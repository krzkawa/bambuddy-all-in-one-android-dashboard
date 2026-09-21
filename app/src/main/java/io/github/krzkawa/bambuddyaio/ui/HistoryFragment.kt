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

/** Recent prints, newest first. */
class HistoryFragment : BaseFragment() {

    private lateinit var list: LinearLayout

    override fun build(ctx: Context) {
        val head = Ui.row(ctx)
        head.addView(Ui.big(ctx, "History"))
        head.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
        head.addView(Ui.button(ctx, "Reload") { load() })
        content.addView(head, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Ui.space(ctx, 10))

        list = Ui.col(ctx)
        content.addView(list, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        load()
    }

    private fun load() {
        val ctx = context ?: return
        list.removeAllViews()
        list.addView(Ui.dim(ctx, "Loading…"))
        background({ Repo.api.archives(40) }) { result ->
            result.onSuccess { render(it.objects()) }
            result.onFailure {
                list.removeAllViews()
                list.addView(Ui.dim(ctx, it.message ?: "Could not load the history"))
            }
        }
    }

    private fun render(rows: List<JSONObject>) {
        val ctx = context ?: return
        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(Ui.dim(ctx, "No prints recorded yet."))
            return
        }
        // The slim endpoint returns oldest-first on some servers, so sort here
        // rather than trusting the order.
        val sorted = rows.sortedByDescending { it.str("completed_at") ?: it.str("started_at") ?: "" }
        for (row in sorted.take(40)) {
            list.addView(card(ctx, row))
            list.addView(Ui.space(ctx, 6))
        }
    }

    private fun card(ctx: Context, row: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        val line = Ui.row(ctx)
        line.addView(Ui.swatch(ctx, row.str("filament_color"), 20))
        line.addView(Ui.space(ctx, 1), Ui.lp(ctx, 10, 1))

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

        val status = row.str("status")
        val colour = when (status) {
            "success", "completed" -> Ui.good(ctx)
            "failed" -> Ui.bad(ctx)
            else -> Ui.dimColor(ctx)
        }
        card.background = Ui.rounded(Ui.cardColor(ctx), 10, ctx, colour)
        card.addView(line, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }
}
