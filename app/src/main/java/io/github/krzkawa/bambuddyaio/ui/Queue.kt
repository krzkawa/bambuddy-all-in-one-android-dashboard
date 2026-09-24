package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.net.ApiError
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject
import java.time.Instant

/**
 * Reading what the queue says back when a print is asked for.
 *
 * Starting a staged item is the one control call whose refusal is a question
 * rather than an error: the server re-checks the assigned spools and answers
 * 409 with what each slot is short of, expecting the caller to put that to the
 * user and ask again (`print_queue.py:2416`). Everything here is the parsing
 * and the wording of that answer, kept Android-free so it can be tested.
 */
object Queue {

    /** The `detail.code` the server uses for a refusal that can be overridden. */
    const val INSUFFICIENT_FILAMENT = "insufficient_filament"

    /** One slot that cannot cover its share of the print. */
    data class Shortfall(
        val slotId: Int,
        val amsId: Int?,
        val trayId: Int?,
        val filament: String?,
        val requiredGrams: Double,
        /** Null when the server could not work out what is left on the spool. */
        val remainingGrams: Double?
    ) {
        /** What to call the slot, preferring the unit-and-tray pair the server named. */
        val where: String
            get() = if (amsId != null && trayId != null) {
                Assign.slotName(amsId, trayId)
            } else {
                Assign.trayWords(slotId)
            }

        /** One line he can act on: where, what, and how much is missing. */
        val line: String
            get() {
                val what = filament?.let { " ($it)" }.orEmpty()
                if (remainingGrams == null) {
                    return "$where$what needs ${grams(requiredGrams)}, " +
                        "and the spool does not say how much is left"
                }
                val short = (requiredGrams - remainingGrams).coerceAtLeast(0.0)
                return "$where$what is ${grams(short)} short — " +
                    "${grams(requiredGrams)} needed, ${grams(remainingGrams)} left"
            }
    }

    /**
     * The slots a refused start named, or null when the refusal was something
     * else entirely — a 409 the app does not understand is not a prompt.
     */
    fun shortfalls(error: ApiError): List<Shortfall>? {
        if (error.code != 409) return null
        val detail = error.detail ?: return null
        if (detail.str("code") != INSUFFICIENT_FILAMENT) return null
        val rows = detail.objects("deficit").map { read(it) }
        return rows.ifEmpty { null }
    }

    private fun read(row: JSONObject): Shortfall = Shortfall(
        slotId = row.int("slot_id") ?: -1,
        amsId = row.int("ams_id"),
        trayId = row.int("tray_id"),
        filament = row.str("filament_type"),
        requiredGrams = row.dbl("required_grams") ?: 0.0,
        remainingGrams = row.dbl("remaining_grams")
    )

    /** The whole question, ready to put in a dialog. */
    fun shortfallMessage(rows: List<Shortfall>): String =
        rows.joinToString("\n\n") { it.line }

    /** Grams, rounded the way a person reads a spool: whole grams, no decimals. */
    fun grams(value: Double): String = "${Math.round(value)} g"

    /** What to call a queued item on screen. */
    fun itemName(item: JSONObject): String =
        item.str("archive_name") ?: item.str("library_file_name") ?: "Queued print"

    /**
     * Whether Start would do anything for this item.
     *
     * The route refuses anything that is not still pending with a 400
     * (`print_queue.py:2397`), so an item already printing or done gets no
     * button rather than a button that always fails.
     */
    fun canStart(item: JSONObject): Boolean = item.str("status") == "pending"

    /**
     * Statuses that mean an item is over and done with.
     *
     * Written as the list of endings rather than as a list of live states so
     * that a status this app has never heard of still shows up: a queue that
     * silently drops the item he is waiting on is worse than one that shows a
     * row too many.
     */
    private val FINISHED = setOf(
        "completed", "complete", "done", "finished", "success", "succeeded",
        "failed", "error", "cancelled", "canceled", "stopped", "aborted",
        "skipped", "removed", "archived", "expired"
    )

    /** Live states, which sort above the ones still waiting their turn. */
    private val ACTIVE = setOf(
        "printing", "running", "active", "in_progress", "started",
        "starting", "sending", "preparing", "uploading", "paused"
    )

    private fun statusOf(item: JSONObject): String =
        item.str("status")?.trim()?.lowercase().orEmpty()

    /** True once an item has finished, one way or another. */
    fun isFinished(item: JSONObject): Boolean = statusOf(item) in FINISHED

    /** True while the printer is actually working on it. */
    fun isActive(item: JSONObject): Boolean = statusOf(item) in ACTIVE

