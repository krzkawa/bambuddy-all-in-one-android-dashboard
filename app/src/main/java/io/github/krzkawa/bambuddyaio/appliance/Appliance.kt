package io.github.krzkawa.bambuddyaio.appliance

import android.content.Context

/**
 * Settings for the phone as a device on a shelf: alerts, dimming, boot, battery.
 *
 * Kept in their own file rather than in [io.github.krzkawa.bambuddyaio.net.Prefs],
 * which holds the connection and is edited by every screen's work; nothing here
 * is a secret and nothing here needs the server.
 */
class Appliance(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("appliance", Context.MODE_PRIVATE)

    // --------------------------------------------------------------- alerts

    /** 0 silent, 1 chime, 2 chime and a spoken sentence. */
    var sound: Int
        get() = sp.getInt("sound", CHIME).coerceIn(SILENT, VOICE)
        set(v) = sp.edit().putInt("sound", v.coerceIn(SILENT, VOICE)).apply()

    /** 0 quiet, 1 normal, 2 loud — a gain on top of the phone's media volume. */
    var loudness: Int
        get() = sp.getInt("loudness", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("loudness", v.coerceIn(0, 2)).apply()

    fun alertOn(kind: Events.Kind): Boolean = sp.getBoolean("alert.${kind.key}", kind.onByDefault)

    fun setAlert(kind: Events.Kind, on: Boolean) = sp.edit().putBoolean("alert.${kind.key}", on).apply()

    // -------------------------------------------------------------- dimming

    /** Minutes without a touch before the screen dims; 0 never. */
    var dimAfterMinutes: Int
        get() = sp.getInt("dimAfter", 5).coerceAtLeast(0)
        set(v) = sp.edit().putInt("dimAfter", v.coerceAtLeast(0)).apply()

    /** Daytime dimming waits while a print is running, so progress reads from across the room. */
    var brightWhilePrinting: Boolean
        get() = sp.getBoolean("brightPrinting", true)
        set(v) = sp.edit().putBoolean("brightPrinting", v).apply()

    /** At night the screen dims shortly after any touch, printing or not. */
    var nightOn: Boolean
        get() = sp.getBoolean("nightOn", true)
        set(v) = sp.edit().putBoolean("nightOn", v).apply()

    var nightFrom: Int
        get() = sp.getInt("nightFrom", 23).coerceIn(0, 23)
        set(v) = sp.edit().putInt("nightFrom", v.coerceIn(0, 23)).apply()

    var nightTo: Int
        get() = sp.getInt("nightTo", 7).coerceIn(0, 23)
        set(v) = sp.edit().putInt("nightTo", v.coerceIn(0, 23)).apply()

    /** Index into [DIM_LEVELS]. */
    var dimLevel: Int
        get() = sp.getInt("dimLevel", 1).coerceIn(0, DIM_LEVELS.size - 1)
        set(v) = sp.edit().putInt("dimLevel", v.coerceIn(0, DIM_LEVELS.size - 1)).apply()

    // ---------------------------------------------------------------- phone

    /** Opens the dashboard when the phone boots, and shows it over the lock screen. */
    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", true)
        set(v) = sp.edit().putBoolean("startOnBoot", v).apply()

    /** Warns when the battery runs hot or the phone comes off its charger. */
    var batteryWatch: Boolean
        get() = sp.getBoolean("batteryWatch", true)
        set(v) = sp.edit().putBoolean("batteryWatch", v).apply()

    fun night(): Night.Config = Night.Config(
        dimAfterMs = dimAfterMinutes * 60_000L,
        brightWhilePrinting = brightWhilePrinting,
        nightOn = nightOn,
        nightFrom = nightFrom,
        nightTo = nightTo
    )

    companion object {
        const val SILENT = 0
        const val CHIME = 1
        const val VOICE = 2

        /** Window brightness when dimmed. The lowest still shows the rail and a progress figure. */
        val DIM_LEVELS = floatArrayOf(0.01f, 0.04f, 0.12f)
        val DIM_NAMES = listOf("Dark", "Low", "Soft")

        /** Gain applied to the chime for each loudness step. */
        val GAINS = floatArrayOf(0.3f, 0.65f, 1.0f)

        @Volatile private var instance: Appliance? = null

        fun of(ctx: Context): Appliance =
            instance ?: synchronized(this) { instance ?: Appliance(ctx).also { instance = it } }
    }
}
