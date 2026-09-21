package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import org.json.JSONObject

/** The numbers Bambuddy keeps about everything printed so far. */
class StatsFragment : BaseFragment() {

    private lateinit var body: LinearLayout

    override fun build(ctx: Context) {
        val head = Ui.row(ctx)
        head.addView(Ui.big(ctx, "Statistics"))
        head.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
        head.addView(Ui.button(ctx, "Reload") { load() })
        content.addView(head, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Ui.space(ctx, 10))

        body = Ui.col(ctx)
        content.addView(body, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        load()
    }

    private fun load() {
        val ctx = context ?: return
        body.removeAllViews()
        body.addView(Ui.dim(ctx, "Loading…"))
        background({ Repo.api.statistics() }) { result ->
            result.onSuccess { render(it) }
            result.onFailure {
                body.removeAllViews()
                body.addView(Ui.dim(ctx, it.message ?: "Could not load the statistics"))
            }
        }
    }

    private fun render(stats: JSONObject) {
        val ctx = context ?: return
        body.removeAllViews()

        val total = stats.int("total_prints") ?: 0
        val ok = stats.int("successful_prints") ?: 0
        val failed = stats.int("failed_prints") ?: 0
        val rate = if (total > 0) (ok * 100.0 / total) else 0.0

        val headline = Ui.card(ctx)
        val row = Ui.row(ctx)
        row.addView(Ui.stat(ctx, "Prints", total.toString()))
        gap(ctx, row)
        row.addView(Ui.stat(ctx, "Succeeded", ok.toString(), Ui.good(ctx)))
        gap(ctx, row)
        row.addView(Ui.stat(ctx, "Failed", failed.toString(), if (failed > 0) Ui.bad(ctx) else null))
        gap(ctx, row)
        row.addView(Ui.stat(ctx, "Success rate", "${rate.toInt()}%"))
        headline.addView(row, wide(ctx))
        headline.addView(Ui.space(ctx, 8))
        val bar = Bar(ctx)
        bar.set(rate / 100.0, Ui.good(ctx))
        headline.addView(bar.view)
        body.addView(headline)
        body.addView(Ui.space(ctx, 8))

        val totals = Ui.card(ctx)
        val row2 = Ui.row(ctx)
        row2.addView(Ui.stat(ctx, "Print time", "${(stats.dbl("total_print_time_hours") ?: 0.0).toInt()} h"))
        gap(ctx, row2)
        row2.addView(Ui.stat(ctx, "Filament", "${((stats.dbl("total_filament_grams") ?: 0.0) / 1000.0).let {
            String.format("%.1f", it)
        }} kg"))
        gap(ctx, row2)
        row2.addView(Ui.stat(ctx, "Cost", String.format("%.2f", stats.dbl("total_cost") ?: 0.0)))
        stats.dbl("total_energy_kwh")?.takeIf { it > 0 }?.let {
            gap(ctx, row2)
            row2.addView(Ui.stat(ctx, "Energy", "${String.format("%.1f", it)} kWh"))
        }
        totals.addView(row2, wide(ctx))
        body.addView(totals)

        breakdown(ctx, "By filament", stats.optJSONObject("prints_by_filament_type"))
        breakdown(ctx, "By printer", stats.optJSONObject("prints_by_printer"))
    }

    private fun breakdown(ctx: Context, title: String, data: JSONObject?) {
        if (data == null || data.length() == 0) return
        body.addView(Ui.space(ctx, 8))
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, title))
        card.addView(Ui.space(ctx, 4))
        val keys = data.keys()
        var max = 1
        val entries = ArrayList<Pair<String, Int>>()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = data.optInt(key)
            entries.add(key to value)
            if (value > max) max = value
        }
        for ((key, value) in entries.sortedByDescending { it.second }.take(8)) {
            val line = Ui.row(ctx)
            line.addView(Ui.body(ctx, key), Ui.lp(ctx, 110, ViewGroup.LayoutParams.WRAP_CONTENT))
            val bar = Bar(ctx)
            bar.set(value.toDouble() / max)
            line.addView(bar.view, Ui.lp(ctx, 0, 6, 1f))
            line.addView(Ui.space(ctx, 1), Ui.lp(ctx, 8, 1))
            line.addView(Ui.dim(ctx, value.toString()))
            card.addView(line, wide(ctx))
            card.addView(Ui.space(ctx, 5))
        }
        body.addView(card)
    }

    private fun wide(ctx: Context) =
        Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun gap(ctx: Context, row: LinearLayout) {
        row.addView(Ui.space(ctx, 1), Ui.lp(ctx, 14, 1))
    }
}
