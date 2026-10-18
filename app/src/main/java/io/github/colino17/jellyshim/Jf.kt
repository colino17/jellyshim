package io.github.colino17.jellyshim

import android.content.Context
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/** Log tag used throughout the app. */
internal const val TAG = "JellyShim"

/** Jellyfin SDK access. */
object Jf {
    @Volatile private var instance: Jellyfin? = null

    private fun get(ctx: Context): Jellyfin = instance ?: synchronized(this) {
        instance ?: createJellyfin {
            clientInfo = ClientInfo(name = "JellyShim", version = versionName(ctx))
            context = ctx.applicationContext
        }.also { instance = it }
    }

    private fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "dev"
    } catch (e: Exception) {
        "dev"
    }

    /** The last client handed out; reused while the server address and token are unchanged. */
    private class Cached(val baseUrl: String, val token: String?, val client: ApiClient)
    @Volatile private var cached: Cached? = null

    /**
     * An API client for [baseUrl] / [token]. The same client is reused on repeated calls, so the
     * connection pool survives between the 45-second polls instead of being rebuilt every time.
     */
    fun api(ctx: Context, baseUrl: String, token: String? = null): ApiClient {
        cached?.let { if (it.baseUrl == baseUrl && it.token == token) return it.client }
        val client = get(ctx).createApi(baseUrl = baseUrl, accessToken = token)
        cached = Cached(baseUrl, token, client)
        return client
    }

    /** Forget the cached client (and the token it holds), e.g. on logout. */
    fun clear() {
        cached = null
    }
}
