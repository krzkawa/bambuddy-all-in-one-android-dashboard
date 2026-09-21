package io.github.krzkawa.bambuddyaio.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.krzkawa.bambuddyaio.net.Api
import io.github.krzkawa.bambuddyaio.net.ApiError
import io.github.krzkawa.bambuddyaio.net.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = Ui.col(this)
        root.setBackgroundColor(Ui.bg(this))
        val pad = Ui.dp(this, 16)
        root.setPadding(pad, pad, pad, pad)

        root.addView(Ui.big(this, "Connect to Bambuddy"))
        root.addView(Ui.dim(this, "The address of your own Bambuddy server, on your network."))
        root.addView(Ui.space(this, 12))

        val columns = Ui.row(this)
        columns.gravity = android.view.Gravity.TOP

        // Left: server address and API key.
        val left = Ui.col(this)
        left.addView(Ui.heading(this, "Server address"))
        serverField = Ui.input(this, "http://192.168.1.50:8000", Repo.prefs.serverUrl)
        serverField.inputType = InputType.TYPE_TEXT_VARIATION_URI
        left.addView(serverField, wide())
        left.addView(Ui.space(this, 10))
        left.addView(Ui.heading(this, "API key"))
        left.addView(Ui.dim(this, "Settings > API Keys on the server. Needs read, control and inventory."))
        keyField = Ui.input(this, "bb_…", Repo.prefs.apiKey)
        left.addView(keyField, wide())

        // Right: account login.
        val right = Ui.col(this)
        right.addView(Ui.heading(this, "Or sign in with your account"))
        userField = Ui.input(this, "Username or email", Repo.prefs.username)
        right.addView(userField, wide())
        right.addView(Ui.space(this, 6))
        passField = Ui.input(this, "Password")
        passField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        right.addView(passField, wide())
        right.addView(Ui.space(this, 6))
        right.addView(Ui.dim(this, "Leave both sides blank if your server has authentication turned off."))

        columns.addView(left, Ui.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        columns.addView(Ui.space(this, 1), Ui.lp(this, 16, 1))
        columns.addView(right, Ui.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(columns, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(Ui.space(this, 14))
        val actions = Ui.row(this)
        actions.addView(Ui.button(this, "Connect", primary = true) { connect() })
        actions.addView(Ui.space(this, 1), Ui.lp(this, 10, 1))
        message = Ui.body(this, "")
        actions.addView(message)
        root.addView(actions)

        setContentView(Ui.scroll(this, root))
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

    private fun say(text: String, error: Boolean) {
        message.text = text
        message.setTextColor(if (error) Ui.bad(this) else Ui.dimColor(this))
    }
}
