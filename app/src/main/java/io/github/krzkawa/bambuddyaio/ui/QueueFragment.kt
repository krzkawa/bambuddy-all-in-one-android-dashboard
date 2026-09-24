package io.github.krzkawa.bambuddyaio.ui

import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What is lined up to print next, and the way to add to it.
 *
 * The top-bar action opens the file library. Pending items can be moved up and
 * down their printer's line, held, scheduled, started or removed; the one on
 * the printer can be stopped. Copies of one batch fold into a single row.
 */
class QueueFragment : BaseFragment() {

    private lateinit var list: LinearLayout
    private var items: List<JSONObject> = emptyList()
    private var busy = false

    override fun build(ctx: Context) {
        screenAction("Add print") { PrintFlow.open(this, LibraryFragment.of(null)) }
        list = Ui.col(ctx)
        content.addView(list, Ui.wide(ctx))
        list.addView(empty(ctx, "Loading…"))
        // The queue changes by itself — the scheduler starts things, prints
        // finish — so it is read again while the screen is up rather than
        // needing a Reload button.
        observe(ticker(REFRESH_MS)) { load() }
    }

    private fun load() {
        val ctx = context ?: return
        if (busy) return
        background({ Repo.api.queue() }) { result ->
            result.onSuccess {
                items = it.objects()
                render()
            }
            result.onFailure {
                list.removeAllViews()
                list.addView(empty(ctx, it.message ?: "Could not load the queue"))
            }
        }
    }

    private fun render() {
        val ctx = context ?: return
        list.removeAllViews()
        // Finished prints live on the History screen. This one answers "what is
        // the printer doing and what is it doing next", so a week of completed
        // jobs pushing that off the top is just noise.
        val shown = Queue.upcoming(items)
        if (shown.isEmpty()) {
            list.addView(empty(ctx, "Nothing waiting. Tap Add print to send one."))
            return
        }
        for (row in Queue.rows(shown)) {
            list.addView(row(ctx, row), Ui.wide(ctx))
            list.addView(Ui.space(ctx, 6))
        }
    }

    private fun row(ctx: Context, row: Queue.Row): LinearLayout {
        val item = row.first
        val card = Ui.inset(ctx)
        card.background = Ui.rounded(Ui.cardColor(ctx), 10, ctx)
        val line = Ui.row(ctx)

        // A dot ahead of the name says which one is on the printer right now.
        val active = Queue.isActive(item)
        line.addView(Ui.dot(ctx, if (active) Ui.good(ctx) else Ui.faintColor(ctx)))
        Ui.gap(ctx, line, 10)

        val info = Ui.col(ctx)
        val name = Queue.rowName(row)
        info.addView(if (active) Ui.title(ctx, name) else Ui.body(ctx, name))
        val bits = ArrayList<String>()
        item.str("status")?.let { bits.add(Ui.stateWord(it)) }
        item.str("printer_name")?.let { bits.add(it) }
        item.int("plate_id")?.takeIf { it > 1 }?.let { bits.add("plate $it") }
        item.int("print_time_seconds")?.takeIf { it > 0 }?.let { bits.add(Ui.minutes(it / 60)) }
        item.dbl("filament_used_grams")?.takeIf { it > 0 }?.let { bits.add("${it.toInt()} g") }
        info.addView(Ui.tiny(ctx, bits.joinToString(" · ")))
        // A start time is what he wants to know about a scheduled print, and it
        // is not a warning, so it is said plainly rather than in amber.
        Queue.scheduledAt(item)?.takeIf { Queue.isScheduled(item) }?.let {
            info.addView(Ui.dim(ctx, "Starts " + PrintPlan.sayWhen(it, LocalDateTime.now(), ZoneId.systemDefault())))
        }
        // The reason it is sitting there is the one thing on the row worth
        // colouring: it is usually the same check that will refuse a Start.
        Queue.waitingReason(item)?.let {
            val why = Ui.tiny(ctx, it)
            why.setTextColor(Ui.warn(ctx))
            info.addView(why)
        }
        line.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (Queue.canStart(item)) {
            pendingActions(ctx, line, row, name)
        } else if (item.str("status") == "printing") {
            line.addView(Ui.quiet(ctx, "Stop") { confirmStop(ctx, item, name) })
        }
        card.addView(line, Ui.wide(ctx))
        return card
    }

    private fun pendingActions(ctx: Context, line: LinearLayout, row: Queue.Row, name: String) {
        val item = row.first
        // A batch moves as its copies, which the server numbers one by one, so
        // only a single item gets the arrows.
        if (!row.isBatch) {
            val queueLine = Queue.lineOf(items, item)
            val at = queueLine.indexOfFirst { it.optInt("id") == item.optInt("id") }
            if (queueLine.size > 1) {
                line.addView(arrow(ctx, "↑", at > 0) { move(item, -1) })
                line.addView(arrow(ctx, "↓", at in 0 until queueLine.lastIndex) { move(item, 1) })
            }
        }
        line.addView(Ui.quiet(ctx, "More") { more(ctx, row, name) })
        Ui.gap(ctx, line, Ui.XS)
        line.addView(Ui.button(ctx, "Start", primary = true) { confirmStart(ctx, item, name) })
    }

    private fun arrow(ctx: Context, label: String, enabled: Boolean, onClick: () -> Unit) =
        Ui.quiet(ctx, label, onClick).also {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.3f
            it.minWidth = Ui.dp(ctx, 40)
            it.textSize = 18f
        }

