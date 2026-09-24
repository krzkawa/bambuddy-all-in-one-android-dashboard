package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.appliance.Alerts
import io.github.krzkawa.bambuddyaio.appliance.Appliance
import io.github.krzkawa.bambuddyaio.appliance.Battery
import io.github.krzkawa.bambuddyaio.appliance.Events

/**
 * Settings for the phone itself: alerts, dimming, starting on boot, battery.
 *
 * Its own file so the Settings screen only gains one call, and the cards can
 * change without touching the connection settings above them.
 */
fun addApplianceCards(ctx: Context, into: LinearLayout, rerender: () -> Unit) {
    val a = Appliance.of(ctx)
    into.addView(alertsCard(ctx, a, rerender))
    into.addView(Ui.space(ctx, Ui.M))
    into.addView(dimmingCard(ctx, a, rerender))
    into.addView(Ui.space(ctx, Ui.M))
    into.addView(phoneCard(ctx, a, rerender))
    into.addView(Ui.space(ctx, Ui.M))
}

private fun alertsCard(ctx: Context, a: Appliance, rerender: () -> Unit): LinearLayout {
    val card = Ui.card(ctx)
    card.addView(Ui.heading(ctx, "Alerts"))
    card.addView(
        setting(
            ctx, "Sound", "The phone's volume keys set how loud.",
            Ui.segmented(ctx, listOf("Off", "Chime", "Chime + voice"), a.sound) {
                a.sound = it
                rerender()
            }
        ),
        Ui.wide(ctx)
    )
    if (a.sound != Appliance.SILENT) {
        card.addView(Ui.divider(ctx))
        val controls = Ui.row(ctx)
        controls.addView(Ui.segmented(ctx, listOf("Quiet", "Normal", "Loud"), a.loudness) {
            a.loudness = it
            rerender()
        })
        Ui.gap(ctx, controls, Ui.S)
        controls.addView(Ui.quiet(ctx, "Play a test") { Alerts.test(ctx) })
        card.addView(setting(ctx, "Loudness", null, controls), Ui.wide(ctx))
        if (Alerts.mediaMuted(ctx)) {
            val muted = Ui.dim(ctx, "The phone's media volume is all the way down, so alerts make no sound.")
            muted.setTextColor(Ui.warn(ctx))
            card.addView(muted)
        }
    }
    card.addView(Ui.divider(ctx))
    card.addView(Ui.space(ctx, Ui.S))
    card.addView(Ui.body(ctx, "Alert me for"))
    card.addView(Ui.space(ctx, Ui.S))
    val chips = Ui.row(ctx)
    val kinds = Events.Kind.values().filter { it != Events.Kind.UNPLUGGED }
    kinds.forEachIndexed { i, kind ->
        if (i > 0) Ui.gap(ctx, chips, 6)
        val on = a.alertOn(kind)
        chips.addView(Ui.segmented(ctx, listOf(kind.label), if (on) 0 else -1) {
            a.setAlert(kind, !on)
            rerender()
        })
    }
    // Scrolled to rather than wrapped on a narrower screen.
    val scroller = HorizontalScrollView(ctx)
    scroller.isHorizontalScrollBarEnabled = false
    scroller.addView(chips)
    card.addView(scroller, Ui.wide(ctx))
    card.addView(Ui.space(ctx, Ui.S))
    card.addView(Ui.tiny(ctx, "Only while the app is open. A dimmed screen lights up for an alert."))
    return card
}

