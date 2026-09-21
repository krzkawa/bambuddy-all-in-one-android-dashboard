package io.github.krzkawa.bambuddyaio.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.krzkawa.bambuddyaio.R
import io.github.krzkawa.bambuddyaio.nfc.BambuTag
import io.github.krzkawa.bambuddyaio.net.Repo
import kotlinx.coroutines.Dispatchers
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
        setContentView(buildLayout())
        nfc = NfcAdapter.getDefaultAdapter(this)

        showTab(if (savedInstanceState != null) savedInstanceState.getInt(KEY_TAB, 0) else 0)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Repo.error.collect { message ->
                    statusLine.text = message ?: ""
                    statusLine.visibility = if (message == null) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, current)
    }

    override fun onResume() {
        super.onResume()
        applyKeepAwake()
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

        root.addView(buildRail(), Ui.lp(this, 104, ViewGroup.LayoutParams.MATCH_PARENT))

        val right = Ui.col(this)
        statusLine = Ui.tiny(this, "")
        statusLine.setTextColor(Ui.bad(this))
        statusLine.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), 0)
        statusLine.visibility = View.GONE
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

        tabs.forEachIndexed { index, tab ->
            val item = Ui.body(this, tab.label)
            item.gravity = Gravity.CENTER_VERTICAL
            item.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 8), Ui.dp(this, 9))
            item.isClickable = true
            item.setOnClickListener { showTab(index) }
            val lp = Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = Ui.dp(this, 2)
            rail.addView(item, lp)
            railButtons.add(item)
        }

        val scroller = ScrollView(this)
        scroller.setBackgroundColor(Ui.color(this, R.color.bg_rail))
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
        const val SCAN_TAB = 3
    }
}
