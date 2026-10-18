package io.github.colino17.jellyshim

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** The 6-hour background sync (everything: Continue Watching and all libraries). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Prefs(applicationContext).loggedIn) return Result.success()
        val r = ChannelSync(applicationContext).syncAll()
        return when {
            r.ok -> Result.success()
            !r.retryable -> Result.failure()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}
