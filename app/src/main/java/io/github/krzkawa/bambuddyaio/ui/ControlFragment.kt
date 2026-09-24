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

    /** Parts of the machine card fed by their own polls rather than the status. */
    private var powerHolder: LinearLayout? = null
    private var upkeepHolder: LinearLayout? = null

    override fun build(ctx: Context) {
        picker = Ui.col(ctx)
        content.addView(picker, Ui.wide(ctx))
        body = Ui.col(ctx)
        content.addView(body, Ui.wide(ctx))

        observe(Repo.statuses) { render() }
        observe(Repo.selected) { signature = ""; render() }
        observe(Power.plugs) { fillPower() }
        observe(Maintenance.items) { fillUpkeep() }
        observe(Firmware.info) { live.forEach { it(Repo.statuses.value[Repo.selected.value]) } }
        // Each of these asks the server on its own clock and throttles itself,
        // so the Printers screen asking as well costs nothing extra.
        observe(ticker(Power.POLL_MS)) { background({ Power.load(Repo.api, printerIds()) }) {} }
        // On a cold start the first tick comes before the printer list does.
        observe(Repo.printers) { background({ Power.load(Repo.api, printerIds()) }) {} }
        observe(ticker(Maintenance.POLL_MS)) { background({ Maintenance.load(Repo.api) }) {} }
        observe(ticker(Firmware.POLL_MS)) { background({ Firmware.load(Repo.api) }) {} }
        render()
    }

    private fun printerIds(): List<Int> = Repo.printers.value.map { it.optInt("id", -1) }.filter { it >= 0 }

    private fun model(id: Int): String? =
        Repo.printers.value.firstOrNull { it.optInt("id") == id }?.str("model")

    private fun fillPower() {
        val holder = powerHolder ?: return
        val ctx = context ?: return
        val id = Repo.selected.value
        holder.removeAllViews()
        Power.row(ctx, id, Repo.statuses.value[id])?.let {
            holder.addView(it, Ui.wide(ctx))
            holder.addView(Ui.space(ctx, Ui.S))
        }
    }

    private fun fillUpkeep() {
        val holder = upkeepHolder ?: return
        val ctx = context ?: return
        holder.removeAllViews()
        Maintenance.line(ctx, Repo.selected.value)?.let {
            holder.addView(Ui.space(ctx, Ui.S))
            holder.addView(it, Ui.wide(ctx))
        }
    }

    override fun onDestroyView() {
        settle.removeCallbacksAndMessages(null)
        live.clear()
        super.onDestroyView()
    }

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
                .append(status?.bool("connected")).append(Power.busy(status))
                .append(status?.optJSONArray("nozzles")?.length())
                .append(PrintChecks.signature(status))
        }
        if (next == signature && body.childCount > 0) return
        signature = next

        settle.removeCallbacksAndMessages(null)
        live.clear()
        body.removeAllViews()
        powerHolder = null
        upkeepHolder = null

        if (id < 0 || status == null) {
            body.addView(empty(ctx, "The server cannot reach this printer."))
            // A printer that is off at the plug is exactly the one the server
            // cannot reach, so the switch stays on offer here.
            val holder = Ui.col(ctx)
            body.addView(holder, Ui.wide(ctx))
            powerHolder = holder
            fillPower()
            return
        }

        body.addView(printSection(ctx, id, status))
        body.addView(Ui.space(ctx, Ui.M))
        body.addView(tempSection(ctx, id, status))
        body.addView(Ui.space(ctx, Ui.M))
        body.addView(fanSection(ctx, id, status))
        body.addView(Ui.space(ctx, Ui.M))
        body.addView(machineSection(ctx, id, status))

        // Moving the machine is for an idle printer that is listening. Mid-print
        // there is nothing on this card he should be able to press.
        if (status.bool("connected") && !Power.busy(status)) {
            body.addView(Ui.space(ctx, Ui.M))
            val move = Ui.col(ctx)
            body.addView(move, Ui.wide(ctx))
            Move.fill(move, id, model(id), status)
        }

        PrintChecks.card(ctx, id, status)?.let {
            body.addView(Ui.space(ctx, Ui.M))
            body.addView(it)
        }

        if (faults.isNotEmpty()) {
            body.addView(Ui.space(ctx, Ui.M))
            body.addView(errorSection(ctx, id, faults))
        }

        live.forEach { it(status) }
    }

    private fun printSection(ctx: Context, id: Int, status: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        val state = status.str("state") ?: "IDLE"

        val headline = Ui.row(ctx)
        val percent = Ui.display(ctx, percentOf(status))
        headline.addView(percent)
        Ui.gap(ctx, headline, Ui.M)
        val words = Ui.col(ctx)
        val job = Ui.body(ctx, jobName(status))
        job.maxLines = 1
        job.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        words.addView(job)
        val progress = Ui.dim(ctx, progressLine(status))
        words.addView(progress)
        headline.addView(words, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(headline, Ui.wide(ctx))
        live.add { s ->
            percent.text = percentOf(s)
            job.text = jobName(s)
            progress.text = progressLine(s)
        }

        card.addView(Ui.space(ctx, Ui.M))
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

        // Only offered on a plate the printer says holds several objects: with
        // one object there is nothing to save by skipping it.
        if ((status.int("printable_objects_count") ?: 0) > 1) {
            gap(ctx, row)
            row.addView(Ui.button(ctx, "Skip an object") { chooseObject(ctx, id) })
        }
        card.addView(row, Ui.wide(ctx))

        card.addView(Ui.space(ctx, Ui.M))
        card.addView(Ui.divider(ctx))
        card.addView(Ui.space(ctx, Ui.M))
        val speedRow = Ui.row(ctx)
        speedRow.addView(Ui.heading(ctx, "Speed"))
        Ui.push(ctx, speedRow)
        val labels = listOf("Silent", "Standard", "Sport", "Ludicrous")
        val current = (status.int("speed_level") ?: 2).coerceIn(1, 4)
        speedRow.addView(Ui.segmented(ctx, labels, current - 1) { index ->
            command("Speed ${labels[index]}") { Repo.api.setSpeed(id, index + 1) }
        })
        card.addView(speedRow, Ui.wide(ctx))
        return card
    }

    private fun percentOf(status: JSONObject?): String =
        "${(status?.dbl("progress") ?: 0.0).toInt()}%"

    private fun jobName(status: JSONObject?): String =
        status?.str("subtask_name") ?: status?.str("gcode_file") ?: "Nothing printing"

    private fun progressLine(status: JSONObject?): String {
        // The stage the firmware is in says more than "Printing" or "Preparing"
        // while it levels the bed or calibrates, and Bambuddy already names it.
        val stage = status?.str("stg_cur_name")?.takeIf { it != "Printing" && Power.busy(status) }
        val state = stage ?: Ui.stateWord(status?.str("state"))
        val remaining = status?.int("remaining_time")
        return if (remaining == null || remaining <= 0) state
        else "$state · ${Ui.minutes(remaining)} left"
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
        val heading = Ui.row(ctx)
        heading.addView(Ui.heading(ctx, "Temperatures"))
        Ui.push(ctx, heading)
        heading.addView(Ui.quiet(ctx, "History") { HeaterHistory.show(this, id) })
        card.addView(heading, Ui.wide(ctx))

        card.addView(heater(ctx, "Nozzle", "nozzle", Temps.NOZZLE_MAX) { target ->
            command("Nozzle ${target}°") { Repo.api.setNozzleTemp(id, target) }
        }, Ui.wide(ctx))
        card.addView(Ui.divider(ctx))
        card.addView(heater(ctx, "Bed", "bed", Temps.BED_MAX) { target ->
            command("Bed ${target}°") { Repo.api.setBedTemp(id, target) }
        }, Ui.wide(ctx))
        // Chamber keys are dropped entirely on a printer with no chamber sensor.
        if (status.temp("chamber") != null) {
            card.addView(Ui.divider(ctx))
            card.addView(heater(ctx, "Chamber", "chamber", Temps.CHAMBER_MAX) { target ->
                command("Chamber ${target}°") { Repo.api.setChamberTemp(id, target) }
            }, Ui.wide(ctx))
        }
        return card
    }

    private fun heater(ctx: Context, label: String, key: String, max: Int, send: (Int) -> Unit): LinearLayout {
        val row = Ui.row(ctx)
        row.setPadding(0, Ui.dp(ctx, Ui.S), 0, Ui.dp(ctx, Ui.S))
        val stat = Ui.stat(ctx, label, "—")
        row.addView(stat, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val h = Heater(ctx, label, key, max, stat, send)
        live.add { status -> h.show(status) }

        row.addView(Ui.quiet(ctx, "Off") { h.set(0) })
        gap(ctx, row, 4)
        row.addView(Ui.button(ctx, "−10") { h.nudge(-10) })
        gap(ctx, row, 4)
        row.addView(Ui.button(ctx, "+10") { h.nudge(10) })
        gap(ctx, row, 4)
        row.addView(Ui.button(ctx, "Set") { typeTemperature(ctx, h) })
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
        card.addView(fanRow(ctx, id, "Part cooling", "part", "cooling_fan_speed"), Ui.wide(ctx))
        card.addView(Ui.divider(ctx))
        card.addView(fanRow(ctx, id, "Auxiliary", "aux", "big_fan1_speed"), Ui.wide(ctx))
        card.addView(Ui.divider(ctx))
        card.addView(fanRow(ctx, id, "Chamber", "chamber", "big_fan2_speed"), Ui.wide(ctx))
        return card
    }

    private fun fanRow(ctx: Context, id: Int, label: String, fan: String, speedKey: String): LinearLayout {
        val row = Ui.row(ctx)
        row.setPadding(0, Ui.dp(ctx, Ui.S), 0, Ui.dp(ctx, Ui.S))
        val stat = Ui.stat(ctx, label, "—")
        live.add { status ->
            val speed = status?.int(speedKey)
            Ui.setStat(stat, if (speed == null) "—" else "$speed%")
        }
        row.addView(stat, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf(0, 50, 100).forEach { value ->
            row.addView(Ui.button(ctx, "$value%") { command("$label $value%") { Repo.api.setFan(id, fan, value) } })
            gap(ctx, row, 4)
        }
        return row
    }

    private fun machineSection(ctx: Context, id: Int, status: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Machine"))
        val power = Ui.col(ctx)
        card.addView(power, Ui.wide(ctx))
        powerHolder = power
        fillPower()
        val row = Ui.row(ctx)
        val lightOn = status.bool("chamber_light")
        row.addView(Ui.button(ctx, if (lightOn) "Light off" else "Light on", primary = !lightOn) {
            command("Light") { Repo.api.setLight(id, !lightOn) }
        })
        // Homing moved to the Move card, which is only offered while the
        // printer is idle: it was one question away from running mid-print.
        gap(ctx, row)
        row.addView(Ui.button(ctx, "Maintenance") { Maintenance.choose(ctx, id, attentionOnly = false) })
        gap(ctx, row)
        row.addView(Ui.button(ctx, "Refresh") { command("Refresh") { Repo.api.refreshStatus(id) } })
        if (PrintChecks.hasAirduct(model(id))) {
            Ui.gap(ctx, row, Ui.L)
            row.addView(PrintChecks.airduct(ctx, id, status), Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(row, Ui.wide(ctx))
        val upkeep = Ui.col(ctx)
        card.addView(upkeep, Ui.wide(ctx))
        upkeepHolder = upkeep
        fillUpkeep()

        card.addView(Ui.space(ctx, Ui.M))
        val info = Ui.row(ctx)
        val network = Ui.stat(ctx, "Network", "—")
        val door = Ui.stat(ctx, "Door", "—")
        val sd = Ui.stat(ctx, "SD card", "—")
        val firmware = Ui.stat(ctx, "Firmware", "—")
        live.add { s ->
            val update = Firmware.update(id)
            Ui.setStat(firmware, s?.str("firmware_version") ?: Firmware.info.value[id]?.current ?: "—")
            (firmware.getChildAt(1) as? android.widget.TextView)?.text =
                if (update?.latest != null) "Firmware · ${update.latest} out" else "Firmware"
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
        gap(ctx, info, Ui.XL)
        info.addView(door)
        gap(ctx, info, Ui.XL)
        info.addView(sd)
        gap(ctx, info, Ui.XL)
        info.addView(firmware)
        firmware.setOnClickListener { Firmware.update(id)?.let { Firmware.explain(ctx, it) } }
        card.addView(info, Ui.wide(ctx))
        return card
    }

    private fun errorSection(ctx: Context, id: Int, faults: List<Hms.Fault>): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Errors"))
        faults.take(6).forEachIndexed { index, fault ->
            if (index > 0) {
                card.addView(Ui.space(ctx, Ui.S))
                card.addView(Ui.divider(ctx))
                card.addView(Ui.space(ctx, Ui.S))
            }
            val line = Ui.row(ctx)
            line.gravity = android.view.Gravity.TOP
            line.addView(Ui.dot(ctx, Ui.severity(ctx, fault.severity)), Ui.lp(ctx, 7, 7).also {
                it.topMargin = Ui.dp(ctx, 6)
            })
            Ui.gap(ctx, line, Ui.S)
            val words = Ui.col(ctx)
            words.addView(Ui.body(ctx, fault.description))
            fault.detail?.let { words.addView(Ui.tiny(ctx, it)) }
            actionRow(ctx, id, fault)?.let {
                words.addView(Ui.space(ctx, Ui.S))
                words.addView(it, Ui.wide(ctx))
            }
            line.addView(words, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(line, Ui.wide(ctx))
        }
        card.addView(Ui.space(ctx, Ui.M))
        card.addView(Ui.quiet(ctx, "Clear errors") { command("Clear errors") { Repo.api.clearHms(id) } })
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

    private fun gap(ctx: Context, row: LinearLayout, width: Int = Ui.S) = Ui.gap(ctx, row, width)
}
