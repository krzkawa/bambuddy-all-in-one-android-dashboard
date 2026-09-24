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

    /** What is lined up behind what is printing. Polled apart from the status. */
    private var queued: List<JSONObject> = emptyList()

    /** Each card's "not live" line, so its age can tick without a rebuild. */
    private val ageLines = HashMap<Int, Pair<LinearLayout, TextView>>()

    override fun build(ctx: Context) {
        list = Ui.col(ctx)
        content.addView(list, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        observe(Repo.statuses) { render() }
        observe(Repo.selected) { render() }
        observe(ticker(5_000)) { refreshAges() }
        // The queue is not part of the status poll, so it gets a slow clock of
        // its own: what is next changes when a print ends, not second by second.
        observe(ticker(60_000)) { loadQueue() }
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
                    .append(s?.bool("chamber_light"))
                    .append(Queue.nextFor(queued, p.optInt("id"))?.let { Queue.itemName(it) })
                    .append(s?.objects("ams")?.joinToString {
                        u -> u.objects("tray").joinToString { t ->
                            "${t.str("tray_type")}${t.optInt("remain")}${t.int("state")}"
                        }
                    })
                    .append(Hms.faults(s).firstOrNull()?.description)
                    .append(s?.objects("hms_errors")?.size)
            }
        }
        if (next == signature && list.childCount > 0) return
        signature = next

        list.removeAllViews()
        ageLines.clear()

        if (printers.isEmpty()) {
            list.addView(
                empty(
                    ctx,
                    Repo.error.value ?: "No printers on this Bambuddy server yet.",
                    "Add one in Bambuddy and it appears here."
                )
            )
            return
        }

        for (printer in printers) {
            val id = printer.optInt("id", -1)
            if (id < 0) continue
            list.addView(card(ctx, id, printer, statuses[id]))
            list.addView(Ui.space(ctx, Ui.S))
        }
        Ui.arrive(list)
        refreshAges()
    }

    private fun loadQueue() {
        background({ Repo.api.queue() }) { result ->
            result.onSuccess {
                queued = it.objects()
                render()
            }
            // A queue that will not load costs him one line at the foot of a
            // card. It is not worth a message over the status that did load.
        }
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
            // The one card you are working on is outlined. Nothing else is.
            card.background = Ui.rounded(Ui.cardColor(ctx), 12, ctx, Ui.strokeColor(ctx))
        }
        card.isClickable = true
        card.setOnClickListener {
            Repo.select(id)
            (activity as? MainActivity)?.showTab(MainActivity.CONTROL_TAB)
        }

        val state = status?.str("state") ?: if (status == null) "Offline" else "Idle"
        val connected = status?.bool("connected") ?: false
        val colour = stateColor(ctx, state, connected)

        val top = Ui.row(ctx)
        top.addView(Ui.dot(ctx, colour))
        Ui.gap(ctx, top, Ui.S)
        top.addView(Ui.title(ctx, printer.optString("name").ifBlank { "Printer $id" }))
        Ui.gap(ctx, top, Ui.S)
        val badge = Ui.dim(ctx, if (connected) Ui.stateWord(state) else "Not connected")
        badge.setTextColor(colour)
        top.addView(badge)
        Ui.push(ctx, top)
        printer.str("model")?.let { top.addView(Ui.tiny(ctx, it)) }
        card.addView(top, Ui.wide(ctx))

        // Always built, always hidden while the data is fresh; refreshAges owns it.
        val ageLine = Ui.tiny(ctx, "")
        ageLine.setTextColor(Ui.warn(ctx))
        ageLine.visibility = View.GONE
        card.addView(ageLine)
        ageLines[id] = card to ageLine

        if (status == null) {
            card.addView(Ui.space(ctx, Ui.S))
            card.addView(Ui.dim(ctx, "The server has not reached this printer."))
            return card
        }

        val progress = status.dbl("progress") ?: 0.0
        // The large figure is for a print that is moving. A finished plate has
        // nothing left to count, and 100% set in that size just shouts.
        val running = state == "RUNNING" || state == "PAUSE"
        val job = status.str("subtask_name") ?: status.str("current_print") ?: status.str("gcode_file")

        // The server says outright when a plate is waiting to be cleared, and
        // remembers it across a restart. Guessing it from the state does not.
        val awaitingPlate =
            if (status.has("awaiting_plate_clear")) status.bool("awaiting_plate_clear")
            else state == "FINISH" || state == "FAILED"

        // Landscape leaves this card far more width than height, so what the
        // print is doing and what you can do about it share one line rather
        // than costing two.
        card.addView(Ui.space(ctx, Ui.S))
        val headline = Ui.row(ctx)
        if (running) {
            headline.addView(Ui.display(ctx, "${progress.toInt()}%", colour))
            Ui.gap(ctx, headline, Ui.M)
        }
        val words = Ui.col(ctx)
        if (job != null) {
            val jobLine = if (running) Ui.body(ctx, job) else Ui.dim(ctx, job)
            jobLine.maxLines = 1
            jobLine.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            words.addView(jobLine)
        }
        if (running) {
            val parts = ArrayList<String>()
            val layers = status.int("layer_num")
            val total = status.int("total_layers")
            // "118/184" rather than "layer 118 of 184": this line has to hold
            // three facts and still sit on one row beside the buttons.
            if (total != null && total > 0) parts.add("$layers/$total")
            status.int("remaining_time")?.takeIf { it > 0 }?.let {
                // Both forms, because they answer different questions: how much
                // longer if you are waiting for it, and what time to come back
                // if you are not.
                parts.add("${Ui.minutes(it)} left")
                parts.add("done ${finishTime(ctx, it)}")
            }
            if (parts.isNotEmpty()) {
                val line = Ui.dim(ctx, parts.joinToString(" · "))
                line.maxLines = 1
                words.addView(line)
            }
        }
        headline.addView(words, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // The light is the one thing you reach for that is not about the print
        // itself, and it was a tab away.
        if (connected && status.has("chamber_light")) {
            val lightOn = status.bool("chamber_light")
            headline.addView(Ui.quiet(ctx, if (lightOn) "Light off" else "Light on") {
                command("Light") { Repo.api.setLight(id, !lightOn) }
            })
            Ui.gap(ctx, headline, Ui.XS)
        }

        if (running) {
            if (state == "RUNNING") {
                headline.addView(Ui.button(ctx, "Pause") { command("Pause") { Repo.api.pause(id) } })
            } else {
                headline.addView(Ui.button(ctx, "Resume", primary = true) { command("Resume") { Repo.api.resume(id) } })
            }
            Ui.gap(ctx, headline, Ui.S)
            headline.addView(Ui.button(ctx, "Stop") { confirmStop(id) })
        } else if (awaitingPlate) {
            headline.addView(Ui.button(ctx, "Plate cleared", primary = true) {
                command("Clear plate") { Repo.api.clearPlate(id) }
            })
        }
        card.addView(headline, Ui.wide(ctx))

        // Only a print that is still moving gets a bar. A finished plate reads
        // 100%, so the old rule drew a full-width bar under every idle printer
        // that said nothing and cost the card a line — which on a screen this
        // short is a line the printer below it needed.
        if (running) {
            card.addView(Ui.space(ctx, Ui.S))
            val bar = Bar(ctx)
            bar.set(progress / 100.0, colour)
            card.addView(bar.view)
        }

        if (state == "PAUSE") runoutNotice(ctx, id, status)?.let { card.addView(it) }

        card.addView(Ui.space(ctx, Ui.M))
        val temps = Ui.row(ctx)
        temps.addView(Ui.stat(ctx, "Nozzle", Ui.temp(status.temp("nozzle"), status.temp("nozzle_target"))))
        Ui.gap(ctx, temps, Ui.XL)
        temps.addView(Ui.stat(ctx, "Bed", Ui.temp(status.temp("bed"), status.temp("bed_target"))))
        status.temp("chamber")?.let {
            Ui.gap(ctx, temps, Ui.XL)
            temps.addView(Ui.stat(ctx, "Chamber", Ui.temp(it, status.temp("chamber_target"))))
        }

        card.addView(temps, Ui.wide(ctx))

        // What is in the AMS, under the heat: together they answer "is this
        // machine ready to go". Bare colour dots did not — a dot cannot say
        // PLA, and it cannot say the spool is nearly out. On its own line
        // because a full unit is wider than what is left beside a chamber
        // temperature, and a slot that runs off the card is worse than a row.
        amsStrip(ctx, status)?.let {
            card.addView(Ui.space(ctx, 10))
            card.addView(it, Ui.wide(ctx))
        }

        Queue.nextFor(queued, id)?.let { next ->
            card.addView(Ui.space(ctx, Ui.S))
            val line = Ui.row(ctx)
            line.addView(Ui.tiny(ctx, "Next"))
            Ui.gap(ctx, line, Ui.S)
            val name = Ui.dim(ctx, Queue.itemName(next))
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            line.addView(name, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            Queue.waitingReason(next)?.let {
                val why = Ui.tiny(ctx, it)
                why.setTextColor(Ui.warn(ctx))
                line.addView(why)
            }
            card.addView(line, Ui.wide(ctx))
        }

        val faults = Hms.faults(status)
        if (faults.isNotEmpty()) {
            card.addView(Ui.space(ctx, Ui.M))
            card.addView(Ui.divider(ctx))
            card.addView(Ui.space(ctx, Ui.S))
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

        return card
    }

    /** The clock time a print with this many minutes left will finish at. */
    private fun finishTime(ctx: Context, minutesLeft: Int): String {
        val at = java.util.Calendar.getInstance()
        at.add(java.util.Calendar.MINUTE, minutesLeft)
        // The phone's own setting decides 24-hour or not.
        return android.text.format.DateFormat.getTimeFormat(ctx).format(at.time)
    }

    /**
     * Every slot on the printer, small: colour, what is in it, how much is left.
     *
     * A whole AMS has to fit beside the temperatures, so the material name is
     * dropped once there are more slots than one unit's worth — at that point
     * the colours and the percentages are what is being scanned anyway.
     */
    private fun amsStrip(ctx: Context, status: JSONObject): LinearLayout? {
        val trays = status.objects("ams").flatMap { it.objects("tray") } +
            status.objects("vt_tray")
        if (trays.isEmpty()) return null
        val room = trays.size <= 4

        val strip = Ui.row(ctx)
        trays.forEachIndexed { index, tray ->
            if (index > 0) Ui.gap(ctx, strip, Ui.M)
            val slot = Ui.row(ctx)
            slot.addView(Ui.swatch(ctx, tray.str("tray_color"), 12))
            Ui.gap(ctx, slot, 6)

            val bits = ArrayList<String>()
            if (room) {
                (tray.str("tray_sub_brands") ?: tray.str("tray_type"))?.let { bits.add(it) }
            }
            val remain = tray.optInt("remain", -1).takeIf { it in 0..100 }
            remain?.let { bits.add("$it%") }

            val label = Ui.tiny(ctx, when {
                bits.isNotEmpty() -> bits.joinToString(" ")
                room -> "Empty"
                else -> "—"
            })
            when {
                // The firmware's own "this one is loaded" bit, rather than
                // arithmetic on tray_now that an AMS HT would break.
                tray.int("state") == LOADED -> label.setTextColor(Ui.textColor(ctx))
                remain != null && remain <= LOW -> label.setTextColor(Ui.warn(ctx))
            }
            slot.addView(label)
            strip.addView(slot)
        }
        return strip
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

    private companion object {
        /** tray.state 11: the firmware says this slot is the one loaded. */
        const val LOADED = 11

        /** Per cent left at which a spool is worth flagging. */
        const val LOW = 10
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
