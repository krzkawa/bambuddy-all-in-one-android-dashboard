package io.github.krzkawa.bambuddyaio.net

import android.content.Context

/** Everything the app remembers between launches. */
class Prefs(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("bambuddy", Context.MODE_PRIVATE)

    /** Base URL of the Bambuddy server, e.g. "http://192.168.1.50:8000". No trailing slash. */
    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    /** API key from Settings > API Keys on the server. Sent as X-API-Key. */
    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    /** JWT from a username/password login. Sent as Authorization: Bearer. */
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v.trim()).apply()

    /** Keeps the display awake while the app is open. On by default — this is a wall dashboard. */
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepAwake", true)
        set(v) = sp.edit().putBoolean("keepAwake", v).apply()

    /** How often the dashboard refreshes, in seconds. */
    var pollSeconds: Int
        get() = sp.getInt("poll", 4).coerceIn(2, 60)
        set(v) = sp.edit().putInt("poll", v.coerceIn(2, 60)).apply()

    /** Last printer the user looked at, so the app comes back where they left it. */
    var lastPrinterId: Int
        get() = sp.getInt("lastPrinter", -1)
        set(v) = sp.edit().putInt("lastPrinter", v).apply()

    val configured: Boolean
        get() = serverUrl.isNotBlank()

    val hasCredentials: Boolean
        get() = apiKey.isNotBlank() || token.isNotBlank()

    fun signOut() {
        sp.edit().remove("token").remove("apiKey").remove("username").apply()
    }
}
