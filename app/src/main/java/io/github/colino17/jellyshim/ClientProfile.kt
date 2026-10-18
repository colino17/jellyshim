package io.github.colino17.jellyshim

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * How to open or play an item in one particular Jellyfin client app.
 *
 * >>> To use a different client, change [ClientProfiles.ACTIVE] (bottom of this file). <<<
 * To support a client that isn't listed, copy one of the profiles below, adjust the intents, and
 * point ACTIVE at it. Nothing else in the project depends on which client is used, and the choice is
 * made when an item is clicked, so no re-sync is needed after changing it.
 *
 * @param openItem the intent that shows the item's page ([itemId] is the 32-character hex form,
 *                 no dashes, as in the Jellyfin web URL)
 * @param playItem the intent that starts playback; leave it out if the client has no way to do this
 *                 (the item's page is opened instead)
 */
class ClientProfile(
    val name: String,
    val packageName: String,
    val openItem: (ctx: Context, itemId: String) -> Intent,
    val playItem: (ctx: Context, itemId: String) -> Intent = openItem,
)

object ClientProfiles {

    /** Plain "open this URI in that app" intent, for clients that register a deep-link scheme. */
    private fun deepLink(packageName: String, uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(packageName)

    /** Moonfin-Core (the Flutter app, package org.moonfin.androidtv). */
    val MOONFIN = ClientProfile(
        name = "Moonfin",
        packageName = "org.moonfin.androidtv",
        openItem = { _, id -> deepLink("org.moonfin.androidtv", "moonfin://item?id=$id") }, // details page
        playItem = { _, id -> deepLink("org.moonfin.androidtv", "moonfin://play?id=$id") }, // starts playback
    )

    /**
     * The official Jellyfin Android TV app. It opens an item when its startup activity is started
     * with these extras. It has no way to start playback directly, so "play" just opens the item.
     */
    val JELLYFIN_OFFICIAL = ClientProfile(
        name = "Jellyfin (official)",
        packageName = "org.jellyfin.androidtv",
        openItem = { _, id ->
            Intent().apply {
                component = ComponentName(
                    "org.jellyfin.androidtv", "org.jellyfin.androidtv.ui.startup.StartupActivity"
                )
                putExtra("ItemId", id)
                putExtra("ItemIsUserView", false)
            }
        },
    )

    // ------------------------------------------------------------------------------------------
    /** The client that items open in. Change this line to switch clients. */
    val ACTIVE: ClientProfile = MOONFIN
    // ------------------------------------------------------------------------------------------
}
