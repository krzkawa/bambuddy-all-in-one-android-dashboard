package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.net.ApiError
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

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
}