private fun dimmingCard(ctx: Context, a: Appliance, rerender: () -> Unit): LinearLayout {
    val card = Ui.card(ctx)
    card.addView(Ui.heading(ctx, "Dimming"))

    val waits = listOf(0, 1, 5, 15)
    card.addView(
        setting(
            ctx, "Dim when untouched for", "A touch brings it back and does nothing else.",
            Ui.segmented(
                ctx, waits.map { if (it == 0) "Never" else "$it min" },
                waits.indexOf(a.dimAfterMinutes).coerceAtLeast(0)
            ) {
                a.dimAfterMinutes = waits[it]
                rerender()
            }
        ),
        Ui.wide(ctx)
    )
    if (a.dimAfterMinutes > 0) {
        card.addView(Ui.divider(ctx))
        card.addView(
            setting(
                ctx, "Stay bright while printing", "So the progress reads from across the room.",
                onOff(ctx, a.brightWhilePrinting) {
                    a.brightWhilePrinting = it
                    rerender()
                }
            ),
            Ui.wide(ctx)
        )
    }
    card.addView(Ui.divider(ctx))

    val hours = Ui.row(ctx)
    hours.addView(Ui.quiet(ctx, "${hh(a.nightFrom)}–${hh(a.nightTo)}") {
        pickHours(ctx, a, rerender)
    })
    Ui.gap(ctx, hours, Ui.S)
    hours.addView(onOff(ctx, a.nightOn) {
        a.nightOn = it
        rerender()
    })
    card.addView(
        setting(ctx, "At night", "Dims half a minute after a touch, printing or not.", hours),
        Ui.wide(ctx)
    )
    card.addView(Ui.divider(ctx))
    card.addView(
        setting(
            ctx, "How dim", null,
            Ui.segmented(ctx, Appliance.DIM_NAMES, a.dimLevel) {
                a.dimLevel = it
                rerender()
            }
        ),
        Ui.wide(ctx)
    )
    return card
}

private fun phoneCard(ctx: Context, a: Appliance, rerender: () -> Unit): LinearLayout {
    val card = Ui.card(ctx)
    card.addView(Ui.heading(ctx, "This phone"))
    card.addView(
        setting(
            ctx, "Open when the phone starts",
            "After a power cut or an update, and over the lock screen.",
            onOff(ctx, a.startOnBoot) {
                a.startOnBoot = it
                rerender()
            }
        ),
        Ui.wide(ctx)
    )
    card.addView(Ui.divider(ctx))
    val reading = Battery.read(ctx)
    val now = reading?.let {
        "Now ${it.percent}%, ${it.celsius.toInt()}°C, ${if (it.plugged) "charging" else "on battery"}."
    }
    card.addView(
        setting(
            ctx, "Watch the battery",
            listOfNotNull("Says so when it runs hot or comes off the charger.", now).joinToString(" "),
            onOff(ctx, a.batteryWatch) {
                a.batteryWatch = it
                rerender()
            }
        ),
        Ui.wide(ctx)
    )
    return card
}

/** A setting's name and explanation on the left, its control on the right. */
private fun setting(ctx: Context, label: String, detail: String?, control: View): LinearLayout {
    val row = Ui.row(ctx)
    row.setPadding(0, Ui.dp(ctx, Ui.S), 0, Ui.dp(ctx, Ui.S))
    val words = Ui.col(ctx)
    words.addView(Ui.body(ctx, label))
    if (detail != null) words.addView(Ui.tiny(ctx, detail))
    row.addView(words, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    Ui.gap(ctx, row, Ui.M)
    row.addView(control)
    return row
}

private fun onOff(ctx: Context, on: Boolean, set: (Boolean) -> Unit): LinearLayout =
    Ui.segmented(ctx, listOf("Off", "On"), if (on) 1 else 0) { index ->
        if ((index == 1) != on) set(index == 1)
    }

private fun hh(hour: Int) = "%02d:00".format(hour)

private fun pickHours(ctx: Context, a: Appliance, rerender: () -> Unit) {
    fun picker(value: Int) = NumberPicker(ctx).also { p ->
        p.minValue = 0
        p.maxValue = 23
        p.value = value
        p.displayedValues = Array(24) { hh(it) }
        p.wrapSelectorWheel = true
    }
    val from = picker(a.nightFrom)
    val to = picker(a.nightTo)
    val row = Ui.row(ctx)
    row.setPadding(Ui.dp(ctx, Ui.L), Ui.dp(ctx, Ui.S), Ui.dp(ctx, Ui.L), 0)
    row.addView(from, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(Ui.body(ctx, "to"))
    row.addView(to, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    AlertDialog.Builder(ctx)
        .setTitle("Night hours")
        .setView(row)
        .setPositiveButton("Set") { _, _ ->
            a.nightFrom = from.value
            a.nightTo = to.value
            rerender()
        }
        .setNegativeButton("Cancel", null)
        .show()
}
