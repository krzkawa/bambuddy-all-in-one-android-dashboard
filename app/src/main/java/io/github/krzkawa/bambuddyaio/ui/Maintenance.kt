package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Api
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.bool
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bambuddy's maintenance counters — wipe the nozzle, grease the rods — and a
 * tap to say it has been done.
 *
 * The server does the counting, by print hours or by days, and says which
 * items are due and which are close (`maintenance.py`). This only reads that
 * and puts the due ones where he will see them.
 */
object Maintenance {

    data class Item(
        val id: Int,
        val printerId: Int,
        val name: String,
        val enabled: Boolean,
        val due: Boolean,
        val soon: Boolean,
        /** "hours" counts print hours; "days" counts the calendar. */
        val byDays: Boolean,
        val hoursUntil: Double?,
        val daysUntil: Double?,
        val everDone: Boolean
    )

    private val _items = MutableStateFlow<Map<Int, List<Item>>>(emptyMap())
    val items: StateFlow<Map<Int, List<Item>>> = _items.asStateFlow()

    private var loadedAt = 0L

    fun parse(overview: JSONArray): Map<Int, List<Item>> {
        val out = HashMap<Int, List<Item>>()
        for (printer in overview.objects()) {
            val printerId = printer.optInt("printer_id", -1)
            if (printerId < 0) continue
            out[printerId] = printer.objects("maintenance_items").map { i ->
                Item(
                    id = i.optInt("id", -1),
                    printerId = printerId,
                    name = i.str("maintenance_type_name") ?: "Maintenance",
                    enabled = i.bool("enabled"),
                    due = i.bool("is_due"),
                    soon = i.bool("is_warning"),
                    byDays = i.str("interval_type") == "days",
                    hoursUntil = i.dbl("hours_until_due"),
                    daysUntil = i.dbl("days_until_due"),
                    everDone = i.str("last_performed_at") != null
                )
            }.filter { it.id >= 0 }
        }
        return out
    }

    /** Due now, most overdue first. Disabled items are his way of saying never. */
    fun due(items: List<Item>): List<Item> =
        items.filter { it.enabled && it.due }.sortedBy { remaining(it) }

    fun soon(items: List<Item>): List<Item> =
        items.filter { it.enabled && it.soon && !it.due }.sortedBy { remaining(it) }

    /** Everything he has not switched off, the ones that need him first. */
    fun ordered(items: List<Item>): List<Item> =
        items.filter { it.enabled }.sortedWith(compareBy({ !it.due }, { !it.soon }, { remaining(it) }))

    private fun remaining(item: Item): Double =
        (if (item.byDays) item.daysUntil else item.hoursUntil) ?: 0.0

    /** "12 h overdue", "in 3 days", "never done". */
    fun whenDue(item: Item): String {
        if (item.byDays && !item.everDone) return "never done"
        val left = remaining(item)
        val amount = abs(left).roundToInt()
        val unit = if (item.byDays) (if (amount == 1) "day" else "days") else "h"
        val span = "$amount $unit"
        return when {
            left <= 0 && amount == 0 -> "due now"
            left <= 0 -> "$span overdue"
            else -> "in $span"
        }
    }

    /**
     * Refreshes every printer's counters. Blocking. The counts move by print
     * hours and days, so there is no call for asking often.
     */
    @Synchronized
    fun load(api: Api, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - loadedAt < MIN_GAP_MS) return
        loadedAt = now
        try {
            _items.value = parse(api.maintenanceOverview())
        } catch (e: Exception) {
            // An older server, or a key without the permission: nothing to nag about.
            // Try again sooner than a full interval, in case it was the wifi.
            loadedAt = now - MIN_GAP_MS + RETRY_MS
        }
    }

    fun signature(printerId: Int): String =
        _items.value[printerId]?.let { list -> due(list).joinToString { it.name } + "|" + soon(list).size }.orEmpty()

    // ------------------------------------------------------------------- views

    /**
     * One line naming what is due on this printer, or what is close when
     * nothing is. Null when there is nothing to say, which is most of the time.
     */
    fun line(ctx: Context, printerId: Int): LinearLayout? {
        val list = _items.value[printerId] ?: return null
        val due = due(list)
        val soon = soon(list)
        if (due.isEmpty() && soon.isEmpty()) return null

        val line = Ui.row(ctx)
        line.addView(Ui.tiny(ctx, if (due.isNotEmpty()) "Due" else "Soon"))
        Ui.gap(ctx, line, Ui.S)
        val names = Ui.dim(ctx, (due.ifEmpty { soon }).joinToString(" · ") { it.name })
        names.maxLines = 1
        names.ellipsize = android.text.TextUtils.TruncateAt.END
        if (due.isNotEmpty()) names.setTextColor(Ui.warn(ctx))
        line.addView(names, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(Ui.quiet(ctx, "Mark done") { choose(ctx, printerId, attentionOnly = true) })
        return line
    }

    /**
     * The printer's counters as a list; picking one asks, then resets it.
     *
     * [attentionOnly] narrows it to what is due or close, which is what the
     * card's line is about.
     */
    fun choose(ctx: Context, printerId: Int, attentionOnly: Boolean) {
        val all = _items.value[printerId].orEmpty()
        val list = if (attentionOnly) due(all) + soon(all) else ordered(all)
        if (list.isEmpty()) {
            Ui.toast(ctx, if (all.isEmpty()) "No maintenance counters on this printer" else "Nothing is due")
            return
        }
        val labels = list.map { "${it.name} — ${whenDue(it)}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Maintenance on ${Repo.printerName(printerId)}")
            .setItems(labels) { _, which -> confirm(ctx, list[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirm(ctx: Context, item: Item) {
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("${item.name} done?")
            .setMessage("Its counter starts again from now.")
            .setPositiveButton("Mark done") { _, _ ->
                val app = ctx.applicationContext
                Repo.action(item.name, {
                    Repo.api.maintenancePerform(item.id)
                    load(Repo.api, force = true)
                }) { Ui.toast(app, it.replace(" sent", " marked done")) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private const val MIN_GAP_MS = 10 * 60_000L
    private const val RETRY_MS = 60_000L

    /** How often a visible screen asks; [load] decides whether to actually go. */
    const val POLL_MS = 60_000L
}
