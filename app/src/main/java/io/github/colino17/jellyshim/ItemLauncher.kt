package io.github.colino17.jellyshim

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Glue between the launcher rows and the client app chosen in [ClientProfiles.ACTIVE].
 *
 * Programs don't link to the client directly. They launch JellyShim's invisible [OpenItemActivity]
 * (an explicit intent, which launchers handle reliably), and that activity asks the active
 * [ClientProfile] for the real intent when the item is clicked.
 */
object ItemLauncher {
    const val EXTRA_ID = "ItemId"
    const val EXTRA_PLAY = "Play"

    /** The intent stored in a launcher program. [itemId] is the 32-char hex form (no dashes). */
    fun shortcut(ctx: Context, itemId: String, play: Boolean): Intent =
        Intent(ctx, OpenItemActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ID, itemId)
            putExtra(EXTRA_PLAY, play)
        }

    /** Opens or plays the item in the active client. */
    fun launch(ctx: Context, itemId: String, play: Boolean) {
        val profile = ClientProfiles.ACTIVE
        try {
            val intent = (if (play) profile.playItem else profile.openItem)(ctx, itemId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open item $itemId in ${profile.name}", e)
        }
    }
}
