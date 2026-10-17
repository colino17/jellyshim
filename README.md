# JellyShim
An small AndroidTV app that acts as a shim between Moonfin (Jellyfin Client) and the Projectivy Launcher.

## Purpose
I wasn't satisfied with the "channel" integrations offered by any of the well-maintained Jellyfin clients. For years I used a modified version of Kodi as my launcher and was able to browse and open all my Jellyfin movies and shows from the same screen that I launched my other apps. 

Due to some issues with Kodi I decided to switch to Projectivy as my launcher and use one of the native Jellyfin clients for playback, but that raised the issue that I needed to open that client and navigate to the specific library before I could browse Jellyfin clients. The various clients available offer limited interaction with the Android TV launcher "channels" functionality, usually limited to "next up" or "recently added" channels.

JellyShim acts as a shim between the Projectivy launcher and the Moonfin client. Basically, it has a simple interface that allows you to login to your Jellyfin server, and sync your libraries to library "channels" (as well as a "Continue Watching" channel. These channels can be displayed on the Projectivy launcher home screen, and clicking on an item will use Moonfin deeplinks to launch that content using the Moonfin client.

## Features
- Creates Android TV "channels" for each Jellyfin library you have (excluding collections, playlists, and live TV).
- Creates a "Continue Watching" channel that contains items from Jellyfin's "Continue Watching" and "Next Up" sections.
- Syncs regular channels every six hours.
- Syncs the "Continue Watching" channel every 45 seconds.
- Has a Activity/Intent that when manually triggered will sync the "Continue Watching" channel and then go home to the launcher screen. This is useful if you are using something like TVQuickActions to manually trigger certain actions on button presses. Personally I have remapped my home button to run a macro that triggers the activity and then runs an ADB command to reload the launcher which helps ensure that the channel is up to date whenever I go back to the home screen (unless reloaded Projectivy only updates channel content on an interval).
- Movie library channels group any movies that are part of a collection into a single item for the entire collection. This reduces clutter and helps mitigate the item cap mentioned below.

## Limitations
- Channels are capped at 200 items. This cap was chosen as it mirrors the upper display limit that Projectivy allows. Projectivy has a slider that caps out at 200, so even if a channel had more than 200 items only 200 could be displayed by Projectivy.
- Currently only works with Moonfin.

## Using a different client
All client-specific logic lives in ClientProfile.kt. If you would like to use a different client other than Moonfin feel free to fork and modify that file to achieve the desired result. ClientProfiles.ACTIVE at the bottom of the file sets the client. There are builtin profiles for MOONFIN and JELLYFIN_OFFICIAL (although the latter has received minimal testing. 

To use another client, you would need to copy a profile, change the package name and the two intents (open/play), and point ACTIVE at it.

## Disclosure
Claude was heavily used to assist the development of the subject code.
