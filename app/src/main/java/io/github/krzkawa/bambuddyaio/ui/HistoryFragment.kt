package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/** Recent prints, newest first. Tap one to see it and print it again. */
class HistoryFragment : BaseFragment() {

    private lateinit var list: LinearLayout

    override fun build(ctx: Context) {
        screenAction("Reload") { load() }
        list = Ui.col(ctx)
        content.addView(list, Ui.wide(ctx))
        load()
    }

    private fun load() {
        val ctx = context ?: return
        list.removeAllViews()
        list.addView(waiting(ctx))
        // The full listing rather than the slim one: only it carries each
        // print's id, and without an id a row cannot be opened or reprinted.
        background({ Repo.api.archiveList(40) }) { result ->
            result.onSuccess { render(it.objects()) }
            result.onFailure { error ->
                list.removeAllViews()
                list.addView(failed(ctx, error, "the history") { load() })
            }
        }
    }

    private fun render(rows: List<JSONObject>) {
        val ctx = context ?: return
        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(
                empty(ctx, "No prints recorded yet.", "Finished prints appear here on their own.")
            )
            return
        }
        // The slim endpoint returns oldest-first on some servers, so sort here
        // rather than trusting the order.
        val sorted = rows.sortedByDescending { it.str("completed_at") ?: it.str("started_at") ?: "" }
        val cards = sorted.take(40).map { card(ctx, it) as android.view.View }
        // Reading order is across then down, which is how the newest print
        // stays top left where the eye starts.
        val grid = Ui.grid(ctx, cards, columns(ctx))
        list.addView(grid, Ui.wide(ctx))
        Ui.arrive(grid)
    }

    private fun card(ctx: Context, row: JSONObject): LinearLayout {
        val card = Ui.inset(ctx)
        card.background = Ui.rounded(Ui.cardColor(ctx), 10, ctx)
        val line = Ui.row(ctx)

        // How it went, as a dot. Outlining the whole row in red for a failure
        // made a page of history read as a page of alarms.
        val status = row.str("status")
        val colour = when (status) {
            "success", "completed" -> Ui.good(ctx)
            "failed" -> Ui.bad(ctx)
            else -> Ui.faintColor(ctx)
        }
        line.addView(Ui.dot(ctx, colour))
        Ui.gap(ctx, line, 10)
        line.addView(Ui.swatch(ctx, row.str("filament_color"), 20))
        Ui.gap(ctx, line, Ui.M)

        val narrow = columns(ctx) > 1

        val info = Ui.col(ctx)
        val name = Ui.body(ctx, row.str("print_name") ?: "Print")
        name.maxLines = 1
        // Middle, not end: these are all "bracket_v3_plate.gcode.3mf", so the
        // tail is what tells two of them apart.
        name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        info.addView(name)

        val stamp = row.str("completed_at") ?: row.str("started_at")
        val bits = ArrayList<String>()
        // Half a screen wide the date drops onto the second line rather than
        // taking width off the name, and the day is the part worth keeping:
        // what time a print finished four days ago is not a thing he checks.
        if (narrow) shortWhen(stamp)?.let { bits.add(it) }
        // The dot has already said how it went, so the word is only spelled out
        // where there is room for it.
        if (!narrow) when (row.str("status")) {
            "success", "completed" -> bits.add("Finished")
            "failed" -> bits.add("Failed")
            null -> {}
            else -> bits.add(row.str("status")!!)
        }
        row.str("filament_type")?.let { bits.add(it) }
        row.dbl("filament_used_grams")?.takeIf { it > 0 }?.let { bits.add("${it.toInt()} g") }
        row.int("actual_time_seconds")?.takeIf { it > 0 }?.let { bits.add(Ui.minutes(it / 60)) }
        val meta = Ui.tiny(ctx, bits.joinToString(" · "))
        meta.maxLines = 1
        meta.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(meta)
        line.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!narrow) {
            val when_ = stamp?.take(16)?.replace('T', ' ')
            if (when_ != null) {
                val t = Ui.tiny(ctx, when_)
                t.maxLines = 1
                line.addView(t)
            }
        }

        val id = row.int("id")
        if (id != null) {
            Ui.gap(ctx, line, Ui.S)
            line.addView(Ui.tiny(ctx, "›"))
            card.background = Ui.pressable(
                ctx, Ui.rounded(Ui.cardColor(ctx), 10, ctx),
                Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.pressed), 10
            )
            card.isClickable = true
            card.setOnClickListener { PrintFlow.open(this, ArchiveFragment.of(id)) }
        }

        card.addView(line, Ui.wide(ctx))
        return card
    }

    /** "21 Sep 14:05" from an ISO stamp, and today's prints by the clock alone. */
    private fun shortWhen(stamp: String?): String? {
        val s = stamp?.takeIf { it.length >= 16 } ?: return stamp
        if (s.startsWith(today())) return s.substring(11, 16)
        val month = MONTHS.getOrNull((s.substring(5, 7).toIntOrNull() ?: 0) - 1) ?: return s.take(10)
        return "${s.substring(8, 10).trimStart('0')} $month ${s.substring(11, 16)}"
    }

    /** Today as the server writes it. java.time needs API 26; this runs on 24. */
    private fun today(): String {
        val c = java.util.Calendar.getInstance()
        return String.format(
            java.util.Locale.US, "%04d-%02d-%02d",
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private companion object {
        val MONTHS = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
    }
}
