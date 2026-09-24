package io.github.krzkawa.bambuddyaio.appliance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * The phone's own health, for a phone that never leaves its charger.
 *
 * Years on a charger is what swells an old phone's battery, and heat is the
 * warning sign. The phone coming off power is worth knowing too: on a shelf
 * next to a printer, that usually means the power went, printer included.
 */
object Battery {

    data class Reading(val percent: Int, val celsius: Double, val plugged: Boolean)

    /** Hot enough to say so. Phones throttle charging from about here. */
    const val HOT_C = 45.0

    /** Reads the sticky battery broadcast; null on a device that has none. */
    fun read(ctx: Context): Reading? {
        val intent: Intent = try {
            ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            null
        } ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        if (level < 0) return null
        return Reading(
            percent = level * 100 / scale,
            celsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        )
    }

    /** An event when the phone has just come off power. */
    fun events(prev: Reading?, now: Reading): List<Events.Event> =
        if (prev != null && prev.plugged && !now.plugged) {
            listOf(
                Events.Event(
                    Events.Kind.UNPLUGGED, -1,
                    "The phone is off its charger (${now.percent}%)",
                    "The phone is running on battery."
                )
            )
        } else {
            emptyList()
        }

    /** A standing warning for the status line, or null when all is well. */
    fun warning(now: Reading): String? = when {
        now.celsius >= HOT_C ->
            "Phone battery at ${now.celsius.toInt()}°C — let it cool off the charger"
        !now.plugged && now.percent <= 20 -> "Phone on battery, ${now.percent}% left"
        !now.plugged -> "Phone on battery (${now.percent}%)"
        else -> null
    }
}
