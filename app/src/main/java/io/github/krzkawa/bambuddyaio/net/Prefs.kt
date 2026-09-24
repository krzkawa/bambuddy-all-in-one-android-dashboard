package io.github.krzkawa.bambuddyaio.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.KeyStore

/** Everything the app remembers between launches. */
class Prefs(ctx: Context) {

    private val app = ctx.applicationContext

    /** Plain settings: the server address and the display preferences. */
    private val sp = app.getSharedPreferences("bambuddy", Context.MODE_PRIVATE)

    /**
     * The API key and the login token live in a separate, keystore-encrypted
     * file. The app is also marked not-backupable, so neither `adb backup` nor
     * Google's auto-backup can lift this file off the phone; encrypting it as
     * well means that even a copy taken off a rooted device is unreadable
     * without that phone's keystore.
     */
    private val secrets: SharedPreferences = openSecrets()

    /**
     * False when the phone's keystore refused to give us an encrypted file and
     * the credentials are sitting in the app's private settings instead. Still
     * private to this app, and still not backed up — Settings says which one
     * he has rather than claiming encryption that is not there.
     */
    val secretsEncrypted: Boolean get() = secrets !== sp

    init {
        migrateSecrets()
        // Logged on every launch so the emulator run in CI, and logcat from his
        // own phone, say which of the two stores the keystore actually allowed.
        Log.i(TAG, if (secretsEncrypted) "Credentials are encrypted" else "Credentials are in plain settings")
    }

    // ------------------------------------------------------------- the store

    private fun openSecrets(): SharedPreferences {
        try {
            return encryptedFile()
        } catch (first: Throwable) {
            Log.w(TAG, "Encrypted settings would not open", first)
        }
        // A keystore can simply be busy — at boot, most often. Ask again before
        // concluding anything, because the next step is not reversible.
        try {
            return encryptedFile()
        } catch (second: Throwable) {
            Log.w(TAG, "Encrypted settings would not open on a second try", second)
        }
        // Twice over means the key really is gone: invalidated by a screen lock
        // being added or removed, or restored onto another phone. What is left
        // behind is a file nothing can decrypt, so throwing it away costs one
        // sign-in. Refusing to open costs him the whole app, so that is never
        // the answer.
        return try {
            app.deleteSharedPreferences(SECRETS_FILE)
            dropMasterKey()
            encryptedFile()
        } catch (third: Throwable) {
            Log.w(TAG, "Keeping the credentials in the app's private settings", third)
            sp
        }
    }

    /**
     * Runs on the main thread at startup. The first launch after an install
     * mints the keystore key, which is the only slow one; after that this is
     * a file read.
     */
    private fun encryptedFile(): SharedPreferences = EncryptedSharedPreferences.create(
        SECRETS_FILE,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        app,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun dropMasterKey() {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            store.deleteEntry(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            // Best effort. If the alias is not there, there is nothing to drop.
        }
    }

    /**
     * Moves credentials written by an older build out of the plain file.
     *
     * The new copy is committed before the old one is removed, so a process
     * killed in the middle still leaves exactly one readable copy of his API
     * key rather than none.
     */
    private fun migrateSecrets() {
        if (!secretsEncrypted) return
        val moved = secrets.edit()
        var anything = false
        for (key in SECRET_KEYS) {
            val old = sp.getString(key, null) ?: continue
            if (old.isNotBlank() && secrets.getString(key, null) == null) {
                moved.putString(key, old)
                anything = true
            }
        }
        if (anything && !moved.commit()) return
        val stale = sp.edit()
        SECRET_KEYS.forEach { stale.remove(it) }
        stale.apply()
    }

    /** Reads a secret, still looking in the old place for a build that crashed mid-migration. */
    private fun secret(key: String): String =
        secrets.getString(key, null) ?: sp.getString(key, "") ?: ""

    private fun setSecret(key: String, value: String) {
        secrets.edit().putString(key, value.trim()).apply()
        if (secretsEncrypted && sp.contains(key)) sp.edit().remove(key).apply()
    }

    // ----------------------------------------------------------- the settings

    /** Base URL of the Bambuddy server, e.g. "http://192.168.1.50:8000". No trailing slash. */
    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    /** API key from Settings > API Keys on the server. Sent as X-API-Key. */
    var apiKey: String
        get() = secret("apiKey")
        set(v) = setSecret("apiKey", v)

    /** JWT from a username/password login. Sent as Authorization: Bearer. */
    var token: String
        get() = secret("token")
        set(v) = setSecret("token", v)

    var username: String
        get() = secret("username")
        set(v) = setSecret("username", v)

    /** Keeps the display awake while the app is open. On by default — this is a wall dashboard. */
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepAwake", true)
        set(v) = sp.edit().putBoolean("keepAwake", v).apply()

    /**
     * Hides the status and navigation bars. On by default: on a 720p phone in
     * landscape they cost about a fifth of the height, which is the difference
     * between all ten tabs fitting and four of them being off the bottom.
     */
    var fullScreen: Boolean
        get() = sp.getBoolean("fullScreen", true)
        set(v) = sp.edit().putBoolean("fullScreen", v).apply()

    /** How often the dashboard refreshes, in seconds. */
    var pollSeconds: Int
        get() = sp.getInt("poll", 4).coerceIn(2, 60)
        set(v) = sp.edit().putInt("poll", v.coerceIn(2, 60)).apply()

    /**
     * Listens on Bambuddy's WebSocket so a change shows within a second, with
     * the poll slowed to a heartbeat while the socket holds. Off by default:
     * polling alone is what has been proven on this phone's wifi.
     */
    var liveUpdates: Boolean
        get() = sp.getBoolean("liveUpdates", false)
        set(v) = sp.edit().putBoolean("liveUpdates", v).apply()

    /** Last printer the user looked at, so the app comes back where they left it. */
    var lastPrinterId: Int
        get() = sp.getInt("lastPrinter", -1)
        set(v) = sp.edit().putInt("lastPrinter", v).apply()

    val configured: Boolean
        get() = serverUrl.isNotBlank()

    val hasCredentials: Boolean
        get() = apiKey.isNotBlank() || token.isNotBlank()

    fun signOut() {
        val edit = secrets.edit()
        SECRET_KEYS.forEach { edit.remove(it) }
        edit.apply()
        val plain = sp.edit()
        SECRET_KEYS.forEach { plain.remove(it) }
        plain.apply()
    }

    private companion object {
        /** Carries the package name so CI's logcat filter picks these up. */
        const val TAG = "bambuddyaio.Prefs"
        const val SECRETS_FILE = "bambuddy_secrets"
        /** androidx.security's own alias; there is no constant exposed for it. */
        const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        val SECRET_KEYS = listOf("apiKey", "token", "username")
    }
}
