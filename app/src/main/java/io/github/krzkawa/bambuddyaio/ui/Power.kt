package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.krzkawa.bambuddyaio.net.Api
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.bool
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.str
import io.github.krzkawa.bambuddyaio.util.temp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The smart plug each printer sits on: whether it is on, what it draws, and
 * the switch.
 *
 * A printer that is off at the plug is exactly the one Bambuddy cannot reach,
 * so this is kept apart from the status poll: the card that says "not
 * connected" is the card that needs the "Turn on" button.
 */
object Power {

    data class Plug(
        val id: Int,
        val name: String,
        /** False for an MQTT plug, which Bambuddy only listens to, and for a Home Assistant script. */
        val switchable: Boolean,
        /** Null when the plug did not say. */
        val on: Boolean?,
        val reachable: Boolean,
        val watts: Double?,
        val todayKwh: Double?
    )

    /** Printers that have a plug, by printer id. A printer with none is simply absent. */
    private val _plugs = MutableStateFlow<Map<Int, Plug>>(emptyMap())
    val plugs: StateFlow<Map<Int, Plug>> = _plugs.asStateFlow()

    /** Which plug feeds which printer changes when someone edits Bambuddy, not every poll. */
    private var assigned: Map<Int, JSONObject?> = emptyMap()
    private var assignedAt = 0L
    private var loadedAt = 0L

    /** Whether Bambuddy can switch this plug at all, read from how it is set up. */
    fun canSwitch(plug: JSONObject): Boolean {
        val type = plug.str("plug_type") ?: "tasmota"
        if (type == "mqtt") return false
        val entity = plug.str("ha_entity_id").orEmpty()
        return !(type == "homeassistant" && entity.startsWith("script."))
    }

    /**
     * One plug, from its configuration and — when the device answered — its
     * live status. Without a live answer the server's last known state stands
     * in, marked unreachable.
     */
    fun parse(plug: JSONObject, status: JSONObject?): Plug {
        val energy = status?.optJSONObject("energy")
        val state = status?.str("state") ?: if (status == null) plug.str("last_state") else null
        return Plug(
            id = plug.optInt("id", -1),
            name = plug.str("name") ?: "Plug",
            switchable = canSwitch(plug),
            on = when (state?.uppercase()) {
                "ON" -> true
                "OFF" -> false
                else -> null
            },
            reachable = status?.let { if (it.has("reachable")) it.bool("reachable") else true } ?: false,
            watts = energy?.dbl("power"),
            todayKwh = energy?.dbl("today")
        )
    }

    /** "On · 142 W · 0.84 kWh today", or why there is nothing to say. */
    fun describe(plug: Plug): String {
        if (!plug.reachable && plug.on == null) return "Plug not answering"
        val parts = ArrayList<String>()
        parts.add(when (plug.on) {
            true -> "On"
            false -> "Off"
            null -> "Unknown"
        })
        if (plug.on == true) plug.watts?.let { parts.add("${Math.round(it)} W") }
        plug.todayKwh?.takeIf { it > 0 }?.let { parts.add(String.format(java.util.Locale.US, "%.2f kWh today", it)) }
        if (!plug.reachable) parts.add("not answering")
        return parts.joinToString(" · ")
    }

    /** A printer in these states would lose the job it is on. */
    fun busy(status: JSONObject?): Boolean =
        status?.str("state") in setOf("RUNNING", "PAUSE", "PREPARE", "SLICING")

