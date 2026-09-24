package io.github.krzkawa.bambuddyaio.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.krzkawa.bambuddyaio.net.Api
import io.github.krzkawa.bambuddyaio.net.ApiError
import io.github.krzkawa.bambuddyaio.net.Finder
import io.github.krzkawa.bambuddyaio.net.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * First-run screen: where the Bambuddy server is, and how to authenticate.
 *
 * Both of the server's own options are offered. An API key is the better one
 * for a wall-mounted phone since it never expires; an account login is there
 * for anyone who would rather not mint a key.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var serverField: EditText
    private lateinit var keyField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var message: TextView
    private lateinit var authNote: TextView
    private lateinit var findLine: TextView
    private lateinit var foundList: LinearLayout
    private var finding: Job? = null

    /** The address the note on screen is about, so a re-check is skipped. */
    private var probed = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = Ui.col(this)
        root.setBackgroundColor(Ui.bg(this))
        val pad = Ui.dp(this, 16)
        root.setPadding(pad, pad, pad, pad)

        root.addView(Ui.big(this, "Connect to Bambuddy"))
        root.addView(Ui.dim(this, "Your own server, on your own network."))
        root.addView(Ui.space(this, Ui.XL))

        val columns = Ui.row(this)
        columns.gravity = android.view.Gravity.TOP

        // Left: server address and API key.
        val left = Ui.card(this)
        left.addView(Ui.heading(this, "Server address"))
        serverField = Ui.input(this, "http://192.168.1.50:8000", Repo.prefs.serverUrl)
        serverField.inputType = InputType.TYPE_TEXT_VARIATION_URI
        left.addView(serverField, wide())
        left.addView(Ui.space(this, Ui.XS))
        val finder = Ui.row(this)
        finder.addView(Ui.quiet(this, "Find it") { find() })
        Ui.gap(this, finder, Ui.S)
        findLine = Ui.tiny(this, "Looks for Bambuddy on this phone's wifi.")
        finder.addView(findLine, Ui.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        left.addView(finder, wide())
        foundList = Ui.col(this)
        left.addView(foundList, wide())
        authNote = Ui.tiny(this, "")
        authNote.visibility = View.GONE
        left.addView(authNote, wide())
        // Asking the server what it wants beats making him guess. Checked when
        // he leaves the address box, which is the moment he has finished it.
        serverField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) checkWhatServerWants() }
        left.addView(Ui.space(this, Ui.L))
        left.addView(Ui.heading(this, "API key"))
        left.addView(Ui.tiny(this, "Settings > API Keys on the server. Needs read, control and inventory."))
        left.addView(Ui.space(this, 6))
        keyField = Ui.input(this, "bb_…", Repo.prefs.apiKey)
        left.addView(keyField, wide())

        // Right: account login.
        val right = Ui.card(this)
        right.addView(Ui.heading(this, "Or sign in with your account"))
        userField = Ui.input(this, "Username or email", Repo.prefs.username)
        right.addView(userField, wide())
        right.addView(Ui.space(this, 6))
        passField = Ui.input(this, "Password")
        passField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        right.addView(passField, wide())
        right.addView(Ui.space(this, Ui.S))
        right.addView(Ui.tiny(this, "An account login runs out after a day and asks for the password again. " +
            "An API key never expires, which is what a phone left by a printer wants."))
        right.addView(Ui.space(this, 6))
        right.addView(Ui.tiny(this, "Leave both blank if your server has authentication turned off."))

        columns.addView(left, Ui.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        columns.addView(Ui.space(this, 1), Ui.lp(this, Ui.M, 1))
        columns.addView(right, Ui.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(columns, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(Ui.space(this, Ui.L))
        val actions = Ui.row(this)
        actions.addView(Ui.button(this, "Connect", primary = true) { connect() })
        Ui.gap(this, actions, Ui.M)
        message = Ui.body(this, "")
        actions.addView(message)
        root.addView(actions)

        setContentView(Ui.scroll(this, root))

        // A saved address means he is here to change something, so say what
        // that server wants without waiting for him to retype it.
        if (Repo.prefs.serverUrl.isNotBlank()) checkWhatServerWants() else find()
    }

    private fun wide(): LinearLayout.LayoutParams =
        Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun connect() {
        val server = serverField.text.toString().trim()
        if (server.isBlank()) {
            say("Enter the server address first", error = true)
            return
        }
        Repo.prefs.serverUrl = server
        Repo.prefs.apiKey = keyField.text.toString().trim()

        val user = userField.text.toString().trim()
        val pass = passField.text.toString()
        say("Connecting…", error = false)

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val api = Api(Repo.prefs)
                try {
                    if (user.isNotBlank() && pass.isNotBlank()) {
                        Repo.prefs.token = api.login(user, pass)
                        Repo.prefs.username = user
                    }
                    // Proves the address, the credentials and the permissions
                    // in one call, which is what the user actually wants to know.
                    val printers = api.printers()
                    "ok:${printers.length()}"
                } catch (e: ApiError) {
                    "err:${e.message}"
                } catch (e: Exception) {
                    "err:${e.message ?: "Could not reach the server"}"
                }
            }

            if (outcome.startsWith("ok:")) {
                startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                finish()
            } else {
                say(outcome.removePrefix("err:"), error = true)
            }
        }
    }

    // ------------------------------------------------------- finding the server

    /**
     * Knocks on every address on the wifi for a Bambuddy. Runs by itself on a
     * first launch, when the address box is empty; a few seconds, mostly spent
     * waiting on addresses where nothing is.
     */
    private fun find() {
        if (finding?.isActive == true) return
        val self = Finder.localAddress()
        findLine.setTextColor(Ui.dimColor(this))
        if (self == null) {
            findLine.text = "This phone is not on a wifi network."
            return
        }
        val hosts = Finder.neighbours(self)
        val network = self.hostAddress.orEmpty().substringBeforeLast('.') + ".x"
        foundList.removeAllViews()
        findLine.text = "Looking on $network…"

        finding = lifecycleScope.launch {
            val found = Finder.scan(hosts) {}
            if (found.isEmpty()) {
                findLine.text = "Nothing on $network answered as Bambuddy. Type its address instead."
                return@launch
            }
            findLine.text = if (found.size == 1) "Found it:" else "Found ${found.size}. Tap the one to use:"
            for (server in found) foundList.addView(foundRow(server), wide())
            // One server and nothing typed: that is the answer, so use it.
            if (found.size == 1 && serverField.text.isNullOrBlank()) use(found[0])
        }
    }

    private fun foundRow(server: Finder.Found): LinearLayout {
        val row = Ui.row(this)
        row.setPadding(0, Ui.dp(this, Ui.XS), 0, 0)
        row.addView(Ui.button(this, server.url.removePrefix("http://")) { use(server) })
        Ui.gap(this, row, Ui.S)
        val note = when {
            server.needsSetup -> "Not set up yet"
            server.authEnabled -> "Asks for a login"
            else -> "No login needed"
        }
        row.addView(Ui.tiny(this, note))
        return row
    }

    private fun use(server: Finder.Found) {
        serverField.setText(server.url)
        serverField.setSelection(server.url.length)
        checkWhatServerWants()
    }

    // ----------------------------------------------------- what this server wants

    /**
     * `GET /auth/status` is public and answers the one thing the two boxes
     * below cannot: whether this install asks for a login at all.
     */
    private fun checkWhatServerWants() {
        val server = serverField.text.toString().trim().trimEnd('/')
        if (server.isBlank() || server == probed) return
        probed = server
        authNote.visibility = View.VISIBLE
        authNote.setTextColor(Ui.dimColor(this))
        authNote.text = "Asking the server what it needs…"

        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { authStatus(server) }
            // He may have carried on typing while that was in flight.
            if (server != probed) return@launch
            when {
                status == null -> {
                    // Could be the wrong address, could be an older Bambuddy.
                    // Either way Connect is the honest test, so say nothing.
                    authNote.visibility = View.GONE
                    probed = ""
                }
                status.optBoolean("requires_setup") -> {
                    authNote.setTextColor(Ui.bad(this@SetupActivity))
                    authNote.text = "This Bambuddy has not been set up yet. " +
                        "Open it in a browser and finish setup first."
                }
                status.optBoolean("auth_enabled", true) ->
                    authNote.text = "This server asks for a login. An API key is the one to use — " +
                        "it never expires, while an account login stops working after a day."
                else ->
                    authNote.text = "This server has authentication switched off. " +
                        "Leave the API key and the account login blank."
            }
        }
    }

    /**
     * Done with a bare request rather than through [Api], because [Api] reads
     * the saved server address and nothing is saved until Connect works.
     */
    private fun authStatus(server: String): JSONObject? {
        val root = if (server.startsWith("http")) server else "http://$server"
        val url = ("$root/api/v1/auth/status").toHttpUrlOrNull() ?: return null
        return try {
            probeClient.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                .execute()
                .use { if (it.isSuccessful) JSONObject(it.body?.string().orEmpty()) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun say(text: String, error: Boolean) {
        message.text = text
        message.setTextColor(if (error) Ui.bad(this) else Ui.dimColor(this))
    }

    private companion object {
        /** Short timeouts: this is a hint while he types, not a real request. */
        val probeClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
