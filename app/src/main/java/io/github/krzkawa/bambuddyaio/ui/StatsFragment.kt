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
        screenAction("Reload") { load() }
        body = Ui.col(ctx)
        content.addView(body, Ui.wide(ctx))
        load()
    }

    private fun load() {
        val ctx = context ?: return
        body.removeAllViews()
        body.addView(waiting(ctx))
        background({ Repo.api.statistics() }) { result ->
            result.onSuccess { render(it) }
            result.onFailure { error ->
                body.removeAllViews()
                body.addView(failed(ctx, error, "the statistics") { load() })
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
        // The success rate is the headline; the counts behind it are the small
        // print, so they are set as small print rather than as four equals.
        val crown = Ui.row(ctx)
        crown.addView(Ui.display(ctx, "${rate.toInt()}%"))
        Ui.gap(ctx, crown, Ui.M)
        val breakdownWords = Ui.col(ctx)
        breakdownWords.addView(Ui.body(ctx, "of prints finished"))
        breakdownWords.addView(Ui.dim(ctx, "$ok done · $failed failed · $total in all"))
        crown.addView(breakdownWords, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headline.addView(crown, wide(ctx))
        headline.addView(Ui.space(ctx, Ui.M))
        val bar = Bar(ctx)
        bar.set(rate / 100.0, if (failed > 0 && rate < 80) Ui.warn(ctx) else Ui.good(ctx))
        headline.addView(bar.view)
        body.addView(headline)
        body.addView(Ui.space(ctx, Ui.M))

        val totals = Ui.card(ctx)
        val row2 = Ui.row(ctx)
        row2.addView(Ui.stat(ctx, "Print time", "${(stats.dbl("total_print_time_hours") ?: 0.0).toInt()} h"))
        gap(ctx, row2)
        val kilos = (stats.dbl("total_filament_grams") ?: 0.0) / 1000.0
        row2.addView(Ui.stat(ctx, "Filament", String.format("%.1f kg", kilos)))
        gap(ctx, row2)
        row2.addView(Ui.stat(ctx, "Cost", String.format("%.2f", stats.dbl("total_cost") ?: 0.0)))
        stats.dbl("total_energy_kwh")?.takeIf { it > 0 }?.let {
            gap(ctx, row2)
            row2.addView(Ui.stat(ctx, "Energy", "${String.format("%.1f", it)} kWh"))
        }
        totals.addView(row2, wide(ctx))
        body.addView(totals)

        val breakdowns = listOfNotNull(
            breakdown(ctx, "By filament", stats.optJSONObject("prints_by_filament_type")),
            breakdown(ctx, "By printer", stats.optJSONObject("prints_by_printer"))
        )
        if (breakdowns.isNotEmpty()) {
            body.addView(Ui.space(ctx, Ui.M))
            body.addView(Ui.grid(ctx, breakdowns, columns(ctx), Ui.M), wide(ctx))
        }
        Ui.arrive(body)
    }

    private fun breakdown(ctx: Context, title: String, data: JSONObject?): LinearLayout? {
        if (data == null || data.length() == 0) return null
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, title))
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
            val name = Ui.dim(ctx, key)
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            line.addView(name, Ui.lp(ctx, 72, ViewGroup.LayoutParams.WRAP_CONTENT))
            val bar = Bar(ctx, 4)
            bar.set(value.toDouble() / max)
            line.addView(bar.view, Ui.lp(ctx, 0, 4, 1f))
            Ui.gap(ctx, line, Ui.M)
            line.addView(Ui.dim(ctx, value.toString()), Ui.lp(ctx, 28, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(line, wide(ctx))
            card.addView(Ui.space(ctx, Ui.S))
        }
        return card
    }

    private fun gap(ctx: Context, row: LinearLayout) = Ui.gap(ctx, row, Ui.XL)
}
