package io.github.colino17.jellyshim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the Continue Watching channel fresh. WorkManager can't run more often than every 15 minutes,
 * so this foreground service polls every [POLL_MS] while the screen is on, and syncs immediately
 * when the TV wakes up. While the screen is off it only checks the screen state.
 */
class ContinueWatchingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "jellyshim_sync"
        private const val POLL_MS = 45_000L
        private const val TICK_MS = 5_000L

        /** Outside requests (macros etc.) closer together than this are ignored / downgraded. */
        private const val MIN_CONTINUE_REQUEST_GAP_MS = 2_000L
        private const val MIN_FULL_REQUEST_GAP_MS = 60_000L
        @Volatile private var lastContinueRequest = -MIN_FULL_REQUEST_GAP_MS
        @Volatile private var lastFullRequest = -MIN_FULL_REQUEST_GAP_MS

        /** Requests for an immediate sync (true = full sync); kept across service restarts. */
        private val syncRequests = Channel<Boolean>(Channel.CONFLATED)
        @Volatile private var running = false

        /** Safe to call from anywhere; returns false if the system refused a background start. */
        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, ContinueWatchingService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start Continue Watching service: $e")
            false
        }

        /**
         * Asks for a sync as soon as possible (within a few seconds): Continue Watching only, or
         * everything if [full]. If the service can't be started, WorkManager does a full sync instead.
         * Requests that come in too often are limited, since any app can send them: a full sync more
         * often than once a minute is downgraded to Continue Watching only, and those are ignored
         * when closer together than two seconds.
         */
        fun requestSync(ctx: Context, full: Boolean = false) {
            if (!Prefs(ctx).loggedIn) return
            val now = SystemClock.elapsedRealtime()
            val wantFull = full && now - lastFullRequest >= MIN_FULL_REQUEST_GAP_MS
            if (wantFull) lastFullRequest = now
            else if (now - lastContinueRequest < MIN_CONTINUE_REQUEST_GAP_MS) return
            lastContinueRequest = now

            syncRequests.trySend(wantFull)
            if (!running && !start(ctx)) SyncScheduler.syncSoon(ctx)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ContinueWatchingService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "startForeground not allowed: $e")
            SyncScheduler.syncSoon(this) // at least one sync, via WorkManager
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (job?.isActive != true) job = scope.launch { pollLoop() }
        return START_STICKY
    }

    private suspend fun CoroutineScope.pollLoop() {
        val prefs = Prefs(this@ContinueWatchingService)
        val sync = ChannelSync(this@ContinueWatchingService)
        // Either can be missing on unusual devices; if so, assume "screen on" and "online"
        val power: PowerManager? = getSystemService(PowerManager::class.java)
        val net: ConnectivityManager? = getSystemService(ConnectivityManager::class.java)
        fun online() = net == null || net.activeNetwork != null
        var lastSync = 0L
        var wasOn = false

        while (isActive) {
            if (!prefs.loggedIn) { // logged out (or login expired): nothing left to do
                stopSelf()
                return
            }
            val on = power?.isInteractive ?: true
            val now = SystemClock.elapsedRealtime()
            val due = now - lastSync >= POLL_MS
            if (on && (due || !wasOn) && online()) {
                sync.syncContinueWatching()
                lastSync = SystemClock.elapsedRealtime()
            }
            wasOn = on

            // Sleep until the next tick, or until someone (e.g. a macro) asks for a sync
            val full = withTimeoutOrNull(TICK_MS) { syncRequests.receive() }
            if (full != null) {
                if (online()) {
                    if (full) sync.syncAll() else sync.syncContinueWatching()
                }
                lastSync = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JellyShim")
            .setContentText("Keeping Continue Watching up to date")
            .setSmallIcon(R.drawable.channel_logo)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }
}
