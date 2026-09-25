package io.github.krzkawa.bambuddyaio.shots

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Taps every row of the rail the way a finger does, through the activity's own
 * touch dispatch, and checks the screen changed.
 *
 * [Shots] switches tabs by calling showTab directly, which is why a rail that
 * could not be tapped at all went unnoticed from 2026-09-22 until krzys found
 * it on his phone.
 *
 * `./gradlew :app:testDebugUnitTest -Pshots --tests "*RailCheck*" --rerun-tasks`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-xhdpi")
class RailCheck {

    @Test
    fun everyRailRowOpensItsScreen() {
        val app = RuntimeEnvironment.getApplication()
        Repo.init(app)
        Repo.prefs.serverUrl = "http://127.0.0.1:9"
        Repo.prefs.apiKey = "bb_demo"

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        Repo.stop()
        val root = activity.window.decorView
        val w = root.resources.displayMetrics.widthPixels
        val h = root.resources.displayMetrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)

        val labels = listOf(
            "Printers", "Control", "Camera", "AMS", "Scan",
            "Spools", "Queue", "History", "Stats", "Settings"
        )
        // Backwards, so the first tap is not on the tab already showing.
        labels.reversed().forEach { label ->
            val row = findRow(root, label)
            tap(activity, row)
            shadowOf(Looper.getMainLooper()).idle()
            Repo.stop()
            assertEquals("Tapping $label", label, screenTitle(activity))
        }
    }

    /** The rail row holding a label, found by its text so tab order can change. */
    private fun findRow(root: View, label: String): View {
        fun walk(v: View): TextView? {
            if (v is TextView && v.text.toString() == label && v.parent is View &&
                (v.parent as View).isClickable
            ) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            return null
        }
        return walk(root)?.parent as? View ?: throw AssertionError("No rail row for $label")
    }

    /** Down and up at the row's centre, in window coordinates, through the activity. */
    private fun tap(activity: MainActivity, view: View) {
        val at = IntArray(2)
        view.getLocationInWindow(at)
        val x = at[0] + view.width / 2f
        val y = at[1] + view.height / 2f
        val t = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val ev = MotionEvent.obtain(t, t + 10, action, x, y, 0)
            activity.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    private fun screenTitle(activity: MainActivity): String {
        val f = MainActivity::class.java.getDeclaredField("screenTitle")
        f.isAccessible = true
        return (f.get(activity) as TextView).text.toString()
    }
}
