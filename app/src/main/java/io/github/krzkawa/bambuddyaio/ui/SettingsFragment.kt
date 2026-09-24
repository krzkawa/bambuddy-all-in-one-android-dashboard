package io.github.krzkawa.bambuddyaio.ui

import android.content.Intent
import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.net.Live
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.str

/** Connection, refresh rate, the always-on screen, and what this build is. */
class SettingsFragment : BaseFragment() {

    private lateinit var body: LinearLayout
    private var liveLine: TextView? = null

    override fun build(ctx: Context) {
        body = Ui.col(ctx)
        content.addView(body, Ui.wide(ctx))
        render()
        observe(Repo.live) { showLive() }
    }

    private fun render() {
        val ctx = context ?: return
        body.removeAllViews()
        val prefs = Repo.prefs

        val connection = Ui.card(ctx)
        connection.addView(Ui.heading(ctx, "Server"))
        connection.addView(Ui.title(ctx, prefs.serverUrl.ifBlank { "Not set" }))
        val how = when {
            prefs.apiKey.isNotBlank() -> "Signed in with an API key"
            prefs.token.isNotBlank() -> "Signed in as ${prefs.username}"
            else -> "No credentials — only works if the server has authentication off"
        }
        connection.addView(Ui.tiny(ctx, how))
        if (prefs.hasCredentials) {
            connection.addView(
                Ui.tiny(
                    ctx,
                    if (prefs.secretsEncrypted) {
                        "Stored encrypted on this phone, and left out of any backup."
                    } else {
                        "Stored in this app's private settings and left out of any backup. " +
                            "This phone's keystore would not take them, so they are not encrypted."
                    }
                )
            )
        }
        connection.addView(Ui.space(ctx, Ui.M))
        val row = Ui.row(ctx)
        row.addView(Ui.button(ctx, "Change") {
            startActivity(Intent(ctx, SetupActivity::class.java))
            activity?.finish()
        })
        Ui.gap(ctx, row, Ui.S)
        row.addView(Ui.quiet(ctx, "Sign out") {
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
        body.addView(Ui.space(ctx, Ui.M))

        val display = Ui.card(ctx)
        display.addView(Ui.heading(ctx, "Screen"))
        display.addView(
            toggle(
                ctx, "Keep the screen on", "While the app is open.", prefs.keepScreenOn
            ) {
                prefs.keepScreenOn = !prefs.keepScreenOn
                (activity as? MainActivity)?.recreate()
            },
            wide(ctx)
        )
        display.addView(Ui.divider(ctx))
        display.addView(
            toggle(
                ctx, "Use the whole screen",
                "Hides the Android bars, which is what makes all ten tabs fit. " +
                    "Swipe in from an edge to bring them back.",
                prefs.fullScreen
            ) {
                prefs.fullScreen = !prefs.fullScreen
                (activity as? MainActivity)?.recreate()
            },
            wide(ctx)
        )
        body.addView(display)
        body.addView(Ui.space(ctx, Ui.M))

        val refresh = Ui.card(ctx)
        val refreshRow = Ui.row(ctx)
        val words = Ui.col(ctx)
        words.addView(Ui.body(ctx, "Refresh every"))
        words.addView(Ui.tiny(ctx, "Slower is easier on an old phone and on the server."))
        refreshRow.addView(words, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val rates = listOf(2, 4, 8, 15, 30)
        refreshRow.addView(
            Ui.segmented(ctx, rates.map { "${it}s" }, rates.indexOf(prefs.pollSeconds)) { index ->
                prefs.pollSeconds = rates[index]
                render()
            }
        )
        refresh.addView(refreshRow, wide(ctx))
        refresh.addView(Ui.space(ctx, Ui.S))
        refresh.addView(Ui.divider(ctx))
        refresh.addView(
            toggle(
                ctx, "Live updates",
                "The server says the moment something changes, instead of the phone asking. " +
                    "If that connection drops, the phone goes back to asking.",
                prefs.liveUpdates
            ) {
                Repo.setLiveUpdates(!prefs.liveUpdates)
                render()
            },
            wide(ctx)
        )
        val line = Ui.tiny(ctx, "")
        liveLine = line
        refresh.addView(line, wide(ctx))
        showLive()
        body.addView(refresh)
        body.addView(Ui.space(ctx, Ui.M))

        val about = Ui.card(ctx)
        about.addView(Ui.heading(ctx, "Versions"))
        // He installs every build from the same release link, so the app has to
        // say which one it is; the server version alone never answered that.
        about.addView(Ui.body(ctx, "This app ${appVersion(ctx)}"))
        val details = Ui.dim(ctx, "Reading the server version…")
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

    /** Whether the socket is actually carrying the updates, since a setting alone does not say. */
    private fun showLive() {
        val line = liveLine ?: return
        val ctx = context ?: return
        val on = Repo.prefs.liveUpdates
        line.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        if (!on) return
        val (text, colour) = when (Repo.live.value) {
            Live.State.LIVE -> "Connected. Still checking in every ${maxOf(Repo.prefs.pollSeconds, 30)}s." to Ui.good(ctx)
            Live.State.CONNECTING -> "Connecting…" to Ui.dimColor(ctx)
            Live.State.RETRYING ->
                "Not connected — asking every ${Repo.prefs.pollSeconds}s until it is back." to Ui.warn(ctx)
            Live.State.OFF -> "Starts when the dashboard is on screen." to Ui.dimColor(ctx)
        }
        line.text = text
        line.setTextColor(colour)
    }

    /**
     * Read back off the installed package rather than from BuildConfig, so it
     * is the APK actually on the phone that is being reported.
     */
    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    /** A setting, its explanation, and the switch that is really a button. */
    private fun toggle(
        ctx: Context,
        label: String,
        detail: String,
        on: Boolean,
        onTap: () -> Unit
    ): LinearLayout {
        val row = Ui.row(ctx)
        row.setPadding(0, Ui.dp(ctx, Ui.S), 0, Ui.dp(ctx, Ui.S))
        val words = Ui.col(ctx)
        words.addView(Ui.body(ctx, label))
        words.addView(Ui.tiny(ctx, detail))
        row.addView(words, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        Ui.gap(ctx, row, Ui.M)
        row.addView(Ui.segmented(ctx, listOf("Off", "On"), if (on) 1 else 0) { index ->
            if ((index == 1) != on) onTap()
        })
        return row
    }
}
