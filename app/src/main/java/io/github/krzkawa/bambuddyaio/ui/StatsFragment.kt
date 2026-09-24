package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What has been printed, drawn: how many prints a day and how they ended,
 * how much filament went through, and what it was.
 *
 * The span strip picks days, weeks or months; every figure on the screen is
 * for that span, so the success rate beside the chart is the success rate of
 * the bars in it.
 */
class StatsFragment : BaseFragment() {

    private lateinit var spanStrip: LinearLayout
    private lateinit var body: LinearLayout
    private var span = Trends.Span.DAYS

    private class Loaded(val stats: JSONObject, val allTime: JSONObject, val runs: JSONArray)

    override fun build(ctx: Context) {
        screenAction("Reload") { load() }
        spanStrip = Ui.row(ctx)
        content.addView(spanStrip, Ui.wide(ctx))
        content.addView(Ui.space(ctx, Ui.M))
        body = Ui.col(ctx)
        content.addView(body, Ui.wide(ctx))
        load()
    }

    private fun drawSpanStrip(ctx: Context) {
        spanStrip.removeAllViews()
        val spans = Trends.Span.values()
        spanStrip.addView(Ui.segmented(ctx, spans.map { it.label }, span.ordinal) { index ->
            if (spans[index] != span) {
                span = spans[index]
                load()
            }
        })
    }

    private fun load() {
        val ctx = context ?: return
        drawSpanStrip(ctx)
        body.removeAllViews()
        body.addView(waiting(ctx))
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val chosen = span
        // The server filters by UTC date; one day early covers the phone's
        // zone either side of it, and the buckets drop anything outside.
        val from = Trends.firstDay(chosen, today).minusDays(1).toString()
        background({
            Loaded(Repo.api.statisticsSince(from), Repo.api.statistics(), Repo.api.runsSince(from))
        }) { result ->
            if (chosen != span) return@background
            result.onSuccess { render(it, chosen, today, zone) }
            result.onFailure {
                body.removeAllViews()
                body.addView(failed(ctx, it, "the statistics") { load() })
            }
        }
    }

