package io.github.colino17.jellyshim

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Entry points for other apps (e.g. tvQuickActions macros) to ask for a sync right now.
 *  - SYNC_CONTINUE_WATCHING: refresh the Continue Watching channel (about 1-3 seconds)
 *  - SYNC_ALL: Continue Watching plus every library
 * Offered as a broadcast ([SyncReceiver]) and as an invisible activity ([SyncActivity]).
 */
object SyncActions {
    const val CONTINUE_WATCHING = "io.github.colino17.jellyshim.SYNC_CONTINUE_WATCHING"
    const val ALL = "io.github.colino17.jellyshim.SYNC_ALL"

    fun handle(ctx: Context, action: String?) {
        ContinueWatchingService.requestSync(ctx, full = action == ALL)
    }
}

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SyncActions.handle(context, intent.action)
}

class SyncActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncActions.handle(this, intent.action)
        finish()
    }
}

/**
 * For a "home button" macro: syncs Continue Watching, waits until the sync is actually finished
 * (max 8 seconds), then goes to the home screen. Because the data is already written when the
 * launcher comes back, a launcher that refreshes on return (Projectivy does, at most once a minute)
 * shows the new data without any delay guesswork or launcher restart.
 */
class SyncAndHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            if (Prefs(applicationContext).loggedIn) {
                withTimeoutOrNull(8_000L) {
                    withContext(Dispatchers.IO) { ChannelSync(applicationContext).syncContinueWatching() }
                }
            }
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }
}
