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
import android.widget.ImageView
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

    private data class Tab(val label: String, val icon: Int, val make: () -> Fragment)

    /**
     * Ten tabs is a lot to scan. They are ordered the way the work goes — the
     * machine, then the filament, then the jobs — and ruled off in those three
     * groups so the eye can jump to a group instead of reading all ten.
     */
    private val tabs = listOf(
        Tab("Printers", R.drawable.nav_printers) { DashboardFragment() },
        Tab("Control", R.drawable.nav_control) { ControlFragment() },
        Tab("Camera", R.drawable.nav_camera) { CameraFragment() },
        Tab("AMS", R.drawable.nav_ams) { AmsFragment() },
        Tab("Scan", R.drawable.nav_scan) { ScanFragment() },
        Tab("Spools", R.drawable.nav_spools) { SpoolsFragment() },
        Tab("Queue", R.drawable.nav_queue) { QueueFragment() },
        Tab("History", R.drawable.nav_history) { HistoryFragment() },
        Tab("Stats", R.drawable.nav_stats) { StatsFragment() },
        Tab("Settings", R.drawable.nav_settings) { SettingsFragment() }
    )

    /** The last tab of each group; a hairline goes under it. */
    private val groupEnds = setOf(2, 5, 8)

    private val railButtons = ArrayList<LinearLayout>()
    private var current = -1
    private var nfc: NfcAdapter? = null
    private lateinit var statusLine: TextView
    private lateinit var statusStrip: LinearLayout
    private lateinit var screenTitle: TextView
    private lateinit var screenAction: TextView

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
            // Read back from disk at startup, and the first poll is still out.
            Repo.restored.value && age != null ->
                "Connecting — last update ${ago(age)}" to Ui.dimColor(this)
            age != null && age > Repo.staleAfterMs() ->
                "Not live — last update ${ago(age)}" to Ui.warn(this)
            notice != null ->
                notice.message to (if (notice.failed) Ui.bad(this) else Ui.dimColor(this))
            else -> "" to Ui.dimColor(this)
        }

        statusLine.text = message
        statusLine.setTextColor(colour)
        statusStrip.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        statusStrip.isClickable = Repo.authExpired.value
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
        // A sticker write is waiting for this tag: write it, and do not scan it.
        if (StickerWrite.armed) {
            lifecycleScope.launch { StickerWrite.handle(tag, applicationContext) }
            return
        }
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
        right.addView(buildTopBar(), Ui.wide(this))
        right.addView(Ui.divider(this))

        val frame = FrameLayout(this)
        frame.id = R.id.content_frame
        right.addView(frame, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(right, Ui.lp(this, 0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    /**
     * One bar across the top of every screen: where you are on the left, the
     * screen's own action on the right.
     *
     * Each screen used to print its own name in large type, a hand's width from
     * the highlighted tab already saying it, and hang its Reload button off the
     * end of that. Hoisting both up here gives every screen its first line back
     * — worth having on a landscape phone with 360 dp of height — and puts
     * Reload in the same place on all of them.
     */
    private fun buildTopBar(): View {
        val bar = Ui.col(this)

        val line = Ui.row(this)
        val px = Ui.dp(this, Ui.M)
        line.setPadding(px, 0, Ui.dp(this, Ui.S), 0)
        screenTitle = Ui.title(this, "")
        screenTitle.setTextColor(Ui.dimColor(this))
        line.addView(screenTitle)
        Ui.push(this, line)
        screenAction = Ui.quiet(this, "") {}
        screenAction.visibility = View.GONE
        line.addView(screenAction)
        // A fixed height, so the bar does not jump a few pixels between a
        // screen that has an action in it and one that does not.
        bar.addView(line, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, 40))

        // Only ever on screen when something is wrong or has just happened, so
        // it is drawn as its own strip rather than as a stray line of red text.
        statusStrip = Ui.row(this)
        statusStrip.background = Ui.rounded(Ui.insetColor(this), 9, this)
        val sp = Ui.dp(this, 10)
        statusStrip.setPadding(sp, Ui.dp(this, 7), sp, Ui.dp(this, 7))
        statusLine = Ui.dim(this, "")
        statusLine.maxLines = 2
        statusStrip.addView(statusLine)
        statusStrip.visibility = View.GONE
        statusStrip.setOnClickListener { if (Repo.authExpired.value) askSignIn() }
        bar.addView(
            statusStrip,
            Ui.wide(this).also {
                it.setMargins(px, 0, px, Ui.dp(this, Ui.S))
            }
        )
        return bar
    }

    /** Lets a screen put its one action — Reload, Reconnect — in the top bar. */
    fun setScreenAction(label: String?, onClick: (() -> Unit)?) {
        if (label == null || onClick == null) {
            screenAction.visibility = View.GONE
            screenAction.setOnClickListener(null)
            return
        }
        screenAction.text = label
        screenAction.visibility = View.VISIBLE
        screenAction.setOnClickListener { onClick() }
    }

    private fun buildRail(): View {
        val rail = Ui.col(this)
        rail.setBackgroundColor(Ui.color(this, R.color.bg_rail))
        val p = Ui.dp(this, 6)
        rail.setPadding(p, p, p, p)

        // Ten tabs have to fit a landscape phone's height without scrolling:
        // one that scrolls with nothing to say so hides Settings off the
        // bottom, and he taps down the list looking for a tab that is there.
        // That is the whole budget, and it is why each row is as short as it
        // is — the icon is what makes a short row easy to aim at, because the
        // shape is recognised before the word is read.
        tabs.forEachIndexed { index, tab ->
            val item = Ui.row(this)
            item.isClickable = true

            // A 3 dp edge marks the tab you are on. A filled pill behind the
            // label was the loudest thing on the screen and it never changes.
            val mark = View(this)
            mark.layoutParams = Ui.lp(this, 3, 20)
            item.addView(mark)

            val icon = ImageView(this)
            icon.setImageResource(tab.icon)
            item.addView(
                icon,
                Ui.lp(this, 20, 20).also { it.setMargins(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0) }
            )

            val label = Ui.body(this, tab.label)
            label.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7))
            item.addView(label)

            rail.addView(item, Ui.wide(this))
            railButtons.add(item)

            if (index in groupEnds) {
                rail.addView(
                    Ui.divider(this),
                    Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        .also { it.setMargins(Ui.dp(this, 10), Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3)) }
                )
            }
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
        railButtons.forEachIndexed { i, item ->
            val on = i == index
            val mark = item.getChildAt(0)
            val icon = item.getChildAt(1) as ImageView
            val label = item.getChildAt(2) as TextView
            mark.setBackgroundColor(if (on) Ui.accent(this) else android.graphics.Color.TRANSPARENT)
            icon.setColorFilter(if (on) Ui.textColor(this) else Ui.faintColor(this))
            label.setTextColor(if (on) Ui.textColor(this) else Ui.dimColor(this))
            // The row you are on is lifted off the rail as well as marked, so
            // it reads at a glance from across the room rather than up close.
            item.background =
                if (on) Ui.rounded(Ui.color(this, R.color.card_alt), 8, this)
                else Ui.pressable(
                    this, Ui.rounded(android.graphics.Color.TRANSPARENT, 8, this),
                    Ui.color(this, R.color.pressed), 8
                )
        }
        screenTitle.text = tabs[index].label
        setScreenAction(null, null)
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_frame, tabs[index].make())
            .commitAllowingStateLoss()
    }

    companion object {
        private const val KEY_TAB = "tab"
        private const val RAIL_WIDTH_DP = 106
        const val CONTROL_TAB = 1
        const val SCAN_TAB = 4

        /** How long a command's outcome stays on the status line. */
        private const val NOTICE_MS = 30_000L
    }
}
