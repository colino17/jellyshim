package io.github.colino17.jellyshim

import android.app.Activity
import android.os.Bundle

private val ITEM_ID = Regex("[0-9a-fA-F]{32}")

/**
 * Invisible trampoline: the launcher starts this, it opens the item in the active client app and closes.
 * Exported so the launcher (another app) can start it; it only accepts a 32-character hex item id.
 */
class OpenItemActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(ItemLauncher.EXTRA_ID)
        val play = intent.getBooleanExtra(ItemLauncher.EXTRA_PLAY, false)
        if (id != null && id.matches(ITEM_ID)) {
            ItemLauncher.launch(this, id, play)
        }
        finish()
    }
}
