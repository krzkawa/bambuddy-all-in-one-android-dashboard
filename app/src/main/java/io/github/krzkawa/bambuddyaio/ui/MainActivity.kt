package io.github.krzkawa.bambuddyaio.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.krzkawa.bambuddyaio.R
import io.github.krzkawa.bambuddyaio.nfc.BambuTag
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.ago
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app lives here: a nav rail down the left, one screen at a time on
 * the right. Locked to landscape and holds the screen awake, because it is
 * meant to sit propped up next to a printer.
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private data class Tab(val label: String, val make: () -> Fragment)

    private val tabs = listOf(
        Tab("Printers") { DashboardFragment() },
        Tab("Control") { ControlFragment() },
        Tab("AMS") { AmsFragment() },
        Tab("Scan") { ScanFragment() },
        Tab("Spools") { SpoolsFragment() },
        Tab("Queue") { QueueFragment() },
        Tab("History") { HistoryFragment() },
        Tab("Stats") { StatsFragment() },
        Tab("Camera") { CameraFragment() },
        Tab("Settings") { SettingsFragment() }
    )

    private val railButtons = ArrayList<TextView>()
    private var current = -1
    private var nfc: NfcAdapter? = null
    private lateinit var statusLine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)

        if (!Repo.prefs.configured) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        applyKeepAwake()
        applyFullScreen()
        setContentView(buildLayout())
        nfc = NfcAdapter.getDefaultAdapter(this)

        showTab(if (savedInstanceState != null) savedInstanceState.getInt(KEY_TAB, 0) else 0)

        // The status line ages, so it runs on a clock rather than on the poll:
        // when the wifi drops nothing is emitted at all, and that is precisely
        // when he needs to be told how old the numbers on screen are.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStatusLine()
                    delay(1000)
                }
            }
        }
    }

    private fun refreshStatusLine() {
        val now = System.currentTimeMillis()
        val updated = Repo.updatedAt.value.takeIf { it > 0L }
        val age = updated?.let { now - it }
        val notice = Repo.notice.value?.takeIf { now - it.at < NOTICE_MS }

        val (message, colour) = when {
            Repo.authExpired.value ->
                "Your sign-in has expired. Tap here to sign in again." to Ui.bad(this)
            Repo.error.value != null -> {
                val error = Repo.error.value.orEmpty()
                (if (age == null) error else "$error · last update ${ago(age)}") to Ui.bad(this)
            }
            age != null && age > Repo.staleAfterMs() ->
                "Not live — last update ${ago(age)}" to Ui.warn(this)
            notice != null ->
                notice.message to (if (notice.failed) Ui.bad(this) else Ui.dimColor(this))
            else -> "" to Ui.dimColor(this)
        }

        statusLine.text = message
        statusLine.setTextColor(colour)
        statusLine.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        statusLine.isClickable = Repo.authExpired.value
    }

    /**
     * Bambuddy's account tokens last 24 hours and it has no refresh route, so a
     * phone left against a printer is signed out by morning. This turns that
     * into one tap instead of a trip through Settings.
     */
    private fun askSignIn() {
        val column = Ui.col(this)
        val pad = Ui.dp(this, 16)
        column.setPadding(pad, pad, pad, 0)
        val user = Ui.input(this, "Username or email", Repo.prefs.username)
        val pass = Ui.input(this, "Password")
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        column.addView(user)
        column.addView(Ui.space(this, 8))
        column.addView(pass)

        AlertDialog.Builder(this)
            .setTitle("Sign in again")
            .setMessage("An account login runs out after a day. An API key does not — Settings has the option.")
            .setView(column)
            .setPositiveButton("Sign in") { _, _ ->
                signIn(user.text.toString().trim(), pass.text.toString())
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun signIn(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            Ui.toast(this, "Enter your username and password")
            return
        }
        Repo.notice("Signing in…")
        refreshStatusLine()
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    Repo.prefs.token = Repo.api.login(username, password)
                    Repo.prefs.username = username
                    null
                } catch (e: Exception) {
                    e.message ?: "Could not sign in"
                }
            }
            if (failure == null) {
                Repo.notice("Signed in")
                Repo.signedIn()
            } else {
                Repo.notice(failure, failed = true)
            }
            refreshStatusLine()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, current)
    }

    override fun onResume() {
        super.onResume()
        applyKeepAwake()
        applyFullScreen()
        Repo.start()
        enableReader()
    }

    override fun onPause() {
        super.onPause()
        // Reader mode must be released or no other app can see a tag again.
        try {
            nfc?.disableReaderMode(this)
        } catch (e: Exception) {
            // Adapter can disappear if NFC is switched off while we are running.
        }
    }

    override fun onStop() {
        super.onStop()
        Repo.stop()
    }

    private fun applyKeepAwake() {
        if (Repo.prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * The status bar and the navigation bar together are about 72 dp of a
     * landscape 720p screen's 360 — a fifth of it, spent on chrome a propped-up
     * dashboard never taps. Sticky immersive gives it back and still lets a
     * swipe from the edge bring the bars up for a moment.
     */
    private fun applyFullScreen() {
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        if (Repo.prefs.fullScreen) {
            bars.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A dialog or the notification shade puts the bars back; this takes
        // the screen again once we have focus, which is what "sticky" means.
        if (hasFocus) applyFullScreen()
    }

    // ------------------------------------------------------------------- NFC

    private fun enableReader() {
        val adapter = nfc ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
        val extras = Bundle()
        // Bambu tags need several authenticated sector reads; the default
        // presence check interrupts that on some phones.
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000)
        try {
            adapter.enableReaderMode(this, this, flags, extras)
        } catch (e: Exception) {
            // Nothing to do: the Scan screen already explains when NFC is off.
        }
    }

    /** Called on a binder thread by the NFC stack, never on the main thread. */
    override fun onTagDiscovered(tag: Tag) {
        ScanState.reading()
        lifecycleScope.launch {
            // Passing the context lets the reader tell "this phone cannot do
            // Mifare Classic" apart from "this tag isn't a Bambu tag".
            val result = withContext(Dispatchers.IO) { BambuTag.read(tag, applicationContext) }
            ScanState.found(result)
            withContext(Dispatchers.Main) {
                // The phone is face down against the spool, so the buzz is the
                // only feedback that reaches him at the moment of the read.
                ScanFeedback.buzz(applicationContext, result)
                if (current != SCAN_TAB) showTab(SCAN_TAB)
            }
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = Ui.row(this)
        root.setBackgroundColor(Ui.bg(this))
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )

        root.addView(buildRail(), Ui.lp(this, RAIL_WIDTH_DP, ViewGroup.LayoutParams.MATCH_PARENT))

        val right = Ui.col(this)
        statusLine = Ui.tiny(this, "")
        statusLine.setTextColor(Ui.bad(this))
        statusLine.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), 0)
        statusLine.visibility = View.GONE
        statusLine.setOnClickListener { if (Repo.authExpired.value) askSignIn() }
        right.addView(statusLine, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val frame = FrameLayout(this)
        frame.id = R.id.content_frame
        right.addView(frame, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(right, Ui.lp(this, 0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    private fun buildRail(): View {
        val rail = Ui.col(this)
        rail.setBackgroundColor(Ui.color(this, R.color.bg_rail))
        rail.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6))

        // Ten tabs have to fit a landscape phone's height without scrolling:
        // one that scrolls with nothing to say so hides Settings off the
        // bottom, and he taps down the list looking for a tab that is there.
        tabs.forEachIndexed { index, tab ->
            val item = Ui.body(this, tab.label)
            item.gravity = Gravity.CENTER_VERTICAL
            item.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6))
            item.isClickable = true
            item.setOnClickListener { showTab(index) }
            val lp = Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = Ui.dp(this, 1)
            rail.addView(item, lp)
            railButtons.add(item)
        }

        val scroller = ScrollView(this)
        scroller.setBackgroundColor(Ui.color(this, R.color.bg_rail))
        // Kept as a safety net for a shorter screen or a large system font.
        // The bar no longer fades out, so when it does scroll it says so.
        scroller.isVerticalScrollBarEnabled = true
        scroller.isScrollbarFadingEnabled = false
        scroller.addView(
            rail,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return scroller
    }

    fun showTab(index: Int) {
        if (index !in tabs.indices) return
        current = index
        railButtons.forEachIndexed { i, view ->
            val on = i == index
            view.setTextColor(if (on) Ui.textColor(this) else Ui.dimColor(this))
            view.background = if (on) Ui.rounded(Ui.color(this, R.color.card_alt), 8, this) else null
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_frame, tabs[index].make())
            .commitAllowingStateLoss()
    }

    companion object {
        private const val KEY_TAB = "tab"
        private const val RAIL_WIDTH_DP = 90
        const val SCAN_TAB = 3

        /** How long a command's outcome stays on the status line. */
        private const val NOTICE_MS = 30_000L
    }
}
