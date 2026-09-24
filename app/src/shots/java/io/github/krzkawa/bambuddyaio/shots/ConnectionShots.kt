package io.github.krzkawa.bambuddyaio.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.krzkawa.bambuddyaio.net.Finder
import io.github.krzkawa.bambuddyaio.net.Live
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.ui.MainActivity
import io.github.krzkawa.bambuddyaio.ui.SetupActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The connection lane's screens: Settings with live updates on, setup with
 * servers found on the wifi, and a cold start restored from disk while the
 * server is still unreachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionShots {

    private val out = File(System.getProperty("shots.dir") ?: "build/shots").also { it.mkdirs() }

    @Suppress("UNCHECKED_CAST")
    private fun <T> seed(field: String, value: T) {
        val f = Repo::class.java.getDeclaredField(field)
        f.isAccessible = true
        (f.get(Repo) as MutableStateFlow<T>).value = value
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun shoot() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Repo.init(app)
        // A port nothing listens on: every request fails at once.
        Repo.prefs.serverUrl = "http://127.0.0.1:9"
        Repo.prefs.apiKey = "bb_demo"
        Repo.prefs.liveUpdates = true

        // --- Setup, with two servers found on the wifi.
        val setup = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val list = SetupActivity::class.java.getDeclaredField("foundList").apply { isAccessible = true }
            .get(setup) as LinearLayout
        val row = SetupActivity::class.java.getDeclaredMethod("foundRow", Finder.Found::class.java)
            .apply { isAccessible = true }
        list.addView(row.invoke(setup, Finder.Found("http://192.168.1.20:8000", true, false)) as View)
        list.addView(row.invoke(setup, Finder.Found("http://192.168.1.31", false, false)) as View)
        val line = SetupActivity::class.java.getDeclaredField("findLine").apply { isAccessible = true }
            .get(setup) as android.widget.TextView
        line.text = "Found 2. Tap the one to use:"
        idle()
        capture(setup.window.decorView, File(out, "setup-found.png"))

        // --- A cold start restored from disk, server still down.
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        Repo.stop()
        val hoursAgo = System.currentTimeMillis() - 3 * 3600_000L - 12 * 60_000L
        seed("_printers", listOf(JSONObject().put("id", 1).put("name", "X1 Carbon").put("model", "X1C")))
        seed("_statuses", mapOf(1 to JSONObject().put("state", "RUNNING").put("connected", true)
            .put("progress", 64.0).put("subtask_name", "bracket_v3_plate.gcode.3mf")
            .put("temperatures", JSONObject().put("nozzle", 219.0).put("bed", 59.0))))
        seed("_fetchedAt", mapOf(1 to hoursAgo))
        seed("_updatedAt", hoursAgo)
        seed("_restored", true)
        seed("_error", null as String?)
        activity.showTab(0)
        idle()
        // The status line runs on its own one-second clock.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_100))
        capture(activity.window.decorView, File(out, "restored-connecting.png"))

        // --- Settings, live updates on and connected: the whole column, not just what fits.
        seed("_restored", false)
        seed("_updatedAt", System.currentTimeMillis())
        activity.showTab(9)
        idle()
        seed("_live", Live.State.LIVE)
        idle()
        captureTall(activity.window.decorView, File(out, "settings-live.png"))
        seed("_live", Live.State.RETRYING)
        idle()
        captureTall(activity.window.decorView, File(out, "settings-retrying.png"))
        Repo.prefs.liveUpdates = false
    }

    /** The scrolling column drawn at its full height. */
    private fun captureTall(root: View, file: File) {
        val scroller = findScroll(root) ?: return capture(root, file)
        val content = scroller.getChildAt(0)
        val w = scroller.width
        content.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bitmap = Bitmap.createBitmap(content.measuredWidth, content.measuredHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF000000.toInt())
        (scroller.background)?.let { it.setBounds(0, 0, bitmap.width, bitmap.height); it.draw(Canvas(bitmap)) }
        content.draw(Canvas(bitmap))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The screen's own scroller, which is the widest one; the rail scrolls too. */
    private fun findScroll(view: View): ScrollView? {
        val all = ArrayList<ScrollView>()
        fun walk(v: View) {
            if (v is ScrollView && v.getChildAt(0) != null) all.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return all.maxByOrNull { it.width }
    }

    private fun capture(view: View, file: File) {
        val w = view.resources.displayMetrics.widthPixels
        val h = view.resources.displayMetrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
