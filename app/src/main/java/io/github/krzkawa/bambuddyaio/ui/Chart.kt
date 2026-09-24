package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * One hand-drawn chart: lines over time, or stacked bars.
 *
 * No charting library: on a phone this old a library costs more to load than
 * the handful of paths it would draw. Everything is worked out once, when the
 * data or the size changes, so drawing a frame allocates nothing and walks
 * paths that already exist.
 *
 * It is read from across a room, so it says little: a few gridlines, the value
 * at each end of a line written beside it rather than in a legend, and colour
 * only where the palette already gives colour a meaning.
 */
class Chart(ctx: Context) : View(ctx) {

    /**
     * A series drawn as a line. [xs] and [ys] are the same length; a NaN in
     * [ys] is a reading that did not happen, and breaks the line.
     */
    class Line(
        val xs: FloatArray,
        val ys: FloatArray,
        val color: Int,
        val widthDp: Float = 2f,
        val dashed: Boolean = false,
        /** Written beside the line's right end, in its colour. */
        val label: String? = null
    )

    /** From [from] upward the line is drawn in [color] instead of its own. */
    class Band(val from: Float, val color: Int)

    /** One bar: its parts from the bottom up, each in the colour at the same index. */
    class Stack(val parts: FloatArray)

    private val density = resources.displayMetrics.density
    private fun px(dp: Float) = dp * density
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val grid = Paint().apply {
        color = Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.stroke)
        strokeWidth = max(1f, px(1f))
        style = Paint.Style.STROKE
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.faintColor(ctx)
        textSize = sp(11f)
    }
    private val endText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dash = DashPathEffect(floatArrayOf(px(4f), px(4f)), 0f)

    // What was asked for.
    private var lines: List<Line> = emptyList()
    private var bands: List<Band> = emptyList()
    private var stacks: List<Stack> = emptyList()
    private var stackColors: IntArray = IntArray(0)
    private var xMin = 0f
    private var xMax = 1f
    private var yMin = 0f
    private var yMax = 1f
    private var yTicks: List<Float> = emptyList()
    private var tickLabels: List<String> = emptyList()
    private var tickWidths = FloatArray(0)
    private var xLabelWidths = FloatArray(0)
    private var yFormat: (Float) -> String = { it.toInt().toString() }
    private var xLabels: List<Pair<Float, String>> = emptyList()
    private var gapX = Float.MAX_VALUE

    /** Said in the middle of the chart when there is nothing to draw. */
    var emptyText: String? = null
        set(value) { field = value; invalidate() }

    // What gets drawn: rebuilt only when the data or the size changes.
    private val paths = ArrayList<Path>()
    private val ends = ArrayList<End>()
    private val rects = ArrayList<FloatArray>()
    private var plotLeft = 0f
    private var plotTop = 0f
    private var plotRight = 0f
    private var plotBottom = 0f
    private var laidOut = false

    private class End(val text: String, val x: Float, var y: Float, val color: Int)

    /**
     * Lines over a shared x range.
     *
     * [gapX] is the widest step between two readings that still counts as one
     * line; anything wider is drawn as a break, because a straight line across
     * an hour the server was off says something that did not happen.
     */
    fun setLines(
        lines: List<Line>,
        xMin: Float,
        xMax: Float,
        yMin: Float,
        yMax: Float,
        yTicks: List<Float>,
        xLabels: List<Pair<Float, String>>,
        bands: List<Band> = emptyList(),
        gapX: Float = Float.MAX_VALUE,
        yFormat: (Float) -> String = { it.toInt().toString() }
    ) {
        this.lines = lines
        this.stacks = emptyList()
        this.xMin = xMin
        this.xMax = if (xMax > xMin) xMax else xMin + 1f
        this.yMin = yMin
        this.yMax = if (yMax > yMin) yMax else yMin + 1f
        this.yTicks = yTicks
        this.xLabels = xLabels
        this.bands = bands.sortedBy { it.from }
        this.gapX = gapX
        this.yFormat = yFormat
        rebuild()
    }

    /** Stacked bars, one per bucket, evenly spaced. [xLabels] index the bars. */
    fun setBars(
        stacks: List<Stack>,
        colors: IntArray,
        yMax: Float,
        yTicks: List<Float>,
        xLabels: List<Pair<Int, String>>,
        yFormat: (Float) -> String = { it.toInt().toString() }
    ) {
        this.lines = emptyList()
        this.bands = emptyList()
        this.stacks = stacks
        this.stackColors = colors
        this.xMin = 0f
        this.xMax = max(1, stacks.size).toFloat()
        this.yMin = 0f
        this.yMax = if (yMax > 0f) yMax else 1f
        this.yTicks = yTicks
        this.xLabels = xLabels.map { (i, s) -> (i + 0.5f) to s }
        this.yFormat = yFormat
        rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun xPx(x: Float) = plotLeft + (x - xMin) / (xMax - xMin) * (plotRight - plotLeft)
    private fun yPx(y: Float) =
        plotBottom - (y.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * (plotBottom - plotTop)

    private fun rebuild() {
        paths.clear()
        ends.clear()
        rects.clear()
        laidOut = width > 0 && height > 0
        if (!laidOut) {
            invalidate()
            return
        }

        tickLabels = yTicks.map(yFormat)
        tickWidths = FloatArray(tickLabels.size) { axisText.measureText(tickLabels[it]) }
        xLabelWidths = FloatArray(xLabels.size) { axisText.measureText(xLabels[it].second) }
        val tickWidth = tickWidths.maxOrNull() ?: 0f
        val endWidth = lines.mapNotNull { it.label }.maxOfOrNull { endText.measureText(it) } ?: 0f
        plotLeft = paddingLeft + (if (tickWidth > 0f) tickWidth + px(6f) else 0f)
        plotRight = width - paddingRight - (if (endWidth > 0f) endWidth + px(8f) else px(2f))
        plotTop = paddingTop + axisText.textSize / 2f + px(2f)
        plotBottom = height - paddingBottom -
            (if (xLabels.isNotEmpty()) axisText.textSize + px(6f) else px(2f))
        if (plotRight <= plotLeft || plotBottom <= plotTop) {
            laidOut = false
            invalidate()
            return
        }

        for (line in lines) {
            paths.add(linePath(line))
            val last = lastReading(line)
            if (line.label != null && last != null) {
                ends.add(End(line.label, plotRight + px(6f), yPx(last), line.color))
            }
        }
        spreadEnds()

        if (stacks.isNotEmpty()) {
            val slot = (plotRight - plotLeft) / stacks.size
            // Thin gaps between bars: at 30 bars on a phone, the gap is what
            // lets the eye count them.
            val gap = min(px(3f), slot * 0.3f)
            stacks.forEachIndexed { i, stack ->
                var base = 0f
                val left = plotLeft + i * slot + gap / 2f
                val right = plotLeft + (i + 1) * slot - gap / 2f
                stack.parts.forEachIndexed { part, value ->
                    if (value > 0f) {
                        val top = yPx(base + value)
                        val bottom = yPx(base)
                        rects.add(floatArrayOf(left, top, right, bottom, part.toFloat()))
                        base += value
                    }
                }
            }
        }
        invalidate()
    }

    /**
     * The line as one path, with no more than two points per pixel column.
     *
     * A day of heater readings is 1440 points and the chart is a few hundred
     * pixels wide; each column keeps its lowest and highest reading so a spike
     * that lasted one minute is still there to see.
     */
    private fun linePath(line: Line): Path {
        val path = Path()
        var drawing = false
        var lastX = Float.NaN
        var column = Int.MIN_VALUE
        var lo = 0f
        var hi = 0f
        var colX = 0f

        fun flush() {
            if (column == Int.MIN_VALUE) return
            if (!drawing) {
                path.moveTo(colX, yPx(lo))
                drawing = true
            } else {
                path.lineTo(colX, yPx(lo))
            }
            if (hi != lo) path.lineTo(colX, yPx(hi))
        }

        for (i in line.xs.indices) {
            val x = line.xs[i]
            val y = line.ys.getOrElse(i) { Float.NaN }
            if (y.isNaN() || x < xMin || x > xMax) continue
            if (!lastX.isNaN() && x - lastX > gapX) {
                flush()
                column = Int.MIN_VALUE
                drawing = false
            }
            lastX = x
            val px = xPx(x)
            val col = px.toInt()
            if (col != column) {
                flush()
                column = col
                colX = px
                lo = y
                hi = y
            } else {
                lo = min(lo, y)
                hi = max(hi, y)
            }
        }
        flush()
        return path
    }

    private fun lastReading(line: Line): Float? {
        for (i in line.xs.indices.reversed()) {
            val y = line.ys.getOrElse(i) { Float.NaN }
            if (!y.isNaN() && line.xs[i] in xMin..xMax) return y
        }
        return null
    }

    /** Keeps the end labels from sitting on top of each other. */
    private fun spreadEnds() {
        if (ends.size < 2) return
        val step = endText.textSize + px(2f)
        ends.sortBy { it.y }
        for (i in 1 until ends.size) {
            if (ends[i].y - ends[i - 1].y < step) ends[i].y = ends[i - 1].y + step
        }
        val overflow = ends.last().y - (height - paddingBottom).toFloat()
        if (overflow > 0f) ends.forEach { it.y -= overflow }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!laidOut) return

        // Gridlines with their values at the left.
        for (i in yTicks.indices) {
            val y = yPx(yTicks[i])
            canvas.drawLine(plotLeft, y, plotRight, y, grid)
            canvas.drawText(tickLabels[i], plotLeft - px(6f) - tickWidths[i], y + axisText.textSize / 3f, axisText)
        }
        val labelY = height - paddingBottom - px(1f)
        // Right to left, so the newest date always shows and any label that
        // would run into the one after it is the one left out.
        var room = width.toFloat()
        for (i in xLabels.indices.reversed()) {
            val (x, text) = xLabels[i]
            val w = xLabelWidths[i]
            val at = (xPx(x) - w / 2f).coerceIn(plotLeft - px(2f), width - paddingRight - w)
            if (at + w > room) continue
            canvas.drawText(text, at, labelY, axisText)
            room = at - px(10f)
        }

        for (r in rects) {
            fill.color = stackColors.getOrElse(r[4].toInt()) { Ui.dimColor(context) }
            canvas.drawRect(r[0], r[1], r[2], r[3], fill)
        }

        lines.forEachIndexed { i, line ->
            val path = paths.getOrNull(i) ?: return@forEachIndexed
            stroke.strokeWidth = px(line.widthDp)
            stroke.pathEffect = if (line.dashed) dash else null
            if (bands.isEmpty()) {
                stroke.color = line.color
                canvas.drawPath(path, stroke)
            } else {
                // The same path once per band, each clipped to its own height,
                // so the line changes colour exactly where it crosses 40%.
                var below = plotBottom + px(4f)
                var colour = line.color
                for (band in bands) {
                    val top = yPx(band.from)
                    drawClipped(canvas, path, top, below, colour)
                    below = top
                    colour = band.color
                }
                drawClipped(canvas, path, plotTop - px(4f), below, colour)
            }
        }
        stroke.pathEffect = null

        for (end in ends) {
            endText.color = end.color
            canvas.drawText(end.text, end.x, end.y + endText.textSize / 3f, endText)
        }

        if (lines.isEmpty() && stacks.isEmpty() || paths.isNotEmpty() && paths.all { it.isEmpty }) {
            emptyText?.let {
                val w = axisText.measureText(it)
                canvas.drawText(it, (plotLeft + plotRight - w) / 2f, (plotTop + plotBottom) / 2f, axisText)
            }
        }
    }

    private fun drawClipped(canvas: Canvas, path: Path, top: Float, bottom: Float, colour: Int) {
        if (bottom <= top) return
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), bottom)
        stroke.color = colour
        canvas.drawPath(path, stroke)
        canvas.restore()
    }
}