    private fun move(item: JSONObject, by: Int) {
        val order = Queue.moved(items, item, by) ?: return
        // Drawn in its new place straight away; the server's answer follows.
        val positions = order.toMap()
        items = items.map { i ->
            positions[i.optInt("id")]?.let { JSONObject(i.toString()).put("position", it) } ?: i
        }.sortedWith(compareBy<JSONObject>({ it.int("printer_id") ?: -1 }, { it.optInt("position") }))
        render()
        busy = true
        background({ Repo.api.queueReorder(order) }) { result ->
            busy = false
            result.onFailure { toast(it.message ?: "Could not move it") }
            load()
        }
    }

    private fun more(ctx: Context, row: Queue.Row, name: String) {
        val item = row.first
        val held = item.optBoolean("manual_start")
        val scheduled = Queue.isScheduled(item)
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (!row.isBatch && Queue.lineOf(items, item).size > 2) {
            options.add("Move to the front" to { move(item, -1000) })
        }
        options.add((if (scheduled) "Change the start time" else "Start later at…") to { schedule(ctx, row) })
        if (scheduled) options.add("Start when the printer is free" to { update(row, JSONObject().put("scheduled_time", JSONObject.NULL)) })
        if (!held) options.add("Wait for me to start it" to { update(row, JSONObject().put("manual_start", true)) })
        val printers = Repo.printers.value.filter { it.optInt("id", -1) >= 0 }
        if (printers.size > 1) options.add("Send to another printer" to { reassign(ctx, row, printers) })
        options.add((if (row.isBatch) "Remove all ${row.items.size}" else "Remove") to { confirmRemove(ctx, row, name) })
        AlertDialog.Builder(ctx)
            .setTitle(name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Sets a start time. A scheduled item must not also be held, or it would
     * sit past its time waiting for a tap nobody knows is needed.
     */
    private fun schedule(ctx: Context, row: Queue.Row) {
        val now = LocalDateTime.now()
        val current = Queue.scheduledAt(row.first)?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }
            ?: now.plusHours(1)
        TimePickerDialog(ctx, { _, hour, minute ->
            val at = PrintPlan.nextAt(hour, minute, LocalDateTime.now(), ZoneId.systemDefault())
            update(row, JSONObject().put("scheduled_time", at.toString()).put("manual_start", false)) {
                Repo.notice("${Queue.rowName(row)} starts ${PrintPlan.sayWhen(at, LocalDateTime.now(), ZoneId.systemDefault())}")
            }
        }, current.hour, current.minute, DateFormat.is24HourFormat(ctx)).show()
    }

    private fun reassign(ctx: Context, row: Queue.Row, printers: List<JSONObject>) {
        val names = printers.map { it.optString("name").ifBlank { "Printer ${it.optInt("id")}" } }
        AlertDialog.Builder(ctx)
            .setTitle("Send to which printer?")
            .setItems(names.toTypedArray()) { _, which ->
                // The tray mapping was chosen against the old printer's AMS, so
                // it goes too, and Bambuddy matches again against the new one.
                update(row, JSONObject().put("printer_id", printers[which].optInt("id")).put("ams_mapping", JSONObject.NULL))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Applies one edit to every item in the row, then reads the queue back. */
    private fun update(row: Queue.Row, payload: JSONObject, then: (() -> Unit)? = null) {
        busy = true
        background({ row.items.forEach { Repo.api.queueUpdate(it.optInt("id"), payload) } }) { result ->
            busy = false
            result.onSuccess { then?.invoke() }
            result.onFailure { toast(it.message ?: "Could not change it") }
            load()
        }
    }

    private fun confirmRemove(ctx: Context, row: Queue.Row, name: String) {
        AlertDialog.Builder(ctx)
            .setTitle(if (row.isBatch) "Remove all ${row.items.size} from the queue?" else "Remove from the queue?")
            .setMessage(name)
            .setPositiveButton("Remove") { _, _ ->
                busy = true
                background({ row.items.forEach { Repo.api.queueRemove(it.optInt("id")) } }) { result ->
                    busy = false
                    result.onSuccess { toast("Removed") }
                    result.onFailure { toast(it.message ?: "Could not remove it") }
                    load()
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    /** Stopping throws away the print on the plate, so it is asked for twice. */
    private fun confirmStop(ctx: Context, item: JSONObject, name: String) {
        val printer = item.str("printer_name") ?: item.int("printer_id")?.let { Repo.printerName(it) }
        AlertDialog.Builder(ctx)
            .setTitle("Stop this print?")
            .setMessage(if (printer == null) name else "$name\n\nOn $printer. It cannot be resumed.")
            .setPositiveButton("Stop") { _, _ ->
                background({ Repo.api.queueStop(item.optInt("id")) }) { result ->
                    result.onSuccess {
                        Repo.notice("Stopped $name")
                        Repo.refresh()
                    }
                    result.onFailure { toast(it.message ?: "Could not stop it") }
                    load()
                }
            }
            .setNegativeButton("Keep printing", null)
            .show()
    }

    /** A print is a machine moving in another room, so it is asked for twice. */
    private fun confirmStart(ctx: Context, item: JSONObject, name: String) {
        val printer = item.str("printer_name")
            ?: item.int("printer_id")?.let { Repo.printerName(it) }
        AlertDialog.Builder(ctx)
            .setTitle("Start this print?")
            .setMessage(if (printer == null) name else "$name\n\nOn $printer")
            .setPositiveButton("Start") { _, _ ->
                PrintFlow.start(this, item.optInt("id"), name) { load() }
            }
            .setNegativeButton("Not yet", null)
            .show()
    }

    companion object {
        private const val REFRESH_MS = 15_000L
    }
}
