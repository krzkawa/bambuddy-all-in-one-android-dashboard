package io.github.krzkawa.bambuddyaio.appliance

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.ui.Assign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

/**
 * Everything the phone does for itself while it sits beside the printer:
 * sounds and speaks when a print changes, dims when nobody is looking, keeps
 * an eye on its own battery.
 *
 * The state that must outlive the activity — what each printer looked like
 * last time — lives here, so turning a setting on (which recreates the
 * activity) does not make every printer look new and set everything off.
 */
object Alerts {

    private var last: Map<Int, JSONObject> = emptyMap()
    private var lastBattery: Battery.Reading? = null
    private var night: Night? = null
    private var lastBatteryWarning: String? = null

    /** Wires the activity up. Call once from `onCreate`. */
    fun attach(activity: AppCompatActivity): Night {
        val settings = Appliance.of(activity)
        val dimmer = Night(activity)
        night = dimmer

        // The volume keys then set how loud alerts are, rather than the ringer.
        activity.volumeControlStream = AudioManager.STREAM_MUSIC
        if (settings.startOnBoot) showOverLockScreen(activity)
        if (settings.sound == Appliance.VOICE) Voice.warmUp(activity)

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Repo.statuses.collect { seen(activity, it) } }
                var beat = 0
                while (true) {
                    dim(activity, dimmer, settings)
                    if (beat++ % BATTERY_EVERY == 0) battery(activity, settings)
                    delay(TICK_MS)
                }
            }
        }
        return dimmer
    }

    /** What a touch does first: wakes a dimmed screen, and is eaten if it did. */
    fun onTouch(ev: MotionEvent): Boolean = night?.onTouch(ev) ?: false

    private fun seen(ctx: Context, statuses: Map<Int, JSONObject>) {
        // A status restored from disk at startup can be hours old. Comparing
        // the first live poll with it would announce a print that finished
        // overnight as if it had just happened, so it is never the baseline.
        if (Repo.restored.value) {
            last = emptyMap()
            return
        }
        val before = last
        last = statuses
        if (before.isEmpty()) return
        val events = statuses.flatMap { (id, status) ->
            Events.between(id, Repo.printerName(id), before[id], status) { tray ->
                Assign.nameGlobalTray(id, tray)
            }
        }
        announce(ctx, events)
    }

    /** Plays, says and shows [events], leaving out the kinds he switched off. */
    fun announce(ctx: Context, events: List<Events.Event>, force: Boolean = false) {
        val settings = Appliance.of(ctx)
        val wanted = if (force) events else events.filter { settings.alertOn(it.kind) }
        if (wanted.isEmpty()) return
        val tone = Events.toneOf(wanted) ?: return

        night?.wake()
        Repo.notice(wanted.joinToString(" · ") { it.line }, failed = tone == Events.Tone.BAD)

        if (settings.sound == Appliance.SILENT) return
        Chime.play(tone, Appliance.GAINS[settings.loudness])
        if (settings.sound == Appliance.VOICE) {
            val after = Chime.lengthMs(tone) + 250L
            wanted.forEach { Voice.speak(ctx, it.speech, after) }
        }
    }

    /** A finished print as it will sound, from the Settings screen. */
    fun test(ctx: Context) {
        announce(
            ctx,
            listOf(
                Events.Event(
                    Events.Kind.FINISHED, -1, "This is what a finished print sounds like",
                    "This is what a finished print sounds like."
                )
            ),
            force = true
        )
    }

    /** True when the phone's media volume is all the way down, so alerts would be silent. */
    fun mediaMuted(ctx: Context): Boolean {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }

    private fun dim(activity: AppCompatActivity, dimmer: Night, settings: Appliance) {
        val printing = Repo.statuses.value.values.any {
            it.optString("state") in setOf("RUNNING", "PAUSE", "PREPARE")
        }
        dimmer.tick(
            settings.night(),
            Appliance.DIM_LEVELS[settings.dimLevel],
            Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            printing
        )
    }

    private fun battery(ctx: Context, settings: Appliance) {
        if (!settings.batteryWatch) return
        val now = Battery.read(ctx) ?: return
        val events = Battery.events(lastBattery, now)
        lastBattery = now
        announce(ctx, events)

        // A standing warning is re-posted while it holds, but never over the
        // top of something that just happened on a printer.
        val warning = Battery.warning(now) ?: run { lastBatteryWarning = null; return }
        val current = Repo.notice.value
        val fresh = current != null && System.currentTimeMillis() - current.at < 25_000L
        if (fresh && current?.message != lastBatteryWarning) return
        lastBatteryWarning = warning
        Repo.notice(warning, failed = now.celsius >= Battery.HOT_C)
    }

    /**
     * After a power cut the phone boots to its lock screen. Showing over it
     * means the dashboard is what comes back, without a swipe. A secure lock
     * (PIN, pattern) still holds everywhere else.
     */
    @Suppress("DEPRECATION")
    private fun showOverLockScreen(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
        } else {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    private const val TICK_MS = 2_000L

    /** The battery is read every tenth tick, twenty seconds apart. */
    private const val BATTERY_EVERY = 10
}
