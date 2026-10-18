package io.github.colino17.jellyshim

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.operations.AuthenticationApi
import org.jellyfin.sdk.api.operations.SessionApi
import org.jellyfin.sdk.model.api.AuthenticateUserByName

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private var statusView: TextView? = null
    private var continueView: TextView? = null
    private var syncButton: Button? = null
    private var syncing = false
    private var showingMain = false

    /** The plain-http public address the user has already been warned about. */
    private var insecureAcknowledged: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 50, 80, 50)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        if (prefs.loggedIn) Background.ensureRunning(this) // make sure background syncing is running
        render()
    }

    override fun onResume() {
        super.onResume()
        // A background sync may have finished, or the login may have expired, while we were away
        if (prefs.loggedIn != showingMain) render()
        else if (showingMain && !syncing) { status(prefs.lastSync); continueView?.text = prefs.lastContinueSync }
    }

    private fun render() {
        root.removeAllViews()
        statusView = null
        continueView = null
        syncButton = null
        showingMain = prefs.loggedIn
        if (showingMain) showMain() else showLogin()
    }

    // ---------------------------------------------------------- UI helpers

    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text = text; textSize = size; setPadding(0, 12, 0, 12)
        root.addView(this)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setOnClickListener { onClick() }
        root.addView(this)
    }

    private fun edit(hint: String, text: String = "", password: Boolean = false, uri: Boolean = false) =
        EditText(this).apply {
            this.hint = hint; setText(text); setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or when {
                password -> InputType.TYPE_TEXT_VARIATION_PASSWORD
                uri -> InputType.TYPE_TEXT_VARIATION_URI
                else -> 0
            }
            root.addView(this)
        }

    private fun status(text: String) { statusView?.text = text }

    // --------------------------------------------------------------- login

    private fun showLogin() {
        label("JellyShim", 28f)
        prefs.notice?.let { label(it) }
        label("Sign in to your Jellyfin server")
        val url = edit("Server URL, e.g. http://192.168.1.50:8096", prefs.serverUrl ?: "http://", uri = true)
        val user = edit("Username")
        val pass = edit("Password", password = true)
        button("Log in") {
            login(normalizeUrl(url.text.toString()), user.text.toString(), pass.text.toString())
        }
        statusView = label("")
    }

    private fun login(base: String, user: String, pass: String) {
        // Plain http to a public address would send the password and token unencrypted over the internet
        if (isInsecurePublic(base) && insecureAcknowledged != base) {
            insecureAcknowledged = base
            status("Warning: $base is not on a private network and isn't encrypted (http). " +
                "Your password and login token could be intercepted. Use https if you can, " +
                "or press Log in again to continue anyway.")
            return
        }
        lifecycleScope.launch {
            try {
                status("Logging in...")
                val auth = withContext(Dispatchers.IO) {
                    AuthenticationApi(Jf.api(this@MainActivity, base))
                        .authenticateUserByName(AuthenticateUserByName(username = user, pw = pass)).content
                }
                val token = auth.accessToken
                val userId = auth.user?.id
                if (token == null || userId == null) {
                    status("Login failed: unexpected response from server")
                    return@launch
                }
                prefs.serverUrl = base
                prefs.token = token
                prefs.userId = userId.toString()
                prefs.userName = auth.user?.name
                prefs.notice = null
                Background.ensureRunning(this@MainActivity)
                render()
                runSync()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Login failed", e)
                status("Login failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- main

    private fun showMain() {
        label("JellyShim", 28f)
        label("Signed in as ${prefs.userName ?: "?"} on ${prefs.serverUrl}")
        label("Continue Watching refreshes about every 45 seconds while the TV is on; libraries every 6 hours.")
        syncButton = button("Sync now") { runSync() }.also { it.isEnabled = !syncing }
        statusView = label(if (syncing) "Syncing..." else prefs.lastSync)
        continueView = label(prefs.lastContinueSync)
        button("Log out and remove channels") { logout() }
    }

    private fun runSync() {
        if (syncing) return
        syncing = true
        syncButton?.isEnabled = false
        status("Syncing...")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ChannelSync(this@MainActivity).syncAll() }
            } finally {
                syncing = false
            }
            // If the login expired during the sync, this swaps to the login screen
            if (prefs.loggedIn) {
                syncButton?.isEnabled = true
                status(prefs.lastSync)
                continueView?.text = prefs.lastContinueSync
            } else render()
        }
    }

    /**
     * Local state goes first: once the credentials are gone, the background service and worker stop
     * on their own and can't hit a revoked token (which would show a bogus "login expired" notice) or
     * recreate channels. Telling the server and deleting the channels follows, and is done on a
     * scope that outlives this screen so it always completes.
     */
    private fun logout() {
        val url = prefs.serverUrl
        val token = prefs.token
        val appContext = applicationContext
        Background.stop(this)
        prefs.logout()
        render()
        Background.scope.launch {
            if (url != null && token != null) {
                try {
                    SessionApi(Jf.api(appContext, url, token)).reportSessionEnded()
                } catch (e: Exception) {
                    // Server unreachable etc.: we are logged out locally regardless
                }
            }
            ChannelSync(appContext).deleteAllChannels()
            Jf.clear()
        }
    }
}