    /**
     * Refreshes every printer's plug. Blocking, so call it off the main thread.
     *
     * Throttled, because two screens ask and a plug status is a round trip from
     * the server to the device on every call.
     */
    @Synchronized
    fun load(api: Api, printerIds: List<Int>, force: Boolean = false) {
        // Before the first poll there are no printers to ask about, and that
        // must not count as having asked.
        if (printerIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val known = assigned.keys.containsAll(printerIds)
        if (!force && known && now - loadedAt < MIN_GAP_MS) return
        loadedAt = now

        if (force || !known || now - assignedAt > ASSIGNMENT_MS) {
            val next = HashMap<Int, JSONObject?>()
            for (id in printerIds) {
                // A server without the smart plug routes, or a key without the
                // permission, has no plugs as far as this screen is concerned.
                next[id] = try { api.plugForPrinter(id) } catch (e: Exception) { null }
            }
            assigned = next
            assignedAt = now
        }

        val result = HashMap<Int, Plug>()
        for ((printerId, plug) in assigned) {
            if (plug == null || printerId !in printerIds) continue
            val plugId = plug.optInt("id", -1)
            if (plugId < 0) continue
            val status = try { api.plugStatus(plugId) } catch (e: Exception) { null }
            result[printerId] = parse(plug, status)
        }
        _plugs.value = result
    }

    /** What the plug looks like for a rebuild signature. */
    fun signature(printerId: Int): String =
        _plugs.value[printerId]?.let { "${it.on}${it.reachable}${it.switchable}" }.orEmpty()

    // ------------------------------------------------------------------- views

    /**
     * The compact form for a printer card's top line, used while the plug is
     * on and answering — the usual case, and not worth a row of its own on a
     * card that has to share the screen with another. Tapping it asks whether
     * to turn the printer off. Null in every other case, where [row] is used.
     */
    fun chip(ctx: Context, printerId: Int, status: JSONObject?): TextView? {
        val plug = _plugs.value[printerId] ?: return null
        if (plug.on != true || !plug.reachable) return null
        val chip = Ui.tiny(ctx, listOfNotNull("Plug on", plug.watts?.let { "${Math.round(it)} W" }).joinToString(" · "))
        chip.setPadding(Ui.dp(ctx, Ui.XS), Ui.dp(ctx, Ui.XS), Ui.dp(ctx, Ui.XS), Ui.dp(ctx, Ui.XS))
        if (plug.switchable) {
            chip.isClickable = true
            chip.setOnClickListener { confirmOff(ctx, printerId, plug, status) }
        }
        return chip
    }

    /**
     * The plug's line on a card: its state, its draw, and the one thing to do
     * about it. Null when this printer has no plug.
     */
    fun row(ctx: Context, printerId: Int, status: JSONObject?): LinearLayout? {
        val plug = _plugs.value[printerId] ?: return null
        val line = Ui.row(ctx)
        line.addView(Ui.tiny(ctx, "Power"))
        Ui.gap(ctx, line, Ui.S)
        val words = Ui.dim(ctx, describe(plug))
        words.maxLines = 1
        if (plug.on == false) words.setTextColor(Ui.textColor(ctx))
        line.addView(words, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (plug.switchable && plug.reachable) {
            when (plug.on) {
                // Off is the one that can cost a print, so it is the quiet one
                // and it always asks.
                true -> line.addView(Ui.quiet(ctx, "Turn off") { confirmOff(ctx, printerId, plug, status) })
                false -> line.addView(Ui.button(ctx, "Turn on", primary = true) { switch(ctx, printerId, plug, true) })
                null -> {
                    line.addView(Ui.quiet(ctx, "Turn on") { switch(ctx, printerId, plug, true) })
                    line.addView(Ui.quiet(ctx, "Turn off") { confirmOff(ctx, printerId, plug, status) })
                }
            }
        }
        return line
    }

    /**
     * Asks before cutting power, and says what it would cost.
     *
     * The phone lives next to the printer; a sleeve brushing the screen must
     * not be able to end a twelve-hour print.
     */
    fun confirmOff(ctx: Context, printerId: Int, plug: Plug, drawnWith: JSONObject?) {
        // The row may have been drawn a while ago; ask about the print as it is now.
        val status = Repo.statuses.value[printerId] ?: drawnWith
        val name = Repo.printerName(printerId)
        val busy = busy(status)
        val message = StringBuilder()
        if (busy) {
            val job = status?.str("subtask_name") ?: status?.str("gcode_file")
            val progress = status?.dbl("progress")?.toInt()
            message.append("$name is ${Ui.stateWord(status?.str("state")).lowercase()}")
            if (job != null) message.append(" $job")
            if (progress != null && progress > 0) message.append(" ($progress%)")
            message.append(". Cutting power ends the print, and it cannot be resumed.")
        } else {
            message.append("$name loses power at ${plug.name}.")
        }
        val nozzle = status?.temp("nozzle")
        if (nozzle != null && nozzle >= HOT_NOZZLE) {
            message.append("\n\nThe nozzle is at ${nozzle.toInt()}°. Its fan stops with the power, so the hot end cools better if you wait.")
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(if (busy) "Cut power mid-print?" else "Turn $name off?")
            .setMessage(message.toString())
            .setPositiveButton(if (busy) "Cut power" else "Turn off") { _, _ -> switch(ctx, printerId, plug, false) }
            .setNegativeButton(if (busy) "Keep printing" else "Cancel", null)
            .show()
    }

    private fun switch(ctx: Context, printerId: Int, plug: Plug, on: Boolean) {
        val label = if (on) "Power on" else "Power off"
        val ids = Repo.printers.value.map { it.optInt("id", -1) }.filter { it >= 0 }
        Repo.action(label, {
            Repo.api.plugControl(plug.id, if (on) "on" else "off")
            load(Repo.api, ids.ifEmpty { listOf(printerId) }, force = true)
        }) { Ui.toast(ctx.applicationContext, it) }
    }

    /** Degrees at which the hot end would rather keep its fan a while longer. */
    private const val HOT_NOZZLE = 60.0

    private const val MIN_GAP_MS = 10_000L
    private const val ASSIGNMENT_MS = 5 * 60_000L

    /** How often a visible screen asks. */
    const val POLL_MS = 20_000L
}
