package io.github.krzkawa.bambuddyaio.appliance

import android.app.Activity
import android.view.MotionEvent
import android.view.WindowManager

/**
 * Turns the screen down when nobody is looking at it.
 *
 * A phone that is always on at full brightness is a lamp all night and wears
 * its panel out showing the same rail for years. This drops the window's
 * brightness to a floor after a spell without a touch — sooner at night — and
 * brings it straight back on a touch or an alert.
 *
 * The touch that wakes a dimmed screen is swallowed, because at that
 * brightness he cannot see what is under his finger.
 */
class Night(private val activity: Activity) {

    data class Config(
        val dimAfterMs: Long,
        val brightWhilePrinting: Boolean,
        val nightOn: Boolean,
        val nightFrom: Int,
        val nightTo: Int
    )

    private var lastActivity = System.currentTimeMillis()
    private var dimmed = false
    private var swallowing = false

    /** Called on every touch before the screen sees it. True means "eat this one". */
    fun onTouch(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            lastActivity = System.currentTimeMillis()
            if (dimmed) {
                swallowing = true
                apply(false)
            }
        }
        val eat = swallowing
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            swallowing = false
        }
        return eat
    }

    /** An alert wants him to look: full brightness, and the idle clock restarts. */
    fun wake() {
        lastActivity = System.currentTimeMillis()
        if (dimmed) apply(false)
    }

    /** Re-evaluates; called on a clock by the activity. */
    fun tick(config: Config, level: Float, hour: Int, printing: Boolean) {
        val dim = shouldDim(config, System.currentTimeMillis() - lastActivity, hour, printing)
        if (dim != dimmed) apply(dim, level)
    }

    val isDimmed: Boolean get() = dimmed

    private fun apply(dim: Boolean, level: Float = 0f) {
        dimmed = dim
        val attrs = activity.window.attributes
        attrs.screenBrightness =
            if (dim) level else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = attrs
    }

    companion object {
        /** At night the screen goes down this soon after the last touch. */
        const val NIGHT_AFTER_MS = 30_000L

        /** True when [hour] falls in the window that starts at [from] and ends at [to], across midnight too. */
        fun inHours(hour: Int, from: Int, to: Int): Boolean = when {
            from == to -> false
            from < to -> hour in from until to
            else -> hour >= from || hour < to
        }

        fun shouldDim(config: Config, idleMs: Long, hour: Int, printing: Boolean): Boolean {
            if (config.nightOn && inHours(hour, config.nightFrom, config.nightTo)) {
                return idleMs > NIGHT_AFTER_MS
            }
            if (config.dimAfterMs <= 0L) return false
            if (printing && config.brightWhilePrinting) return false
            return idleMs > config.dimAfterMs
        }
    }
}
