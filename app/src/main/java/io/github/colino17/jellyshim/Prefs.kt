package io.github.colino17.jellyshim

import android.content.Context
import androidx.core.content.edit

private const val NEVER_SYNCED = "Never synced"
private const val CONTINUE_NOT_SYNCED = "Continue Watching: not synced yet"

/** SharedPreferences wrapper. The token is stored unencrypted, which is fine for a LAN TV app. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("jellyshim", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = sp.getString("serverUrl", null)
        set(v) = sp.edit { putString("serverUrl", v) }

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit { putString("token", v) }

    var userId: String?
        get() = sp.getString("userId", null)
        set(v) = sp.edit { putString("userId", v) }

    var userName: String?
        get() = sp.getString("userName", null)
        set(v) = sp.edit { putString("userName", v) }

    var lastSync: String
        get() = sp.getString("lastSync", NEVER_SYNCED) ?: NEVER_SYNCED
        set(v) = sp.edit { putString("lastSync", v) }

    var lastContinueSync: String
        get() = sp.getString("lastContinueSync", CONTINUE_NOT_SYNCED) ?: CONTINUE_NOT_SYNCED
        set(v) = sp.edit { putString("lastContinueSync", v) }

    /** Message shown on the login screen (e.g. "session expired"). */
    var notice: String?
        get() = sp.getString("notice", null)
        set(v) = sp.edit { putString("notice", v) }

    val loggedIn: Boolean
        get() = !serverUrl.isNullOrBlank() && !token.isNullOrBlank() && !userId.isNullOrBlank()

    /** Forget the session but keep the server URL so it doesn't have to be retyped. */
    fun logout(notice: String? = null) {
        sp.edit {
            remove("token").remove("userId").remove("userName").remove("lastSync").remove("lastContinueSync")
            putString("notice", notice)
        }
    }
}