    /**
     * The queue as it is worth looking at: what is printing now, then what is
     * lined up behind it. Everything that has already been and gone belongs to
     * the History screen, which is where it now stays.
     */
    fun upcoming(items: List<JSONObject>): List<JSONObject> =
        items.filterNot { isFinished(it) }
            .sortedBy { if (isActive(it)) 0 else 1 }

    /**
     * The next thing lined up for one printer, for the Printers screen.
     *
     * An item the server has not tied to a printer counts for whichever
     * printer is asking: it is going to land on one of them, and on the usual
     * single-printer setup it is going to land on this one.
     */
    fun nextFor(items: List<JSONObject>, printerId: Int): JSONObject? =
        items.filterNot { isFinished(it) || isActive(it) }
            .firstOrNull { (it.int("printer_id") ?: printerId) == printerId }

    /**
     * Why an item is sitting there, when the server has said so.
     *
     * `filament_short` is the scheduler's own flag from its last pass, which is
     * worth showing before he taps: it is the same check that will refuse him.
     */
    fun waitingReason(item: JSONObject): String? = when {
        item.str("waiting_reason") != null -> item.str("waiting_reason")
        item.optBoolean("filament_short") -> "Not enough filament"
        item.optBoolean("manual_start") -> "Waiting for you to start it"
        else -> null
    }

    // ------------------------------------------------------------ editing

    /**
     * When a pending item is due to start, or null when it goes as soon as
     * the printer is free.
     *
     * Bambuddy's own page treats a time more than six months out as a
     * placeholder rather than a plan, so this does too.
     */
    fun scheduledAt(item: JSONObject, now: Instant = Instant.now()): Instant? {
        val at = PrintPlan.parseInstant(item.str("scheduled_time")) ?: return null
        if (at.isAfter(now.plusSeconds(180L * 24 * 3600))) return null
        return at
    }

    /** True when the item is waiting for a time that has not come yet. */
    fun isScheduled(item: JSONObject, now: Instant = Instant.now()): Boolean =
        !item.optBoolean("manual_start") && (scheduledAt(item, now)?.isAfter(now) ?: false)

    /**
     * The pending items that share [item]'s place in line, in order.
     *
     * The server numbers positions per printer, and items not tied to one form
     * their own line, so moving an item only ever reshuffles its neighbours.
     */
    fun lineOf(items: List<JSONObject>, item: JSONObject): List<JSONObject> {
        val printer = item.int("printer_id")
        return items.filter { canStart(it) && it.int("printer_id") == printer }
            .sortedBy { it.optInt("position") }
    }

    /**
     * The renumbering that moves [item] [by] places (negative is earlier), as
     * id-to-position pairs for `/queue/reorder`, or null when it cannot move
     * that way.
     *
     * The whole line is renumbered from 1 rather than two positions swapped:
     * positions on a real queue have gaps and repeats after deletes, and a
     * swap of two equal numbers changes nothing.
     */
    fun moved(items: List<JSONObject>, item: JSONObject, by: Int): List<Pair<Int, Int>>? {
        val line = lineOf(items, item).toMutableList()
        val from = line.indexOfFirst { it.optInt("id") == item.optInt("id") }
        if (from < 0) return null
        val to = (from + by).coerceIn(0, line.size - 1)
        if (to == from) return null
        line.add(to, line.removeAt(from))
        return line.mapIndexed { index, it -> it.optInt("id") to index + 1 }
    }

    /** One row on the Queue screen: a single item, or every pending copy of one batch. */
    data class Row(val items: List<JSONObject>) {
        val first: JSONObject get() = items.first()
        val isBatch: Boolean get() = items.size > 1
    }

    /**
     * Folds pending copies of a batch into one row at the place of the first.
     *
     * Ten copies of a bracket as ten identical rows push everything else off
     * the screen, and he thinks of them as one job anyway.
     */
    fun rows(items: List<JSONObject>): List<Row> {
        val out = ArrayList<Row>()
        val batchRow = HashMap<Int, Int>()
        val grouped = HashMap<Int, MutableList<JSONObject>>()
        for (item in items) {
            val batch = item.int("batch_id")
            if (batch == null || !canStart(item)) {
                out.add(Row(listOf(item)))
                continue
            }
            val existing = batchRow[batch]
            if (existing == null) {
                val list = mutableListOf(item)
                grouped[batch] = list
                batchRow[batch] = out.size
                out.add(Row(list))
            } else {
                grouped.getValue(batch).add(item)
            }
        }
        return out.map { Row(it.items.toList()) }
    }

    /** What to call a row: the batch's own name when it has one. */
    fun rowName(row: Row): String =
        if (row.isBatch) {
            val base = row.first.str("batch_name") ?: itemName(row.first)
            if (base.contains("×")) "$base · ${row.items.size} left" else "$base ×${row.items.size}"
        } else {
            itemName(row.first)
        }
}
