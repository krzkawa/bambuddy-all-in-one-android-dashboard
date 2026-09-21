package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.bool
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.str
import io.github.krzkawa.bambuddyaio.util.temp
import org.json.JSONObject

/** Hands-on control of one printer: the print, the heaters, the fans, the light. */
class ControlFragment : BaseFragment() {

    private lateinit var picker: LinearLayout
    private lateinit var body: LinearLayout
    private var signature = ""

    override fun build(ctx: Context) {
        content.addView(header(ctx, "Control"))
        picker = Ui.col(ctx)
        content.addView(picker, wide(ctx))
        body = Ui.col(ctx)
        content.addView(body, wide(ctx))

        observe(Repo.statuses) { render() }
        observe(Repo.selected) { signature = ""; render() }
        render()
    }

    private fun wide(ctx: Context) =
        Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun render() {
        val ctx = context ?: return
        buildPrinterPicker(ctx, picker) { signature = ""; render() }

        val id = Repo.selected.value
        val status = Repo.statuses.value[id]
        val faults = Hms.faults(status)

        val next = buildString {
            append(id).append(status?.str("state")).append(status?.bool("chamber_light"))
                .append(status?.int("speed_level")).append(status?.temp("nozzle")?.toInt())
                .append(status?.temp("bed")?.toInt()).append(status?.temp("chamber")?.toInt())
                .append(status?.temp("nozzle_target")?.toInt()).append(status?.temp("bed_target")?.toInt())
                .append(status?.int("cooling_fan_speed"))
                .append(faults.size).append(faults.firstOrNull()?.description)
                .append(status?.bool("wired_network"))
        }
        if (next == signature && body.childCount > 0) return
        signature = next

        body.removeAllViews()

        if (id < 0 || status == null) {
            body.addView(Ui.dim(ctx, "Pick a printer that the server can reach."))
            return
        }

        body.addView(printSection(ctx, id, status))
        body.addView(Ui.space(ctx, 8))
        body.addView(tempSection(ctx, id, status))
        body.addView(Ui.space(ctx, 8))
        body.addView(fanSection(ctx, id, status))
        body.addView(Ui.space(ctx, 8))
        body.addView(machineSection(ctx, id, status))

        if (faults.isNotEmpty()) {
            body.addView(Ui.space(ctx, 8))
            body.addView(errorSection(ctx, id, faults))
        }
    }

    private fun printSection(ctx: Context, id: Int, status: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Print"))
        val state = status.str("state") ?: "IDLE"
        val job = status.str("subtask_name") ?: status.str("gcode_file") ?: "Nothing printing"
        card.addView(Ui.title(ctx, job))
        val progress = status.dbl("progress") ?: 0.0
        card.addView(Ui.dim(ctx, "$state · ${progress.toInt()}% · ${Ui.minutes(status.int("remaining_time"))} left"))
        card.addView(Ui.space(ctx, 8))

        val row = Ui.row(ctx)
        if (state == "PAUSE") {
            row.addView(Ui.button(ctx, "Resume", primary = true) { command("Resume") { Repo.api.resume(id) } })
        } else {
            row.addView(Ui.button(ctx, "Pause") { command("Pause") { Repo.api.pause(id) } })
        }
        gap(ctx, row)
        row.addView(Ui.button(ctx, "Stop") {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Stop this print?")
                .setMessage("The printer cannot resume a stopped print.")
                .setPositiveButton("Stop") { _, _ -> command("Stop") { Repo.api.stop(id) } }
                .setNegativeButton("Keep printing", null)
                .show()
        })
        gap(ctx, row)
        row.addView(Ui.button(ctx, "Plate cleared") { command("Clear plate") { Repo.api.clearPlate(id) } })
        card.addView(row, wide(ctx))

        card.addView(Ui.space(ctx, 10))
        card.addView(Ui.heading(ctx, "Speed"))
        val speeds = Ui.row(ctx)
        val current = status.int("speed_level") ?: 2
        listOf(1 to "Silent", 2 to "Standard", 3 to "Sport", 4 to "Ludicrous").forEach { (mode, label) ->
            speeds.addView(Ui.button(ctx, label, primary = mode == current) {
                command("Speed $label") { Repo.api.setSpeed(id, mode) }
            })
            gap(ctx, speeds)
        }
        card.addView(speeds, wide(ctx))
        return card
    }

