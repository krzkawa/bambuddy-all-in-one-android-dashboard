package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.R
import io.github.krzkawa.bambuddyaio.net.Repo
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

    override fun build(ctx: Context) {
        content.addView(header(ctx, "Printers", "Tap a printer to control it"))
        list = Ui.col(ctx)
        content.addView(list, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        observe(Repo.statuses) { render() }
        observe(Repo.selected) { render() }
        render()
    }

    private fun render() {
        val ctx = context ?: return
        val statuses = Repo.statuses.value
        val printers = Repo.printers.value

        // Rebuilding a card every four seconds would fight the user's finger, so
        // only redraw when something actually moved.
        val next = buildString {
            append(Repo.selected.value)
            for (p in printers) {
                val s = statuses[p.optInt("id")]
                append(p.optInt("id")).append(s?.str("state")).append(s?.dbl("progress"))
                    .append(s?.int("layer_num")).append(s?.temp("nozzle")?.toInt())
                    .append(s?.temp("bed")?.toInt()).append(s?.bool("connected"))
                    .append(s?.objects("hms_errors")?.size)
            }
        }
        if (next == signature && list.childCount > 0) return
        signature = next

        list.removeAllViews()

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

        val errors = status.objects("hms_errors")
        if (errors.isNotEmpty()) {
            card.addView(Ui.space(ctx, 8))
            val warning = Ui.body(ctx, errors.first().str("text") ?: "Printer reported an error")
            warning.setTextColor(Ui.bad(ctx))
            card.addView(warning)
            if (errors.size > 1) card.addView(Ui.tiny(ctx, "${errors.size - 1} more"))
        }

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
        } else if (state == "FINISH" || state == "FAILED") {
            card.addView(Ui.space(ctx, 10))
            card.addView(Ui.button(ctx, "Plate cleared") { command("Clear plate") { Repo.api.clearPlate(id) } })
        }

        return card
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
