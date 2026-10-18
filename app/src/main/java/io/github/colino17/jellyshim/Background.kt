package io.github.colino17.jellyshim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

/** Starts and stops all background syncing (the 6-hour worker and the Continue Watching service). */
object Background {
    /** For work that must finish even if the screen that started it goes away (e.g. logout cleanup). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureRunning(ctx: Context) {
        SyncScheduler.schedule(ctx)
        ContinueWatchingService.start(ctx)
    }

    fun stop(ctx: Context) {
        SyncScheduler.cancel(ctx)
        ContinueWatchingService.stop(ctx)
    }
}

object SyncScheduler {
    private const val PERIODIC = "jellyshim_periodic"
    private const val NOW = "jellyshim_now"
    private const val INTERVAL_HOURS = 6L

    private val needNet = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Periodic background sync. WorkManager persists it across reboots. */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(needNet).build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** One-off sync. With [replace] = false, an already queued/running one is left alone. */
    fun syncSoon(ctx: Context, replace: Boolean = true) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(needNet).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req
        )
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(ctx).cancelUniqueWork(NOW)
    }
}

/** Boot, app update, and launcher "initialize programs" broadcasts all do the same thing. */
class TriggerReceiver : BroadcastReceiver() {
    private val accepted = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        "android.media.tv.action.INITIALIZE_PROGRAMS",
    )

    override fun onReceive(context: Context, intent: Intent) {
        // The receiver is exported (the launcher must be able to reach it), so ignore anything else
        if (intent.action !in accepted) return
        if (!Prefs(context).loggedIn) return
        Background.ensureRunning(context)
        SyncScheduler.syncSoon(context, replace = false)
    }
}
