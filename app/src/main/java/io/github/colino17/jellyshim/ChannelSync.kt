package io.github.colino17.jellyshim

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.LibraryApi
import org.jellyfin.sdk.api.operations.ShowApi
import org.jellyfin.sdk.api.operations.UserViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** [retryable] = false for problems retrying can't fix (e.g. an expired login). */
data class SyncResult(val ok: Boolean, val message: String, val retryable: Boolean = true)

/**
 * Mirrors Jellyfin into Android TV preview channels:
 *  - one channel per library (matched by internalProviderId = library ID)
 *  - one "Continue Watching" channel (resume + next up), refreshed often by [ContinueWatchingService]
 * Programs are matched by internalProviderId = item ID. How programs look is in [ProgramBuilder].
 */
class ChannelSync(private val ctx: Context) {

    companion object {
        /**
         * Continue Watching and the library channels write to different channels and share no state, so
         * they get separate locks: a long library sync can't make a Continue Watching refresh (or a
         * macro) wait. Each lock also guards the state used only by its sync ([seriesCache] by
         * [continueLock], [emptyStreak] by [libraryLock]). Code that needs both takes [continueLock]
         * first; nothing may take them in the other order.
         */
        private val continueLock = Mutex()
        private val libraryLock = Mutex()

        /** Projectivy's maximum row length (with its "increase limit" toggle on). */
        private const val MAX_ITEMS_PER_CHANNEL = 200
        private const val PAGE_SIZE = MAX_ITEMS_PER_CHANNEL + 1 // +1 lets us detect truncation

        /** Library types that don't get a channel (lowercase, underscores removed). */
        private val SKIPPED_LIBRARY_TYPES = setOf("playlists", "boxsets", "livetv")

        private const val CONTINUE_CHANNEL_KEY = "jellyshim:continue-watching"
        private const val CONTINUE_CHANNEL_NAME = "Continue Watching"
        private const val CONTINUE_FETCH_LIMIT = 10 // per source (resume / next up)
        private const val CONTINUE_MAX_ITEMS = 10

        /** A stuck request (e.g. unreachable server) must not hold the lock forever. */
        private const val CONTINUE_TIMEOUT_MS = 25_000L
        private const val FULL_TIMEOUT_MS = 180_000L

        /** A library must come back empty this many syncs in a row before its channel is removed. */
        private const val EMPTY_SYNCS_BEFORE_REMOVAL = 2
        private val emptyStreak = HashMap<String, Int>()

        /** Show (series) artwork for Continue Watching episodes. */
        private val seriesCache = HashMap<UUID, Pair<Long, BaseItemDto>>()
        private const val SERIES_CACHE_MS = 60 * 60 * 1000L // refetch after an hour
        private const val SERIES_CACHE_KEEP_MS = 24 * 60 * 60 * 1000L // forget after a day
    }

    private val resolver = ctx.contentResolver
    private val prefs = Prefs(ctx)
    private val builder = ProgramBuilder(ctx)

    // ------------------------------------------------------------ entry points

    /** Full sync: Continue Watching, then every library (each under its own lock). */
    suspend fun syncAll(): SyncResult {
        val result = guarded(FULL_TIMEOUT_MS) {
            val cw = continueLock.withLock { safely("Continue Watching") { doContinueWatching() } }
            val libs = libraryLock.withLock { doSync() }
            SyncResult(cw.ok && libs.ok, cw.message + "\n" + libs.message, cw.retryable && libs.retryable)
        }
        if (prefs.loggedIn) {
            prefs.lastSync = "${DateFormat.getDateTimeInstance().format(Date())}\n${result.message}"
        }
        return result
    }

    /** Light sync of just the Continue Watching channel; called every ~45s by the service. */
    suspend fun syncContinueWatching(): SyncResult = continueLock.withLock {
        val start = SystemClock.elapsedRealtime()
        val result = guarded(CONTINUE_TIMEOUT_MS) { doContinueWatching() }
        val ms = SystemClock.elapsedRealtime() - start
        if (prefs.loggedIn) {
            prefs.lastContinueSync =
                "Continue Watching synced ${DateFormat.getTimeInstance().format(Date())}, took $ms ms\n${result.message}"
        }
        result
    }

    /** Removes every channel this app has published (used on logout). */
    suspend fun deleteAllChannels() = continueLock.withLock {
        libraryLock.withLock {
            ownChannels().forEach { deleteChannel(it.id) }
            emptyStreak.clear()
            seriesCache.clear()
        }
    }

    // ---------------------------------------------------------- error handling

    private fun isAuthError(e: Throwable) = e is InvalidStatusException && e.status == 401