    private fun tempSection(ctx: Context, id: Int, status: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Temperatures"))

        card.addView(tempRow(ctx, "Nozzle", status.temp("nozzle"), status.temp("nozzle_target"), 300) { target ->
            command("Nozzle ${target}°") { Repo.api.setNozzleTemp(id, target) }
        })
        card.addView(Ui.space(ctx, 6))
        card.addView(tempRow(ctx, "Bed", status.temp("bed"), status.temp("bed_target"), 120) { target ->
            command("Bed ${target}°") { Repo.api.setBedTemp(id, target) }
        })
        if (status.temp("chamber") != null) {
            card.addView(Ui.space(ctx, 6))
            card.addView(tempRow(ctx, "Chamber", status.temp("chamber"), status.temp("chamber_target"), 60) { target ->
                command("Chamber ${target}°") { Repo.api.setChamberTemp(id, target) }
            })
        }
        return card
    }

    private fun tempRow(
        ctx: Context,
        label: String,
        current: Double?,
        target: Double?,
        max: Int,
        send: (Int) -> Unit
    ): LinearLayout {
        val row = Ui.row(ctx)
        val stat = Ui.stat(ctx, label, Ui.temp(current, target))
        row.addView(stat, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var pending = (target ?: 0.0).toInt()
        row.addView(Ui.button(ctx, "−10") {
            pending = (pending - 10).coerceIn(0, max)
            send(pending)
        })
        gap(ctx, row)
        row.addView(Ui.button(ctx, "+10") {
            pending = (pending + 10).coerceIn(0, max)
            send(pending)
        })
        gap(ctx, row)
        row.addView(Ui.button(ctx, "Off") {
            pending = 0
            send(0)
        })
        return row
    }

    private fun fanSection(ctx: Context, id: Int, status: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Fans"))
        card.addView(fanRow(ctx, id, "Part cooling", "part", status.int("cooling_fan_speed")))
        card.addView(Ui.space(ctx, 6))
        card.addView(fanRow(ctx, id, "Auxiliary", "aux", status.int("big_fan1_speed")))
        card.addView(Ui.space(ctx, 6))
        card.addView(fanRow(ctx, id, "Chamber", "chamber", status.int("big_fan2_speed")))
        return card
    }

    private fun fanRow(ctx: Context, id: Int, label: String, fan: String, speed: Int?): LinearLayout {
        val row = Ui.row(ctx)
        row.addView(
            Ui.stat(ctx, label, if (speed == null) "—" else "$speed%"),
            Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        listOf(0, 50, 100).forEach { value ->
            row.addView(Ui.button(ctx, "$value%") { command("$label $value%") { Repo.api.setFan(id, fan, value) } })
            gap(ctx, row)
        }
        return row
    }

    private fun machineSection(ctx: Context, id: Int, status: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Machine"))
        val row = Ui.row(ctx)
        val lightOn = status.bool("chamber_light")
        row.addView(Ui.button(ctx, if (lightOn) "Light off" else "Light on", primary = !lightOn) {
            command("Light") { Repo.api.setLight(id, !lightOn) }
        })
        gap(ctx, row)
        row.addView(Ui.button(ctx, "Home axes") {
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Home the axes?")
                .setMessage("The printer will run its full homing sequence. Do not do this mid-print.")
                .setPositiveButton("Home") { _, _ -> command("Home") { Repo.api.homeAxes(id) } }
                .setNegativeButton("Cancel", null)
                .show()
        })
        gap(ctx, row)
        row.addView(Ui.button(ctx, "Refresh") { command("Refresh") { Repo.api.refreshStatus(id) } })
        card.addView(row, wide(ctx))

        card.addView(Ui.space(ctx, 8))
        val info = Ui.row(ctx)
        // wired_network is a real boolean on the status. A null wifi_signal only
        // ever meant "the printer did not say", which is not the same as wired.
        val network = when {
            status.bool("wired_network") -> "Wired"
            status.int("wifi_signal") != null -> "${status.int("wifi_signal")} dBm"
            else -> "—"
        }
        info.addView(Ui.stat(ctx, "Network", network))
        gap(ctx, info, 14)
        info.addView(Ui.stat(ctx, "Door", if (status.bool("door_open")) "Open" else "Closed"))
        gap(ctx, info, 14)
        info.addView(Ui.stat(ctx, "SD card", if (status.bool("sdcard")) "In" else "None"))
        card.addView(info, wide(ctx))
        return card
    }

    private fun errorSection(ctx: Context, id: Int, faults: List<Hms.Fault>): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Printer errors"))
        for (fault in faults.take(6)) {
            val line = Ui.body(ctx, fault.description)
            line.setTextColor(Ui.severity(ctx, fault.severity))
            card.addView(line)
            fault.detail?.let { card.addView(Ui.tiny(ctx, it)) }
            card.addView(Ui.space(ctx, 4))
        }
        card.addView(Ui.space(ctx, 8))
        card.addView(Ui.button(ctx, "Clear errors") { command("Clear errors") { Repo.api.clearHms(id) } })
        return card
    }

    private fun gap(ctx: Context, row: LinearLayout, width: Int = 8) {
        row.addView(Ui.space(ctx, 1), Ui.lp(ctx, width, 1))
    }
}
