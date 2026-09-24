package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Where the nozzle, bed and chamber have been, opened from Control.
 *
 * Opened on demand rather than always drawn: the live numbers on Control are
 * what he watches, and this is for the moment something looks wrong — a bed
 * that sagged mid-print, a nozzle that never reached its target.
 */
object HeaterHistory {

    private class Range(val label: String, val hours: Float)

    fun show(fragment: BaseFragment, printerId: Int) {
        val ctx = fragment.context ?: return
        val status = Repo.statuses.value[printerId]

        val ranges = ArrayList<Range>()
        // "This print" frames the chart around the job on the plate, which is
        // the question nine times out of ten.
        if (status?.optString("state") == "RUNNING" || status?.optString("state") == "PAUSE") {
            Trends.elapsedMinutes(status.dbl("progress"), status.int("remaining_time"))?.let {
                ranges.add(Range("This print", (it + 10) / 60f))
            }
        }
        ranges.add(Range("1 h", 1f))
        ranges.add(Range("6 h", 6f))
        ranges.add(Range("24 h", 24f))

        val root = Ui.col(ctx)
        val pad = Ui.dp(ctx, Ui.L)
        root.setPadding(pad, pad, pad, Ui.dp(ctx, Ui.XS))
        val top = Ui.row(ctx)
        top.addView(Ui.title(ctx, "Heater history"))
        Ui.push(ctx, top)
        val pickerSlot = LinearLayout(ctx)
        top.addView(pickerSlot)
        root.addView(top, Ui.wide(ctx))
        root.addView(Ui.space(ctx, Ui.M))

        val chart = Chart(ctx)
        chart.emptyText = "Loading…"
        root.addView(chart, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, CHART_DP))
        root.addView(Ui.space(ctx, Ui.S))
        val note = Ui.tiny(ctx, "Dashed: the target it was set to")
        root.addView(note)

        var job: Job? = null
        fun load(index: Int) {
            pickerSlot.removeAllViews()
            pickerSlot.addView(Ui.segmented(ctx, ranges.map { it.label }, index) { load(it) })
            val range = ranges[index]
            chart.emptyText = "Loading…"
            job?.cancel()
            job = fragment.viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { Repo.api.heaterHistory(printerId, ceil(range.hours).toInt()) }
                }
                if (!fragment.isAdded) return@launch
                result.onSuccess {
                    val now = System.currentTimeMillis()
                    draw(ctx, chart, Trends.heaterHistory(it, now), range.hours, now)
                }
                result.onFailure { chart.emptyText = it.message ?: "History not available" }
            }
        }
        load(0)

        val dialog = AlertDialog.Builder(ctx)
            .setView(root)
            .setPositiveButton("Close", null)
            .setOnDismissListener { job?.cancel() }
            .show()
        // Landscape has the width to spare; a chart squeezed into the default
        // dialog width is a chart that needs squinting at.
        val metrics = ctx.resources.displayMetrics
        dialog.window?.setLayout((metrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun draw(ctx: Context, chart: Chart, series: List<Trends.HeaterSeries>, hours: Float, now: Long) {
        chart.emptyText = "No readings in this range"
        val lines = ArrayList<Chart.Line>()
        var top = 0f
        for (s in series) {
            val colour = shade(ctx, s.kind)
            if (s.hasTarget) {
                // An idle heater's target is 0; drawn, it would hug the floor.
                val targets = FloatArray(s.targets.size) { i -> s.targets[i].takeIf { it > 0f } ?: Float.NaN }
                lines.add(Chart.Line(s.hours, targets, colour, widthDp = 1f, dashed = true))
            }
            val last = s.last
            val label = Trends.heaterName(s.kind) + (last?.let { " ${it.toInt()}°" } ?: "")
            lines.add(Chart.Line(s.hours, s.values, colour, label = label))
            top = maxOf(top, s.max ?: 0f)
        }
        val yMax = Trends.roundUp(top + 10f, 50f)
        chart.setLines(
            lines, -hours, 0f, 0f, yMax,
            Trends.ticks(0f, yMax, 4),
            Trends.clockLabels(now, hours, ZoneId.systemDefault(), 6),
            // One reading a minute; a five-minute hole is the printer or the
            // server having been off.
            gapX = 5f / 60f,
            yFormat = { "${it.toInt()}°" }
        )
    }

    /**
     * Neutral shades, brightest for the heater that matters most. Green, amber
     * and red already mean something on this screen, and a legend is one more
     * thing to read — each line carries its own name at its end instead.
     */
    private fun shade(ctx: Context, kind: String): Int = when (kind) {
        "nozzle" -> Ui.textColor(ctx)
        "nozzle_2" -> Ui.accent(ctx)
        "bed" -> Ui.dimColor(ctx)
        else -> Ui.faintColor(ctx)
    }

    private const val CHART_DP = 180
}