    /** Turns failures and timeouts into a [SyncResult]; an expired login logs the user out. */
    private suspend fun guarded(timeoutMs: Long, block: suspend () -> SyncResult): SyncResult = try {
        withTimeoutOrNull(timeoutMs) { block() } ?: SyncResult(false, "Sync timed out after ${timeoutMs / 1000}s")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) { // Throwable: also catches linkage errors from the SDK, instead of crashing
        Log.e(TAG, "Sync failed", e)
        if (isAuthError(e)) {
            val msg = "Your Jellyfin login expired. Open JellyShim and log in again."
            prefs.logout(notice = msg)
            Jf.clear()
            SyncResult(false, msg, retryable = false)
        } else {
            SyncResult(false, "Sync failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Runs one part of a sync so its failure doesn't stop the others (auth errors still propagate). */
    private suspend fun safely(name: String, block: suspend () -> SyncResult): SyncResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (isAuthError(e)) throw e
        Log.e(TAG, "$name failed", e)
        SyncResult(false, "$name: FAILED (${e.javaClass.simpleName}: ${e.message})")
    }

    // ------------------------------------------------------------------ session

    private class Session(val base: String, val userId: UUID, val api: ApiClient)

    /** The logged-in session, read once so a concurrent logout can't leave us half-way; null if not logged in. */
    private fun session(): Session? {
        val base = prefs.serverUrl
        val token = prefs.token
        val uid = prefs.userId
        if (base.isNullOrBlank() || token.isNullOrBlank() || uid.isNullOrBlank()) return null
        val userId = try { UUID.fromString(uid) } catch (e: IllegalArgumentException) { return null }
        return Session(base, userId, Jf.api(ctx, base, token))
    }

    private fun notLoggedIn() = SyncResult(false, "Not logged in", retryable = false)

    // ------------------------------------------------------ continue watching

    private suspend fun doContinueWatching(): SyncResult {
        val s = session() ?: return notLoggedIn()

        val items = fetchContinueWatching(s.api, s.userId)
        val series = fetchSeries(s.api, s.userId, items)
        val existing = ownChannels().firstOrNull { it.internalProviderId == CONTINUE_CHANNEL_KEY }
        // The channel is kept even when empty, so it doesn't jump around the launcher
        val channelId = ensureChannel(existing, CONTINUE_CHANNEL_KEY, CONTINUE_CHANNEL_NAME)
        val st = syncPrograms(channelId, s.base, items, continueMode = true, seriesById = series)

        var line = "$CONTINUE_CHANNEL_NAME: ${items.size} items (+${st.added} ~${st.updated} -${st.removed})"
        if (st.failed > 0) line += " - ${st.failed} FAILED: ${st.firstError}"
        return SyncResult(st.failed == 0, line)
    }

    /** In-progress items first (most recently played), then next-up episodes; de-duplicated. */
    private suspend fun fetchContinueWatching(api: ApiClient, userId: UUID): List<BaseItemDto> = coroutineScope {
        // The two requests are independent, so run them at the same time
        val resume = async {
            LibraryApi(api).getResumeItems(
                userId = userId,
                limit = CONTINUE_FETCH_LIMIT,
                fields = listOf(ItemFields.OVERVIEW),
                mediaTypes = listOf(MediaType.VIDEO),
                enableUserData = true,
                enableTotalRecordCount = false, // we never use the total, so don't make the server count
            ).content.items.orEmpty()
        }
        val nextUp = async {
            ShowApi(api).getNextUp(
                userId = userId,
                limit = CONTINUE_FETCH_LIMIT,
                fields = listOf(ItemFields.OVERVIEW),
                enableUserData = true,
                enableResumable = false, // in-progress episodes are already in the resume list
                enableTotalRecordCount = false,
            ).content.items.orEmpty()
        }
        val out = LinkedHashMap<UUID, BaseItemDto>()
        (resume.await() + nextUp.await()).forEach { out.putIfAbsent(it.id, it) }
        out.values.take(CONTINUE_MAX_ITEMS)
    }

    /**
     * The show (series) item for each episode, so its Thumb/Backdrop can be used as artwork.
     * Series are cached for an hour; only unknown ones are requested.
     */
    private suspend fun fetchSeries(api: ApiClient, userId: UUID, items: List<BaseItemDto>): Map<UUID, BaseItemDto> {
        val now = SystemClock.elapsedRealtime()
        seriesCache.entries.removeAll { now - it.value.first > SERIES_CACHE_KEEP_MS }
        val wanted = items.filter { it.type == BaseItemKind.EPISODE }.mapNotNull { it.seriesId }.distinct()
        val missing = wanted.filter { id -> seriesCache[id]?.let { now - it.first > SERIES_CACHE_MS } ?: true }
        if (missing.isNotEmpty()) {
            LibraryApi(api).getItems(
                userId = userId, ids = missing, recursive = true, limit = missing.size,
            ).content.items.orEmpty().forEach { seriesCache[it.id] = now to it }
        }
        return wanted.mapNotNull { id -> seriesCache[id]?.second?.let { id to it } }.toMap()
    }

    // ----------------------------------------------------------- library sync

    private suspend fun doSync(): SyncResult {
        val s = session() ?: return notLoggedIn()

        val allViews = UserViewApi(s.api).getUserViews(userId = s.userId).content.items.orEmpty()
        if (allViews.isEmpty()) {
            // Most likely a hiccup on the server: don't touch anything
            return SyncResult(true, "Server returned no libraries; channels left unchanged")
        }
        val views = allViews.filter { libraryType(it) !in SKIPPED_LIBRARY_TYPES }
        val wantedIds = views.map { it.id.toString() }.toSet()

        val existing: Map<String, Channel> = ownChannels()
            .filter { it.internalProviderId != null }
            .associateBy { it.internalProviderId!! }

        // Channels for libraries that no longer exist, or are of a skipped type
        existing.forEach { (key, ch) ->
            if (key != CONTINUE_CHANNEL_KEY && key !in wantedIds) deleteChannel(ch.id)
        }

        var ok = true
        val lines = mutableListOf<String>()
        for (view in views) {
            val name = view.name ?: "Library"
            val libId = view.id.toString()
            try {
                val (items, truncated) = fetchItems(s.api, s.userId, view.id)
                if (items.isEmpty()) {
                    val streak = (emptyStreak[libId] ?: 0) + 1
                    emptyStreak[libId] = streak
                    if (streak >= EMPTY_SYNCS_BEFORE_REMOVAL) {
                        existing[libId]?.let { deleteChannel(it.id) }
                        lines += "$name: no items, no channel"
                    } else {
                        lines += "$name: no items this time (channel kept in case it's temporary)"
                    }
                    continue
                }
                emptyStreak.remove(libId)
                val channelId = ensureChannel(existing[libId], libId, name)
                val st = syncPrograms(channelId, s.base, items)
                val stored = countPrograms(channelId)

                var line = "$name: ${items.size} items"
                if (truncated) line += " (limited to $MAX_ITEMS_PER_CHANNEL)"
                line += " (+${st.added} ~${st.updated} -${st.removed})"
                if (st.failed > 0) {
                    ok = false
                    line += " - ${st.failed} FAILED: ${st.firstError}"
                } else if (stored != items.size) {
                    line += " - note: $stored in TV database"
                }
                lines += line
            } catch (e: Throwable) {
                if (e is CancellationException || isAuthError(e)) throw e
                Log.e(TAG, "Library '$name' failed", e)
                ok = false
                lines += "$name: FAILED (${e.javaClass.simpleName}: ${e.message})"
            }
        }
        if (lines.isEmpty()) lines += "No libraries to sync"
        return SyncResult(ok, lines.joinToString("\n"))
    }

    /** Library type as a lowercase string; works whether the SDK models it as an enum or a string. */
    private fun libraryType(view: BaseItemDto): String =
        view.collectionType?.toString()?.lowercase()?.replace("_", "") ?: ""

    /** Returns the first [MAX_ITEMS_PER_CHANNEL] items and whether there were more. */
    private suspend fun fetchItems(
        api: ApiClient, userId: UUID, libraryId: UUID
    ): Pair<List<BaseItemDto>, Boolean> {
        val out = LinkedHashMap<UUID, BaseItemDto>() // de-duplicates in case paging overlaps
        var start = 0
        while (out.size <= MAX_ITEMS_PER_CHANNEL) {
            val page = LibraryApi(api).getItems(
                userId = userId,
                parentId = libraryId,
                recursive = true,
                // Replace movies that belong to a collection with the collection itself
                collapseBoxSetItems = true,
                includeItemTypes = listOf(
                    BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.BOX_SET, BaseItemKind.VIDEO
                ),
                // Jellyfin omits the overview from item lists unless it is requested explicitly
                fields = listOf(ItemFields.OVERVIEW),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                startIndex = start,
                limit = PAGE_SIZE,
            ).content.items.orEmpty()
            if (page.isEmpty()) break
            val before = out.size
            page.forEach { out.putIfAbsent(it.id, it) }
            if (out.size == before) break // nothing new: stop rather than loop
            start += page.size
        }
        return out.values.take(MAX_ITEMS_PER_CHANNEL) to (out.size > MAX_ITEMS_PER_CHANNEL)
    }

    // -------------------------------------------------------------- channels

    private fun ownChannels(): List<Channel> {
        val out = mutableListOf<Channel>()
        resolver.query(TvContractCompat.Channels.CONTENT_URI, Channel.PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) out += Channel.fromCursor(c)
        }
        return out.filter {
            it.type == TvContractCompat.Channels.TYPE_PREVIEW && it.packageName == ctx.packageName
        }
    }

    private fun ensureChannel(existing: Channel?, key: String, name: String): Long {
        if (existing != null) {
            if (existing.displayName != name) {
                val cv = ContentValues().apply { put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, name) }
                resolver.update(TvContractCompat.buildChannelUri(existing.id), cv, null, null)
            }
            return existing.id
        }
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(name)
            .setInternalProviderId(key)
            .build()
        val uri = resolver.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues())
            ?: error("Could not create channel '$name'")
        val id = ContentUris.parseId(uri)
        storeLogo(id)
        return id
    }

    private fun deleteChannel(channelId: Long) {
        resolver.delete(TvContractCompat.buildChannelUri(channelId), null, null)
    }

    private fun storeLogo(channelId: Long) {
        val d = ctx.getDrawable(R.drawable.channel_logo) ?: return
        val bmp = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 320, 320)
        d.draw(Canvas(bmp))
        ChannelLogoUtils.storeChannelLogo(ctx, channelId, bmp)
        bmp.recycle()
    }

    // -------------------------------------------------------------- programs

    private class Stats(
        var added: Int = 0, var updated: Int = 0, var removed: Int = 0,
        var failed: Int = 0, var firstError: String? = null
    )

    private class ExistingProgram(val rowId: Long, val hash: Long?)

    private fun countPrograms(channelId: Long): Int =
        resolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            arrayOf(TvContractCompat.PreviewPrograms._ID), null, null, null
        )?.use { it.count } ?: -1

    /**
     * New items are bulk-inserted, changed ones updated in place, vanished ones deleted.
     * "Changed" is detected with a content hash stored in the program's internalProviderFlag1, so
     * unchanged items cost no more than a hash comparison.
     */
    private fun syncPrograms(
        channelId: Long, base: String, items: List<BaseItemDto>, continueMode: Boolean = false,
        seriesById: Map<UUID, BaseItemDto> = emptyMap(),
    ): Stats {
        val existing = mutableMapOf<String, ExistingProgram>() // item id -> row
        resolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            PreviewProgram.PROJECTION, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val p = PreviewProgram.fromCursor(c)
                val flag: Long? = p.internalProviderFlag1 // null if never set
                p.internalProviderId?.let { existing[it] = ExistingProgram(p.id, flag) }
            }
        }

        val stats = Stats()
        val seen = HashSet<String>()
        val toInsert = ArrayList<ContentValues>()

        items.forEachIndexed { index, item ->
            val pid = item.id.toString()
            if (!seen.add(pid)) return@forEachIndexed
            try {
                val plan = builder.plan(
                    base, item, weight = items.size - index, continueMode,
                    series = item.seriesId?.let { seriesById[it] },
                )
                val old = existing[pid]
                when {
                    old == null -> toInsert += builder.toProgram(channelId, plan).toContentValues()
                    old.hash != plan.hash -> {
                        val values = builder.toProgram(channelId, plan).toContentValues()
                        // An update only writes the fields that are set. Older versions published a
                        // duration / playback position; clear them so they can't linger.
                        values.putNull(TvContractCompat.PreviewPrograms.COLUMN_DURATION_MILLIS)
                        values.putNull(TvContractCompat.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
                        resolver.update(TvContractCompat.buildPreviewProgramUri(old.rowId), values, null, null)
                        stats.updated++
                    }
                }
            } catch (e: Exception) {
                stats.failed++
                if (stats.firstError == null) stats.firstError = e.message ?: e.javaClass.simpleName
            }
        }

        if (toInsert.isNotEmpty()) {
            try {
                val n = resolver.bulkInsert(TvContractCompat.PreviewPrograms.CONTENT_URI, toInsert.toTypedArray())
                stats.added = n
                if (n < toInsert.size) {
                    stats.failed += toInsert.size - n
                    if (stats.firstError == null) stats.firstError = "bulk insert stored $n of ${toInsert.size}"
                }
            } catch (e: Exception) {
                stats.failed += toInsert.size
                if (stats.firstError == null) stats.firstError = e.message ?: e.javaClass.simpleName
            }
        }

        existing.forEach { (pid, row) ->
            if (pid !in seen) {
                resolver.delete(TvContractCompat.buildPreviewProgramUri(row.rowId), null, null)
                stats.removed++
            }
        }
        return stats
    }
}
