package io.github.colino17.jellyshim

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** Separator between the parts of the first subtext line. */
private const val SEPARATOR = "   ·   "
private const val MAX_DESCRIPTION_CHARS = 500

/** Bump to force every program to be rewritten on the next sync. */
private const val HASH_VERSION = 6

internal class Img(val url: String, val aspect: Int)

/**
 * Everything that decides what a program looks like, computed cheaply (no HTML parsing), plus a hash of
 * it. The sync compares [hash] with the stored one and only builds the full program if it differs.
 */
internal class ProgramPlan(
    val itemId: String,
    val type: Int,
    val title: String,
    val infoLine: String?,
    val rawOverview: String?,
    val img: Img?,
    val weight: Int,
    val intent: Intent,
    val hash: Long,
)

/** Turns Jellyfin items into launcher programs. */
internal class ProgramBuilder(private val ctx: Context) {

    /**
     * @param continueMode Continue Watching rows: the click plays, and in-progress items get the
     *                     "IN PROGRESS · … REMAINING" text
     * @param series the show, for episodes (its artwork is used)
     */
    fun plan(base: String, item: BaseItemDto, weight: Int, continueMode: Boolean, series: BaseItemDto?): ProgramPlan {
        val hexId = item.id.toString().replace("-", "")
        val isEpisode = item.type == BaseItemKind.EPISODE
        val type = when (item.type) {
            BaseItemKind.MOVIE -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
            BaseItemKind.SERIES -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
            BaseItemKind.EPISODE -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
            else -> TvContractCompat.PreviewPrograms.TYPE_CLIP
        }

        val posMs = (item.userData?.playbackPositionTicks ?: 0L) / 10_000L
        val durMs = (item.runTimeTicks ?: 0L) / 10_000L
        val inProgress = continueMode && posMs > 0 && durMs > posMs

        // Title: just the episode / movie title
        val title = item.name ?: (if (isEpisode) item.seriesName else null) ?: "Untitled"

        // First subtext line: "Season 2 · Episode 5 · IN PROGRESS · 21 MINUTES REMAINING"
        // (each part only when it applies); the overview follows on the next line
        val infoLine = listOfNotNull(
            if (isEpisode) item.parentIndexNumber?.let { "Season $it" } else null,
            if (isEpisode) item.indexNumber?.let { "Episode $it" } else null,
            if (inProgress) "IN PROGRESS" else null,
            if (inProgress) remainingText(durMs - posMs) else null,
        ).joinToString(SEPARATOR).takeIf { it.isNotEmpty() }

        val img = pickImage(base, hexId, item, series)
        // Continue Watching plays straight away; everything else opens the details page
        val intent = ItemLauncher.shortcut(ctx, hexId, play = continueMode)

        val hash = fnv1a64(
            listOf(
                HASH_VERSION, title, infoLine, item.overview, img?.url, img?.aspect, weight, type,
                intent.toUri(Intent.URI_INTENT_SCHEME),
            ).joinToString("\u0000")
        )
        return ProgramPlan(item.id.toString(), type, title, infoLine, item.overview, img, weight, intent, hash)
    }

    fun toProgram(channelId: Long, plan: ProgramPlan): PreviewProgram {
        val description = listOfNotNull(plan.infoLine, plainText(plan.rawOverview))
            .joinToString("\n").takeIf { it.isNotEmpty() }?.take(MAX_DESCRIPTION_CHARS)
        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(plan.type)
            .setTitle(plan.title)
            .setDescription(description)
            .setInternalProviderId(plan.itemId)
            .setInternalProviderFlag1(plan.hash)
            .setWeight(plan.weight)
            .setIntent(plan.intent)
            .apply {
                plan.img?.let {
                    setPosterArtUri(Uri.parse(it.url))
                    setPosterArtAspectRatio(it.aspect)
                }
            }
            .build()
    }

    /** Overviews can contain HTML; launchers show it literally. */
    private fun plainText(html: String?): String? =
        html?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim() }
            ?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- images

    /** Image tag maps are keyed by String or by enum depending on SDK version; match by name. */
    private fun tagFor(map: Map<*, String>?, name: String): String? =
        map?.entries?.firstOrNull { it.key.toString().replace("_", "").equals(name, ignoreCase = true) }?.value

    /** The item's Thumb, then its first Backdrop (both 16:9); null if it has neither. */
    private fun wideImage(base: String, src: BaseItemDto): Img? {
        val hex = src.id.toString().replace("-", "")
        tagFor(src.imageTags, "Thumb")?.let {
            return Img("$base/Items/$hex/Images/Thumb?tag=$it&maxWidth=640&quality=90",
                TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        }
        src.backdropImageTags?.firstOrNull()?.let {
            return Img("$base/Items/$hex/Images/Backdrop/0?tag=$it&maxWidth=640&quality=90",
                TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        }
        return null
    }

    /**
     * Episodes: the show's Thumb (else its Backdrop), else the episode's own still (all 16:9).
     * Everything else: Thumb, then Backdrop (16:9), then the Primary poster (2:3).
     */
    private fun pickImage(base: String, hexId: String, item: BaseItemDto, series: BaseItemDto?): Img? {
        if (item.type == BaseItemKind.EPISODE) {
            series?.let { wideImage(base, it) }?.let { return it }
            tagFor(item.imageTags, "Primary")?.let {
                return Img("$base/Items/$hexId/Images/Primary?tag=$it&maxWidth=640&quality=90",
                    TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            }
            return null
        }
        wideImage(base, item)?.let { return it }
        tagFor(item.imageTags, "Primary")?.let {
            return Img("$base/Items/$hexId/Images/Primary?tag=$it&maxHeight=480&quality=90",
                TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
        }
        return null
    }
}