    private fun render(data: Loaded, span: Trends.Span, today: LocalDate, zone: ZoneId) {
        val ctx = context ?: return
        body.removeAllViews()

        val buckets = Trends.buckets(data.runs, span, today, zone)
        val labels = Trends.barLabels(buckets, span, 4)
        val per = when (span) {
            Trends.Span.DAYS -> "day"
            Trends.Span.WEEKS -> "week"
            Trends.Span.MONTHS -> "month"
        }

        // Prints per bucket beside the success rate: the chart and the number
        // that sums it up, side by side where landscape has the room.
        val first = Ui.row(ctx)
        first.gravity = android.view.Gravity.TOP
        first.isBaselineAligned = false
        first.addView(printsCard(ctx, buckets, labels, per), Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
        Ui.gap(ctx, first, Ui.M)
        first.addView(headlineCard(ctx, data.stats, data.allTime), Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        body.addView(first, wide(ctx))
        body.addView(Ui.space(ctx, Ui.M))

        val second = Ui.row(ctx)
        second.gravity = android.view.Gravity.TOP
        second.isBaselineAligned = false
        second.addView(filamentCard(ctx, buckets, labels, per), Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
        Ui.gap(ctx, second, Ui.M)
        val since = Trends.firstDay(span, today)
        second.addView(
            materialCard(ctx, Trends.gramsByMaterial(data.runs, since, zone)),
            Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        )
        body.addView(second, wide(ctx))

        printerCard(ctx, data.stats)?.let {
            body.addView(Ui.space(ctx, Ui.M))
            body.addView(it, wide(ctx))
        }
        Ui.arrive(body)
    }

    // -------------------------------------------------------------- the cards

    private fun printsCard(
        ctx: Context,
        buckets: List<Trends.Bucket>,
        labels: List<Pair<Int, String>>,
        per: String
    ): LinearLayout {
        val card = Ui.card(ctx)
        val top = Ui.row(ctx)
        top.addView(Ui.heading(ctx, "Prints per $per"))
        Ui.push(ctx, top)
        // Three colours in the bars, so the three are named once, in a line.
        key(ctx, top, Ui.good(ctx), "Finished")
        key(ctx, top, Ui.bad(ctx), "Failed")
        key(ctx, top, Ui.faintColor(ctx), "Stopped")
        card.addView(top, wide(ctx))
        card.addView(Ui.space(ctx, Ui.XS))

        val most = buckets.maxOfOrNull { it.prints } ?: 0
        val step = Trends.niceStep(maxOf(most, 1) / 3f).coerceAtLeast(1f)
        val yMax = Trends.roundUp(most.toFloat(), step)
        val chart = Chart(ctx)
        chart.emptyText = "Nothing printed in this span"
        if (most > 0) {
            chart.setBars(
                buckets.map { b ->
                    Chart.Stack(floatArrayOf(
                        b.counts[Trends.OK].toFloat(),
                        b.counts[Trends.FAILED].toFloat(),
                        b.counts[Trends.OTHER].toFloat()
                    ))
                },
                intArrayOf(Ui.good(ctx), Ui.bad(ctx), Ui.faintColor(ctx)),
                yMax,
                Trends.ticks(0f, yMax, 3),
                labels
            )
        }
        card.addView(chart, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, 176))
        return card
    }

    private fun key(ctx: Context, row: LinearLayout, colour: Int, word: String) {
        Ui.gap(ctx, row, Ui.M)
        row.addView(Ui.dot(ctx, colour))
        Ui.gap(ctx, row, Ui.XS)
        row.addView(Ui.tiny(ctx, word))
    }

    private fun headlineCard(ctx: Context, stats: JSONObject, allTime: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        val total = stats.int("total_prints") ?: 0
        val ok = stats.int("successful_prints") ?: 0
        val failed = stats.int("failed_prints") ?: 0
        val stopped = stats.int("cancelled_prints") ?: 0
        val rate = if (total > 0) ok * 100.0 / total else 0.0

        card.addView(Ui.display(ctx, if (total > 0) "${rate.toInt()}%" else "—"))
        card.addView(Ui.dim(ctx, "of prints finished"))
        card.addView(Ui.space(ctx, Ui.S))
        val bar = Bar(ctx)
        bar.set(rate / 100.0, if (failed > 0 && rate < 80) Ui.warn(ctx) else Ui.good(ctx))
        card.addView(bar.view)
        card.addView(Ui.space(ctx, Ui.XS))
        val counts = buildList {
            add("$ok done")
            add("$failed failed")
            if (stopped > 0) add("$stopped stopped")
        }.joinToString(" · ")
        card.addView(Ui.tiny(ctx, counts))
        card.addView(Ui.space(ctx, Ui.M))

        val row1 = Ui.row(ctx)
        row1.addView(Ui.stat(ctx, "Print time", "${(stats.dbl("total_print_time_hours") ?: 0.0).toInt()} h"),
            Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(Ui.stat(ctx, "Filament", Trends.weight(stats.dbl("total_filament_grams") ?: 0.0)),
            Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row1, wide(ctx))
        card.addView(Ui.space(ctx, Ui.S))
        val row2 = Ui.row(ctx)
        row2.addView(Ui.stat(ctx, "Cost", String.format(Locale.ROOT, "%.2f", stats.dbl("total_cost") ?: 0.0)),
            Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val kwh = stats.dbl("total_energy_kwh") ?: 0.0
        row2.addView(Ui.stat(ctx, "Energy", if (kwh > 0) String.format(Locale.ROOT, "%.1f kWh", kwh) else "—"),
            Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row2, wide(ctx))

        card.addView(Ui.space(ctx, Ui.M))
        val all = allTime.int("total_prints") ?: 0
        card.addView(Ui.tiny(ctx,
            "All time: $all prints · ${Trends.weight(allTime.dbl("total_filament_grams") ?: 0.0)}"))
        return card
    }

    private fun filamentCard(
        ctx: Context,
        buckets: List<Trends.Bucket>,
        labels: List<Pair<Int, String>>,
        per: String
    ): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Filament per $per"))
        card.addView(Ui.space(ctx, Ui.XS))
        val most = buckets.maxOfOrNull { it.grams }?.toFloat() ?: 0f
        val step = Trends.niceStep(maxOf(most, 1f) / 3f)
        val yMax = Trends.roundUp(most, step)
        val chart = Chart(ctx)
        chart.emptyText = "No filament used in this span"
        if (most > 0f) {
            chart.setBars(
                buckets.map { Chart.Stack(floatArrayOf(it.grams.toFloat())) },
                intArrayOf(Ui.dimColor(ctx)),
                yMax,
                Trends.ticks(0f, yMax, 3),
                labels,
                yFormat = { Trends.weight(it.toDouble()) }
            )
        } else {
            chart.setBars(emptyList(), IntArray(0), 1f, emptyList(), emptyList())
        }
        card.addView(chart, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, 120))
        return card
    }

    private fun materialCard(ctx: Context, materials: List<Pair<String, Double>>): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "By material"))
        if (materials.isEmpty()) {
            card.addView(Ui.dim(ctx, "Nothing recorded"))
            return card
        }
        val most = materials.first().second.coerceAtLeast(1.0)
        for ((material, grams) in materials.take(6)) {
            card.addView(Ui.space(ctx, Ui.XS))
            val line = Ui.row(ctx)
            line.addView(Ui.body(ctx, material))
            Ui.push(ctx, line)
            line.addView(Ui.dim(ctx, Trends.weight(grams)))
            card.addView(line, wide(ctx))
            val bar = Bar(ctx, 4)
            bar.set(grams / most)
            card.addView(bar.view)
        }
        return card
    }

    /** Prints per printer, named — the server keys them by id. */
    private fun printerCard(ctx: Context, stats: JSONObject): LinearLayout? {
        val data = stats.optJSONObject("prints_by_printer") ?: return null
        if (data.length() < 2) return null
        val names = stats.optJSONObject("printer_names")
        val live = Repo.printers.value.associate { it.optInt("id").toString() to it.optString("name") }
        val entries = ArrayList<Pair<String, Int>>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val name = live[id]?.takeIf { it.isNotBlank() }
                ?: names?.optString(id)?.takeIf { it.isNotBlank() }
                ?: "Printer $id"
            entries.add(name to data.optInt(id))
        }
        entries.sortByDescending { it.second }
        val most = entries.first().second.coerceAtLeast(1)

        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "By printer"))
        for ((name, count) in entries.take(8)) {
            card.addView(Ui.space(ctx, Ui.XS))
            val line = Ui.row(ctx)
            line.addView(Ui.body(ctx, name), Ui.lp(ctx, 140, ViewGroup.LayoutParams.WRAP_CONTENT))
            val bar = Bar(ctx, 4)
            bar.set(count.toDouble() / most)
            line.addView(bar.view, Ui.lp(ctx, 0, 4, 1f))
            Ui.gap(ctx, line, Ui.M)
            line.addView(Ui.dim(ctx, "$count"), Ui.lp(ctx, 36, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(line, wide(ctx))
        }
        return card
    }
}
