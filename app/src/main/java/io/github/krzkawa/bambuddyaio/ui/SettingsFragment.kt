package io.github.krzkawa.bambuddyaio.ui

import android.content.Intent
import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.str

/** Connection, refresh rate, the always-on screen, and what this build is. */
class SettingsFragment : BaseFragment() {

    private lateinit var body: LinearLayout

    override fun build(ctx: Context) {
        content.addView(header(ctx, "Settings"))
        body = Ui.col(ctx)
        content.addView(body, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        render()
    }

    private fun render() {
        val ctx = context ?: return
        body.removeAllViews()
        val prefs = Repo.prefs

        val connection = Ui.card(ctx)
        connection.addView(Ui.heading(ctx, "Server"))
        connection.addView(Ui.body(ctx, prefs.serverUrl.ifBlank { "Not set" }))
        val how = when {
            prefs.apiKey.isNotBlank() -> "Signed in with an API key"
            prefs.token.isNotBlank() -> "Signed in as ${prefs.username}"
            else -> "No credentials — only works if the server has authentication off"
        }
        connection.addView(Ui.tiny(ctx, how))
        connection.addView(Ui.space(ctx, 8))
        val row = Ui.row(ctx)
        row.addView(Ui.button(ctx, "Change") {
            startActivity(Intent(ctx, SetupActivity::class.java))
            activity?.finish()
        })
        row.addView(Ui.space(ctx, 1), Ui.lp(ctx, 8, 1))
        row.addView(Ui.button(ctx, "Sign out") {
            AlertDialog.Builder(ctx)
                .setTitle("Sign out?")
                .setMessage("The app will ask for the server address and credentials again.")
                .setPositiveButton("Sign out") { _, _ ->
                    prefs.signOut()
                    startActivity(Intent(ctx, SetupActivity::class.java))
                    activity?.finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        connection.addView(row, wide(ctx))
        body.addView(connection)
        body.addView(Ui.space(ctx, 8))

        val display = Ui.card(ctx)
        display.addView(Ui.heading(ctx, "Display"))
        val keepRow = Ui.row(ctx)
        keepRow.addView(
            Ui.body(ctx, "Keep the screen on while the app is open"),
            Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        keepRow.addView(Ui.button(ctx, if (prefs.keepScreenOn) "On" else "Off", primary = prefs.keepScreenOn) {
            prefs.keepScreenOn = !prefs.keepScreenOn
            (activity as? MainActivity)?.recreate()
        })
        display.addView(keepRow, wide(ctx))
        display.addView(Ui.tiny(ctx, "The app is locked to landscape either way."))
        body.addView(display)
        body.addView(Ui.space(ctx, 8))

        val refresh = Ui.card(ctx)
        refresh.addView(Ui.heading(ctx, "Refresh every"))
        refresh.addView(Ui.tiny(ctx, "Slower is easier on an old phone and on the server's rate limit."))
        refresh.addView(Ui.space(ctx, 6))
        val rates = Ui.row(ctx)
        listOf(2, 4, 8, 15, 30).forEach { seconds ->
            rates.addView(Ui.button(ctx, "${seconds}s", primary = prefs.pollSeconds == seconds) {
                prefs.pollSeconds = seconds
                render()
            })
            rates.addView(Ui.space(ctx, 1), Ui.lp(ctx, 6, 1))
        }
        refresh.addView(rates, wide(ctx))
        body.addView(refresh)
        body.addView(Ui.space(ctx, 8))

        val about = Ui.card(ctx)
        about.addView(Ui.heading(ctx, "Server details"))
        val details = Ui.dim(ctx, "Loading…")
        about.addView(details)
        background({ Repo.api.systemInfo() }) { result ->
            result.onSuccess { info ->
                val version = info.str("version") ?: "unknown"
                details.text = "Bambuddy $version"
            }
            result.onFailure { details.text = it.message ?: "Could not read the server details" }
        }
        body.addView(about)
    }

    private fun wide(ctx: Context) =
        Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
