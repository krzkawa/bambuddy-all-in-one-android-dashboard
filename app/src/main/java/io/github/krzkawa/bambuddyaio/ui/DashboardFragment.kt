package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.krzkawa.bambuddyaio.R
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.ago
import io.github.krzkawa.bambuddyaio.util.bool
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import io.github.krzkawa.bambuddyaio.util.temp
import org.json.JSONObject

/** Every printer at a glance: what it is doing, how far in, and how hot. */
class DashboardFragment : BaseFragment() {

    private lateinit var list: LinearLayout
    private var signature = ""

    /** Each card's "not live" line, so its age can tick without a rebuild. */
    private val ageLines = HashMap<Int, Pair<LinearLayout, TextView>>()

    override fun build(ctx: Context) {
        content.addView(header(ctx, "Printers", "Tap a printer to control it"))
        list = Ui.col(ctx)
        content.addView(list, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        observe(Repo.statuses) { render() }
        observe(Repo.selected) { render() }
        observe(ticker(5_000)) { refreshAges() }
        render()
    }

    private fun render() {
        val ctx = context ?: return
        val statuses = Repo.statuses.value
        val printers = Repo.printers.value

        // Rebuilding a card every four seconds would fight the user's finger, so
        // only redraw when something actually moved. Ageing is deliberately not
        // in here: refreshAges keeps that current without a rebuild.
        val next = buildString {
            append(Repo.selected.value)
            for (p in printers) {
                val s = statuses[p.optInt("id")]
                append(p.optInt("id")).append(s?.str("state")).append(s?.dbl("progress"))
                    .append(s?.int("layer_num")).append(s?.temp("nozzle")?.toInt())
                    .append(s?.temp("bed")?.toInt()).append(s?.bool("connected"))
                    .append(s?.bool("awaiting_plate_clear"))
                    .append(s?.int("expected_tray")).append(s?.int("previous_tray"))
                    .append(Hms.faults(s).firstOrNull()?.description)
                    .append(s?.objects("hms_errors")?.size)
            }
        }
        if (next == signature && list.childCount > 0) return
        signature = next

        list.removeAllViews()
        ageLines.clear()

        if (printers.isEmpty()) {
            list.addView(Ui.dim(ctx, Repo.error.value ?: "No printers on this Bambuddy server yet."))
            return
        }

        for (printer in printers) {
            val id = printer.optInt("id", -1)
            if (id < 0) continue
            list.addView(card(ctx, id, printer, statuses[id]))
            list.addView(Ui.space(ctx, 8))
        }
        refreshAges()
    }

    /**
     * Dims a card whose numbers have stopped arriving and dates them.
     *
     * Kept apart from [render] because the poll emits nothing while the wifi is
     * down, which is exactly when these lines need to keep counting.
     */
    private fun refreshAges() {
        val now = System.currentTimeMillis()
        val limit = Repo.staleAfterMs()
        for ((id, views) in ageLines) {
            val (card, line) = views
            val age = Repo.ageMs(id, now)
            val stale = age != null && age > limit
            card.alpha = if (stale) 0.55f else 1f
            line.visibility = if (stale) View.VISIBLE else View.GONE
            if (stale) line.text = "Not live — last update ${ago(age!!)}"
        }
    }

    private fun card(ctx: Context, id: Int, printer: JSONObject, status: JSONObject?): LinearLayout {
        val card = Ui.card(ctx)
        val selected = Repo.selected.value == id
        if (selected) {
            card.background = Ui.rounded(Ui.cardColor(ctx), 10, ctx, Ui.accent(ctx))
        }
        card.isClickable = true
        card.setOnClickListener {
            Repo.select(id)
            (activity as? MainActivity)?.showTab(1)
        }

        val state = status?.str("state") ?: if (status == null) "Offline" else "Idle"
        val connected = status?.bool("connected") ?: false

        val top = Ui.row(ctx)
        top.addView(Ui.title(ctx, printer.optString("name").ifBlank { "Printer $id" }))
        top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 8, 1))
        val badge = Ui.tiny(ctx, if (connected) state else "Not connected")
        badge.setTextColor(stateColor(ctx, state, connected))
        top.addView(badge)
        top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
        printer.str("model")?.let { top.addView(Ui.tiny(ctx, it)) }
        card.addView(top, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Always built, always hidden while the data is fresh; refreshAges owns it.
        val ageLine = Ui.tiny(ctx, "")
        ageLine.setTextColor(Ui.warn(ctx))
        ageLine.visibility = View.GONE
        card.addView(ageLine)
        ageLines[id] = card to ageLine

        if (status == null) {
            card.addView(Ui.dim(ctx, "No status yet — the server has not reached this printer."))
            return card
        }

        val job = status.str("subtask_name") ?: status.str("current_print") ?: status.str("gcode_file")
        if (job != null) {
            val jobLine = Ui.body(ctx, job)
            jobLine.maxLines = 1
            card.addView(jobLine)
        }

        val progress = status.dbl("progress") ?: 0.0
        if (progress > 0 || state == "RUNNING" || state == "PAUSE") {
            card.addView(Ui.space(ctx, 6))
            val bar = Bar(ctx)
            bar.set(progress / 100.0, stateColor(ctx, state, connected))
            card.addView(bar.view)
            card.addView(Ui.space(ctx, 4))
            val layers = status.int("layer_num")
            val total = status.int("total_layers")
            val parts = ArrayList<String>()
            parts.add("${progress.toInt()}%")
            if (total != null && total > 0) parts.add("layer $layers of $total")
            status.int("remaining_time")?.takeIf { it > 0 }?.let { parts.add("${Ui.minutes(it)} left") }
            card.addView(Ui.dim(ctx, parts.joinToString(" · ")))
        }

        if (state == "PAUSE") runoutNotice(ctx, id, status)?.let { card.addView(it) }

        card.addView(Ui.space(ctx, 8))
        val temps = Ui.row(ctx)
        temps.addView(Ui.stat(ctx, "Nozzle", Ui.temp(status.temp("nozzle"), status.temp("nozzle_target"))))
        temps.addView(Ui.space(ctx, 1), Ui.lp(ctx, 14, 1))
        temps.addView(Ui.stat(ctx, "Bed", Ui.temp(status.temp("bed"), status.temp("bed_target"))))
        status.temp("chamber")?.let {
            temps.addView(Ui.space(ctx, 1), Ui.lp(ctx, 14, 1))
            temps.addView(Ui.stat(ctx, "Chamber", Ui.temp(it, status.temp("chamber_target"))))
        }
        temps.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
        card.addView(temps, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val slots = Ui.row(ctx)
        var any = false
        for (unit in status.objects("ams")) {
            for (tray in unit.objects("tray")) {
                any = true
                slots.addView(Ui.swatch(ctx, tray.str("tray_color"), 16))
                slots.addView(Ui.space(ctx, 1), Ui.lp(ctx, 4, 1))
            }
            slots.addView(Ui.space(ctx, 1), Ui.lp(ctx, 8, 1))
        }
        if (any) {
            card.addView(Ui.space(ctx, 8))
            card.addView(Ui.heading(ctx, "AMS"))
            card.addView(slots, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val faults = Hms.faults(status)
        if (faults.isNotEmpty()) {
            card.addView(Ui.space(ctx, 8))
            val worst = faults.first()
            val warning = Ui.body(ctx, worst.description)
            warning.setTextColor(Ui.severity(ctx, worst.severity))
            card.addView(warning)
            val trailer = listOfNotNull(
                worst.detail,
                if (faults.size > 1) "${faults.size - 1} more" else null
            ).joinToString(" · ")
            if (trailer.isNotBlank()) card.addView(Ui.tiny(ctx, trailer))
        }

        // The server says outright when a plate is waiting to be cleared, and
        // remembers it across a restart. Guessing it from the state does not.
        val awaitingPlate =
            if (status.has("awaiting_plate_clear")) status.bool("awaiting_plate_clear")
            else state == "FINISH" || state == "FAILED"

        if (state == "RUNNING" || state == "PAUSE") {
            card.addView(Ui.space(ctx, 10))
            val actions = Ui.row(ctx)
            if (state == "RUNNING") {
                actions.addView(Ui.button(ctx, "Pause") { command("Pause") { Repo.api.pause(id) } })
            } else {
                actions.addView(Ui.button(ctx, "Resume", primary = true) { command("Resume") { Repo.api.resume(id) } })
            }
            actions.addView(Ui.space(ctx, 1), Ui.lp(ctx, 8, 1))
            actions.addView(Ui.button(ctx, "Stop") { confirmStop(id) })
            card.addView(actions, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else if (awaitingPlate) {
            card.addView(Ui.space(ctx, 10))
            card.addView(Ui.button(ctx, "Plate cleared") { command("Clear plate") { Repo.api.clearPlate(id) } })
        }

        return card
    }

    /**
     * Why a paused printer is paused, when it is a runout.
     *
     * `previous_tray` and `expected_tray` are globalised tray ids the firmware
     * fills in only while a print is paused: the slot that ran out and the slot
     * it now wants. Without them every pause looks the same and he has to read
     * the printer's own screen to find out which.
     */
    private fun runoutNotice(ctx: Context, printerId: Int, status: JSONObject): LinearLayout? {
        // 254 and 255 are the firmware's "nothing here"; a real slot is below them.
        val ran = status.int("previous_tray")?.takeIf { it in 0..253 }
        val wants = status.int("expected_tray")?.takeIf { it in 0..253 }
        if (ran == null && wants == null) return null

        val holder = Ui.col(ctx)
        holder.addView(Ui.space(ctx, 8))

        val words = when {
            ran != null && wants != null && ran != wants ->
                "${Assign.nameGlobalTray(printerId, ran)} ran out — load ${Assign.nameGlobalTray(printerId, wants)}"
            ran != null && wants != null ->
                "${Assign.nameGlobalTray(printerId, ran)} ran out — reload it to carry on"
            wants != null -> "Waiting for ${Assign.nameGlobalTray(printerId, wants)}"
            else -> "${Assign.nameGlobalTray(printerId, ran!!)} ran out"
        }
        val line = Ui.body(ctx, words)
        line.setTextColor(Ui.warn(ctx))
        holder.addView(line)

        val target = wants ?: ran
        if (target != null) {
            holder.addView(Ui.space(ctx, 6))
            val row = Ui.row(ctx)
            row.addView(Ui.button(ctx, "Load ${Assign.nameGlobalTray(printerId, target)}") {
                command("Load") { Repo.api.amsLoad(printerId, target) }
            })
            holder.addView(row, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return holder
    }

    private fun confirmStop(id: Int) {
        val ctx = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Stop this print?")
            .setMessage("The printer cannot resume a stopped print.")
            .setPositiveButton("Stop") { _, _ -> command("Stop") { Repo.api.stop(id) } }
            .setNegativeButton("Keep printing", null)
            .show()
    }

    private fun stateColor(ctx: Context, state: String?, connected: Boolean): Int = when {
        !connected -> Ui.color(ctx, R.color.text_dim)
        state == "RUNNING" -> Ui.good(ctx)
        state == "PAUSE" -> Ui.warn(ctx)
        state == "FAILED" -> Ui.bad(ctx)
        state == "FINISH" -> Ui.accent(ctx)
        else -> Ui.color(ctx, R.color.text_dim)
    }
}
