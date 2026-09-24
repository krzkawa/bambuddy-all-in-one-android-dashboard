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
        list.addView(empty(ctx, "Loading…"))
        // The full listing rather than the slim one: only it carries each
        // print's id, and without an id a row cannot be opened or reprinted.
        background({ Repo.api.archiveList(40) }) { result ->
            result.onSuccess { render(it.objects()) }
            result.onFailure {
                list.removeAllViews()
                list.addView(empty(ctx, it.message ?: "Could not load the history"))
            }
        }
    }

    private fun render(rows: List<JSONObject>) {
        val ctx = context ?: return
        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(empty(ctx, "No prints recorded yet."))
            return
        }
        // The slim endpoint returns oldest-first on some servers, so sort here
        // rather than trusting the order.
        val sorted = rows.sortedByDescending { it.str("completed_at") ?: it.str("started_at") ?: "" }
        for (row in sorted.take(40)) {
            list.addView(card(ctx, row), Ui.wide(ctx))
            list.addView(Ui.space(ctx, 6))
        }
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

        val info = Ui.col(ctx)
        info.addView(Ui.body(ctx, row.str("print_name") ?: "Print"))
        val bits = ArrayList<String>()
        when (row.str("status")) {
            "success", "completed" -> bits.add("Finished")
            "failed" -> bits.add("Failed")
            null -> {}
            else -> bits.add(row.str("status")!!)
        }
        row.str("filament_type")?.let { bits.add(it) }
        row.dbl("filament_used_grams")?.takeIf { it > 0 }?.let { bits.add("${it.toInt()} g") }
        row.int("actual_time_seconds")?.takeIf { it > 0 }?.let { bits.add(Ui.minutes(it / 60)) }
        info.addView(Ui.tiny(ctx, bits.joinToString(" · ")))
        line.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val when_ = (row.str("completed_at") ?: row.str("started_at"))?.take(16)?.replace('T', ' ')
        if (when_ != null) line.addView(Ui.tiny(ctx, when_))

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
}
