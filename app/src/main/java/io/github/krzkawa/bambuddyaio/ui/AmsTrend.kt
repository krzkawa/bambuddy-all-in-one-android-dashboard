package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import java.time.ZoneId
import java.util.Locale

/**
 * How damp one AMS unit has been over the last day, drawn under the unit.
 *
 * A single humidity reading does not say much: 38% after a dry cycle is fine,
 * 38% and climbing since last night means the desiccant is spent. The line is
 * coloured the same way the reading above it is — green up to 40%, amber to
 * 60%, red past it — so the answer is visible before any number is read.
 */
class AmsTrend(ctx: Context) {

    /** When the last request went out, so a rebuilt screen does not ask again. */
    var requestedAt = 0L

    private val caption = Ui.tiny(ctx, "Last 24 h")
    private val range = Ui.tiny(ctx, "")
    private val chart = Chart(ctx)

    val view: LinearLayout = Ui.col(ctx).apply {
        val top = Ui.row(ctx)
        top.addView(caption)
        Ui.push(ctx, top)
        top.addView(range)
        addView(top, Ui.wide(ctx))
        addView(Ui.space(ctx, Ui.XS))
        addView(chart, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, CHART_DP))
        chart.emptyText = "Loading…"
    }

    fun show(ctx: Context, history: Trends.AmsHistory, nowMs: Long) {
        val words = ArrayList<String>()
        if (history.minHumidity != null && history.maxHumidity != null) {
            val unit = if (history.levelOnly) "" else "%"
            words.add("${history.minHumidity.toInt()}–${history.maxHumidity.toInt()}$unit")
        }
        if (history.minTemp != null && history.maxTemp != null) {
            words.add(String.format(Locale.ROOT, "%.0f–%.0f°C", history.minTemp, history.maxTemp))
        }
        range.text = words.joinToString(" · ")
        chart.emptyText = "No readings recorded yet"

        val labels = Trends.clockLabels(nowMs, SPAN_HOURS, ZoneId.systemDefault(), 5)
        if (history.levelOnly) {
            // Bambu's 1–5 level has no thresholds worth colouring by.
            chart.setLines(
                listOf(Chart.Line(history.hours, history.humidity, Ui.dimColor(ctx))),
                -SPAN_HOURS, 0f, 0f, 5f, listOf(1f, 3f, 5f), labels,
                gapX = GAP_HOURS
            )
            return
        }
        // Always wide enough to show both thresholds, and no wider: an axis
        // from zero leaves the line squashed into the top third.
        val top = maxOf(70f, Trends.roundUp((history.maxHumidity ?: 0f) + 5f, 10f))
        val bottom = (kotlin.math.floor(((history.minHumidity ?: 0f) - 5f) / 10f) * 10f).coerceIn(0f, 30f)
        chart.setLines(
            listOf(Chart.Line(history.hours, history.humidity, Ui.good(ctx))),
            -SPAN_HOURS, 0f, bottom, top,
            // The two lines that mean something, and nothing else.
            listOf(HUMIDITY_GOOD, HUMIDITY_FAIR),
            labels,
            bands = listOf(Chart.Band(HUMIDITY_GOOD, Ui.warn(ctx)), Chart.Band(HUMIDITY_FAIR, Ui.bad(ctx))),
            gapX = GAP_HOURS,
            yFormat = { "${it.toInt()}%" }
        )
    }

    fun failed(message: String?) {
        chart.emptyText = message ?: "History not available"
    }

    companion object {
        const val SPAN_HOURS = 24f
        const val CHART_DP = 88

        /** Readings arrive every five minutes; three missed in a row is a break. */
        const val GAP_HOURS = 20f / 60f

        /** Refetch no more often than the server records. */
        const val REFRESH_MS = 5 * 60_000L

        // The same thresholds the reading above the chart is coloured by.
        const val HUMIDITY_GOOD = 40f
        const val HUMIDITY_FAIR = 60f
    }
}
