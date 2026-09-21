package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
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

    /**
     * Everything on the card that changes with every poll, updated in place.
     *
     * A degree ticking past used to be part of the rebuild signature, so the
     * card was thrown away and rebuilt under his finger every second the
     * nozzle was climbing — which is exactly what the signature was added to
     * prevent. The signature now covers only what changes the card's *shape*,
     * and every reading on it refreshes through here instead, so the numbers
     * move without the buttons moving.
     */
    private val live = ArrayList<(JSONObject?) -> Unit>()

    /** Runs the pending temperature commands once he has stopped tapping. */
    private val settle = Handler(Looper.getMainLooper())

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

    override fun onDestroyView() {
        settle.removeCallbacksAndMessages(null)
        live.clear()
        super.onDestroyView()
    }

    private fun wide(ctx: Context) =
        Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun render() {
        val ctx = context ?: return
        buildPrinterPicker(ctx, picker) { signature = ""; render() }

        val id = Repo.selected.value
        val status = Repo.statuses.value[id]
        val faults = Hms.faults(status)

        // The readings refresh whether or not the card is rebuilt, because they
        // are deliberately not part of the signature below.
        live.forEach { it(status) }

        val next = buildString {
            append(id).append(status?.str("state")).append(status?.bool("chamber_light"))
                .append(status?.int("speed_level"))
                .append(status?.temp("chamber") != null)
                .append((status?.int("printable_objects_count") ?: 0) > 1)
                .append(faults.size).append(faults.firstOrNull()?.description)
                .append(faults.flatMap { it.runnableActions })
        }
        if (next == signature && body.childCount > 0) return
        signature = next

        settle.removeCallbacksAndMessages(null)
        live.clear()
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

        live.forEach { it(status) }
    }

    private fun printSection(ctx: Context, id: Int, status: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Print"))
        val state = status.str("state") ?: "IDLE"
        val job = Ui.title(ctx, jobName(status))
        card.addView(job)
        val progress = Ui.dim(ctx, progressLine(status))
        card.addView(progress)
        live.add { s ->
            job.text = jobName(s)
            progress.text = progressLine(s)
        }
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

        // Only offered on a plate the printer says holds several objects: with
        // one object there is nothing to save by skipping it.
        if ((status.int("printable_objects_count") ?: 0) > 1) {
            card.addView(Ui.space(ctx, 8))
            card.addView(Ui.button(ctx, "Skip an object") { chooseObject(ctx, id) })
        }

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

    private fun jobName(status: JSONObject?): String =
        status?.str("subtask_name") ?: status?.str("gcode_file") ?: "Nothing printing"

    private fun progressLine(status: JSONObject?): String {
        val state = status?.str("state") ?: "IDLE"
        val progress = status?.dbl("progress") ?: 0.0
        return "$state · ${progress.toInt()}% · ${Ui.minutes(status?.int("remaining_time"))} left"
    }

    // ---------------------------------------------------------- skipping one object

    /**
     * Asks the printer what is on the plate, then lets him abandon one part.
     *
     * The list has to come from the printer rather than from the status: only
     * it knows which objects have already been skipped, and skipping the same
     * one twice is a command it will refuse.
     */
    private fun chooseObject(ctx: Context, id: Int) {
        toast("Reading the plate…")
        background({ PrintObjects.read(Repo.api.printObjects(id)) }) { result ->
            result.onFailure { toast(it.message ?: "Could not read the plate") }
            result.onSuccess { plate ->
                if (!plate.canSkip) {
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle("Nothing to skip")
                        .setMessage(PrintObjects.nothingToSkip(plate))
                        .setPositiveButton("OK", null)
                        .show()
                    return@onSuccess
                }
                val objects = plate.remaining
                val labels = objects.map { it.label }.toTypedArray()
                val already = plate.skipped.size
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(if (already == 0) "Skip which object?" else "Skip which object? ($already already skipped)")
                    .setItems(labels) { _, which -> confirmSkip(ctx, id, objects[which]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** The firmware cannot put an object back, so this one is always asked twice. */
    private fun confirmSkip(ctx: Context, id: Int, obj: PrintObjects.Obj) {
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Skip ${obj.name}?")
            .setMessage("The printer will abandon this object and carry on with the rest. It cannot be brought back into this print.")
            .setPositiveButton("Skip it") { _, _ ->
                command("Skip ${obj.name}") { Repo.api.skipObjects(id, listOf(obj.id)) }
            }
            .setNegativeButton("Keep printing it", null)
            .show()
    }

    // ------------------------------------------------------------------ heaters

    private fun tempSection(ctx: Context, id: Int, status: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Temperatures"))

        card.addView(heater(ctx, "Nozzle", "nozzle", Temps.NOZZLE_MAX) { target ->
            command("Nozzle ${target}°") { Repo.api.setNozzleTemp(id, target) }
        })
        card.addView(Ui.space(ctx, 6))
        card.addView(heater(ctx, "Bed", "bed", Temps.BED_MAX) { target ->
            command("Bed ${target}°") { Repo.api.setBedTemp(id, target) }
        })
        // Chamber keys are dropped entirely on a printer with no chamber sensor.
        if (status.temp("chamber") != null) {
            card.addView(Ui.space(ctx, 6))
            card.addView(heater(ctx, "Chamber", "chamber", Temps.CHAMBER_MAX) { target ->
                command("Chamber ${target}°") { Repo.api.setChamberTemp(id, target) }
            })
        }
        return card
    }

    private fun heater(ctx: Context, label: String, key: String, max: Int, send: (Int) -> Unit): LinearLayout {
        val row = Ui.row(ctx)
        val stat = Ui.stat(ctx, label, "—")
        row.addView(stat, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val h = Heater(ctx, label, key, max, stat, send)
        live.add { status -> h.show(status) }

        row.addView(Ui.button(ctx, "Set") { typeTemperature(ctx, h) })
        gap(ctx, row)
        row.addView(Ui.button(ctx, "−10") { h.nudge(-10) })
        gap(ctx, row)
        row.addView(Ui.button(ctx, "+10") { h.nudge(10) })
        gap(ctx, row)
        row.addView(Ui.button(ctx, "Off") { h.set(0) })
        return row
    }

    /** The whole point of item 6: one number, one command, no counting taps. */
    private fun typeTemperature(ctx: Context, h: Heater) {
        val field = Ui.input(ctx, "0 – ${h.max}", if (h.pending > 0) h.pending.toString() else "")
        field.inputType = InputType.TYPE_CLASS_NUMBER
        field.setSelection(field.text.length)
        val frame = Ui.col(ctx)
        val p = Ui.dp(ctx, 16)
        frame.setPadding(p, Ui.dp(ctx, 8), p, 0)
        frame.addView(field, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("${h.label} temperature")
            .setView(frame)
            .setPositiveButton("Set") { _, _ ->
                val target = Temps.typed(field.text?.toString(), h.max)
                if (target == null) {
                    // Refused rather than clamped: a slipped digit should not
                    // quietly send the printer somewhere he did not ask for.
                    toast("${h.label} takes 0 to ${h.max}°")
                } else {
                    h.set(target)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * One heater's row: what it reads now, and what he is winding it towards.
     *
     * Each tap of ±10 used to be its own command to the printer. Taps now move
     * a pending figure that is shown straight away, and one command follows
     * once he stops — so cold to 220° is a couple of taps and a single
     * instruction, instead of twenty-two of them racing the status poll.
     */
    private inner class Heater(
        val ctx: Context,
        val label: String,
        val key: String,
        val max: Int,
        val stat: LinearLayout,
        val send: (Int) -> Unit
    ) {
        var pending = 0
            private set

        /** True while the screen is showing his figure rather than the printer's. */
        private var awaiting = false

        /** When to stop insisting, so a command the printer ignored is not shown forever. */
        private var insistUntil = 0L

        private val commit = Runnable { send(pending) }

        fun nudge(by: Int) {
            pending = Temps.step(pending, by, max)
            claim()
            settle.removeCallbacks(commit)
            settle.postDelayed(commit, Temps.SETTLE_MS)
        }

        fun set(target: Int) {
            settle.removeCallbacks(commit)
            pending = Temps.clamp(target, max)
            claim()
            send(pending)
        }

        private fun claim() {
            awaiting = true
            insistUntil = System.currentTimeMillis() + 15_000L
            show(Repo.statuses.value[Repo.selected.value])
        }

        fun show(status: JSONObject?) {
            val current = status?.temp(key)
            val target = status?.temp("${key}_target")
            val reported = Temps.startingPoint(target)
            if (awaiting && (reported == pending || System.currentTimeMillis() > insistUntil)) {
                awaiting = false
            }
            if (!awaiting) pending = reported
            val shown =
                if (awaiting) Ui.temp(current, pending.toDouble()) else Ui.temp(current, target)
            Ui.setStat(stat, shown, if (awaiting) Ui.accent(ctx) else Ui.textColor(ctx))
        }
    }

    private fun fanSection(ctx: Context, id: Int, status: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Fans"))
        card.addView(fanRow(ctx, id, "Part cooling", "part", "cooling_fan_speed"))
        card.addView(Ui.space(ctx, 6))
        card.addView(fanRow(ctx, id, "Auxiliary", "aux", "big_fan1_speed"))
        card.addView(Ui.space(ctx, 6))
        card.addView(fanRow(ctx, id, "Chamber", "chamber", "big_fan2_speed"))
        return card
    }

    private fun fanRow(ctx: Context, id: Int, label: String, fan: String, speedKey: String): LinearLayout {
        val row = Ui.row(ctx)
        val stat = Ui.stat(ctx, label, "—")
        live.add { status ->
            val speed = status?.int(speedKey)
            Ui.setStat(stat, if (speed == null) "—" else "$speed%")
        }
        row.addView(stat, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        val network = Ui.stat(ctx, "Network", "—")
        val door = Ui.stat(ctx, "Door", "—")
        val sd = Ui.stat(ctx, "SD card", "—")
        live.add { s ->
            // wired_network is a real boolean on the status. A null wifi_signal
            // only ever meant "the printer did not say", which is not the same
            // as wired.
            Ui.setStat(network, when {
                s == null -> "—"
                s.bool("wired_network") -> "Wired"
                s.int("wifi_signal") != null -> "${s.int("wifi_signal")} dBm"
                else -> "—"
            })
            Ui.setStat(door, if (s?.bool("door_open") == true) "Open" else "Closed")
            Ui.setStat(sd, if (s?.bool("sdcard") == true) "In" else "None")
        }
        info.addView(network)
        gap(ctx, info, 14)
        info.addView(door)
        gap(ctx, info, 14)
        info.addView(sd)
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
            actionRow(ctx, id, fault)?.let {
                card.addView(Ui.space(ctx, 4))
                card.addView(it, wide(ctx))
            }
            card.addView(Ui.space(ctx, 6))
        }
        card.addView(Ui.space(ctx, 4))
        card.addView(Ui.button(ctx, "Clear errors") { command("Clear errors") { Repo.api.clearHms(id) } })
        return card
    }

    /**
     * The ways out of this fault that the printer itself offered.
     *
     * A halted machine usually comes with the answer attached — resume, retry,
     * ignore it — and the app used to throw those away and offer only "Clear
     * errors", which hides the fault without doing anything about it.
     */
    private fun actionRow(ctx: Context, id: Int, fault: Hms.Fault): LinearLayout? {
        val actions = fault.runnableActions
        if (actions.isEmpty()) return null
        val code = fault.fullCode ?: return null
        val row = Ui.row(ctx)
        for (action in actions.take(4)) {
            val label = Hms.actionLabel(action)
            row.addView(Ui.button(ctx, label) {
                if (Hms.isWeighty(action)) {
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle(label + "?")
                        .setMessage(fault.description)
                        .setPositiveButton(label) { _, _ -> runAction(id, code, action, label, fault.jobId) }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    runAction(id, code, action, label, fault.jobId)
                }
            })
            gap(ctx, row)
        }
        return row
    }

    private fun runAction(id: Int, code: String, action: String, label: String, jobId: String?) {
        command(label) { Repo.api.hmsAction(id, code, action, jobId) }
    }

    private fun gap(ctx: Context, row: LinearLayout, width: Int = 8) {
        row.addView(Ui.space(ctx, 1), Ui.lp(ctx, width, 1))
    }
}
