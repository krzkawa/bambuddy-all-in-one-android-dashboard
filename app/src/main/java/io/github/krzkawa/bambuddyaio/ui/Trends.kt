package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Turns Bambuddy's history into the numbers a [Chart] draws.
 *
 * Kept free of views so it can be tested on its own: the charts are where a
 * wrong bucket or an off-by-a-timezone day looks right in code and wrong on
 * the wall.
 */
object Trends {

    // ------------------------------------------------------------------ time

    /**
     * Epoch millis from one of the server's timestamps.
     *
     * SQLite hands datetimes back without their zone, so a bare
     * "2026-09-24T01:16:22.123456" is UTC — the server only ever writes UTC.
     */
    fun parseTime(iso: String?): Long? {
        val s = iso?.trim()?.takeIf { it.isNotEmpty() }?.replace(' ', 'T') ?: return null
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }

    /** Hours from [nowMs] to [ms]: negative in the past, which is where a chart's x runs. */
    fun hoursBefore(ms: Long, nowMs: Long): Float = (ms - nowMs) / 3_600_000f

    /**
     * Clock-time labels across the last [spanHours], on round hours, in the
     * phone's own time zone. Positions are in [hoursBefore] units.
     */
    fun clockLabels(nowMs: Long, spanHours: Float, zone: ZoneId, count: Int = 5): List<Pair<Float, String>> {
        val step = STEPS_HOURS.firstOrNull { spanHours / it <= count } ?: STEPS_HOURS.last()
        val stepMs = (step * 3_600_000).toLong()
        val startMs = nowMs - (spanHours * 3_600_000).toLong()
        // Align to the zone's clock, not to UTC, so "06:00" lands on six o'clock.
        val offsetMs = zone.rules.getOffset(Instant.ofEpochMilli(nowMs)).totalSeconds * 1000L
        var t = ceilDiv(startMs + offsetMs, stepMs) * stepMs - offsetMs
        val out = ArrayList<Pair<Float, String>>()
        while (t <= nowMs) {
            val local = Instant.ofEpochMilli(t).atZone(zone)
            out.add(hoursBefore(t, nowMs) to String.format(Locale.ROOT, "%02d:%02d", local.hour, local.minute))
            t += stepMs
        }
        return out
    }

    private val STEPS_HOURS = floatArrayOf(0.25f, 0.5f, 1f, 2f, 3f, 4f, 6f, 12f, 24f, 48f)

    private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)

    // ------------------------------------------------------------------ scale

    /** A few round gridline values from [lo] to [hi], neither of which need be round. */
    fun ticks(lo: Float, hi: Float, most: Int = 4): List<Float> {
        if (hi <= lo) return listOf(lo)
        val step = niceStep((hi - lo) / most)
        val out = ArrayList<Float>()
        var v = ceil(lo / step) * step
        while (v <= hi + step * 1e-3f) {
            out.add(v)
            v += step
        }
        return out
    }

    /** 1, 2, 2.5 or 5 times a power of ten: steps a person counts in. */
    fun niceStep(raw: Float): Float {
        if (raw <= 0f) return 1f
        val magnitude = Math.pow(10.0, floor(Math.log10(raw.toDouble()))).toFloat()
        val fraction = raw / magnitude
        val nice = when {
            fraction <= 1f -> 1f
            fraction <= 2f -> 2f
            fraction <= 2.5f -> 2.5f
            fraction <= 5f -> 5f
            else -> 10f
        }
        return nice * magnitude
    }

    /** The top of a chart's scale: the next round step above [value]. */
    fun roundUp(value: Float, step: Float): Float =
        if (value <= 0f) step else ceil(value / step) * step

    // ---------------------------------------------------------- AMS humidity

    /** An AMS unit's last day, ready to draw. */
    class AmsHistory(
        val hours: FloatArray,
        val humidity: FloatArray,
        val temperature: FloatArray,
        /** True when the unit only reports Bambu's 1–5 level, not a percentage. */
        val levelOnly: Boolean,
        val minHumidity: Float?,
        val maxHumidity: Float?,
        val minTemp: Float?,
        val maxTemp: Float?
    ) {
        val isEmpty get() = hours.isEmpty()
    }

    /**
     * Reads `GET /ams-history/{printer}/{ams}`.
     *
     * `humidity_raw` is the real percentage where the AMS sends one; the plain
     * `humidity` is either that same percentage or the 1–5 level, depending on
     * the unit, and Bambuddy stores whichever it got.
     */
    fun amsHistory(json: JSONObject, nowMs: Long): AmsHistory {
        val points = json.objects("data")
        val hours = FloatArray(points.size)
        val humidity = FloatArray(points.size)
        val temperature = FloatArray(points.size)
        var anyRaw = false
        var n = 0
        for (p in points) {
            val at = parseTime(p.str("recorded_at")) ?: continue
            val raw = p.dbl("humidity_raw")
            if (raw != null) anyRaw = true
            hours[n] = hoursBefore(at, nowMs)
            humidity[n] = (raw ?: p.dbl("humidity"))?.toFloat() ?: Float.NaN
            temperature[n] = p.dbl("temperature")?.toFloat() ?: Float.NaN
            n++
        }
        val h = humidity.copyOf(n)
        val present = h.filter { !it.isNaN() }
        val levelOnly = !anyRaw && present.isNotEmpty() && present.all { it in 0f..5f }
        val t = temperature.copyOf(n).filter { !it.isNaN() }
        return AmsHistory(
            hours.copyOf(n), h, temperature.copyOf(n), levelOnly,
            present.minOrNull(), present.maxOrNull(), t.minOrNull(), t.maxOrNull()
        )
    }

    // ------------------------------------------------------------- heaters

    class HeaterSeries(val kind: String, val hours: FloatArray, val values: FloatArray, val targets: FloatArray) {
        val last: Float? get() = values.lastOrNull { !it.isNaN() }
        val max: Float? get() = (values + targets).filter { !it.isNaN() }.maxOrNull()
        /** A target that was ever set above zero is worth drawing; an idle heater's zero is not. */
        val hasTarget: Boolean get() = targets.any { !it.isNaN() && it > 0f }
    }

    /** Reads `GET /printer-sensor-history/{printer}`, in the order nozzle, bed, chamber. */
    fun heaterHistory(json: JSONObject, nowMs: Long): List<HeaterSeries> {
        val out = ArrayList<HeaterSeries>()
        for (series in json.objects("series")) {
            val kind = series.str("sensor_kind") ?: continue
            val points = series.objects("data")
            val hours = FloatArray(points.size)
            val values = FloatArray(points.size)
            val targets = FloatArray(points.size)
            var n = 0
            for (p in points) {
                val at = parseTime(p.str("recorded_at")) ?: continue
                hours[n] = hoursBefore(at, nowMs)
                values[n] = p.dbl("value")?.toFloat() ?: Float.NaN
                targets[n] = p.dbl("target")?.toFloat() ?: Float.NaN
                n++
            }
            if (n == 0) continue
            out.add(HeaterSeries(kind, hours.copyOf(n), values.copyOf(n), targets.copyOf(n)))
        }
        return out.sortedBy { HEATER_ORDER.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }
    }

    private val HEATER_ORDER = listOf("nozzle", "nozzle_2", "bed", "chamber")

    fun heaterName(kind: String): String = when (kind) {
        "nozzle" -> "Nozzle"
        "nozzle_2" -> "Nozzle 2"
        "bed" -> "Bed"
        "chamber" -> "Chamber"
        else -> kind.replaceFirstChar { it.uppercase() }
    }

    /**
     * Minutes this print has been running, worked out from how far it has got
     * and how long it says is left. The status carries no start time, and
     * this is close enough to frame a chart around.
     */
    fun elapsedMinutes(progress: Double?, remainingMinutes: Int?): Int? {
        val p = progress ?: return null
        val left = remainingMinutes ?: return null
        if (p <= 0.0 || p >= 100.0 || left <= 0) return null
        return (left * p / (100.0 - p)).toInt()
    }

    // --------------------------------------------------------------- stats

    enum class Span(val label: String, val buckets: Int) {
        DAYS("30 days", 30),
        WEEKS("12 weeks", 12),
        MONTHS("12 months", 12)
    }

    const val OK = 0
    const val FAILED = 1
    const val OTHER = 2

    /** A print_log status, sorted into the three outcomes the chart colours. */
    fun outcome(status: String?): Int = when (status?.lowercase()) {
        "completed" -> OK
        "failed", "aborted" -> FAILED
        else -> OTHER
    }

    class Bucket(val start: LocalDate) {
        val counts = IntArray(3)
        var grams = 0.0
        val prints get() = counts.sum()
    }

    /** The first day [span] covers, ending with [today]. */
    fun firstDay(span: Span, today: LocalDate): LocalDate = when (span) {
        Span.DAYS -> today.minusDays((span.buckets - 1).toLong())
        Span.WEEKS -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .minusWeeks((span.buckets - 1).toLong())
        Span.MONTHS -> today.withDayOfMonth(1).minusMonths((span.buckets - 1).toLong())
    }

    /**
     * Sorts each run from `GET /archives/slim` into its day, week or month,
     * by the phone's own calendar. Runs outside the span are dropped.
     */
    fun buckets(runs: JSONArray, span: Span, today: LocalDate, zone: ZoneId): List<Bucket> {
        val first = firstDay(span, today)
        val out = List(span.buckets) { i ->
            Bucket(
                when (span) {
                    Span.DAYS -> first.plusDays(i.toLong())
                    Span.WEEKS -> first.plusWeeks(i.toLong())
                    Span.MONTHS -> first.plusMonths(i.toLong())
                }
            )
        }
        for (run in runs.objects()) {
            val at = parseTime(run.str("created_at") ?: run.str("completed_at") ?: run.str("started_at"))
                ?: continue
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            if (day.isBefore(first) || day.isAfter(today)) continue
            val index = when (span) {
                Span.DAYS -> ChronoUnit.DAYS.between(first, day)
                Span.WEEKS -> ChronoUnit.WEEKS.between(first, day)
                Span.MONTHS -> ChronoUnit.MONTHS.between(first, day.withDayOfMonth(1))
            }.toInt()
            val bucket = out.getOrNull(index) ?: continue
            bucket.counts[outcome(run.str("status"))]++
            bucket.grams += run.dbl("filament_used_grams")?.takeIf { it > 0 } ?: 0.0
        }
        return out
    }

    /** Grams of filament per material across [runs], heaviest first. */
    fun gramsByMaterial(runs: JSONArray, since: LocalDate, zone: ZoneId): List<Pair<String, Double>> {
        val totals = LinkedHashMap<String, Double>()
        for (run in runs.objects()) {
            val at = parseTime(run.str("created_at") ?: run.str("completed_at")) ?: continue
            if (Instant.ofEpochMilli(at).atZone(zone).toLocalDate().isBefore(since)) continue
            val grams = run.dbl("filament_used_grams")?.takeIf { it > 0 } ?: continue
            // A multi-material print reports "PLA, PETG"; the first is what most of it was.
            val material = run.str("filament_type")?.split(',', ';')?.first()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "Unknown"
            totals[material] = (totals[material] ?: 0.0) + grams
        }
        return totals.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    /** Where to write a date under a bar: a handful, evenly spaced, the last always shown. */
    fun barLabels(buckets: List<Bucket>, span: Span, most: Int = 6): List<Pair<Int, String>> {
        if (buckets.isEmpty()) return emptyList()
        val every = ceil(buckets.size / most.toDouble()).toInt().coerceAtLeast(1)
        val out = ArrayList<Pair<Int, String>>()
        for (i in buckets.indices.reversed() step every) out.add(i to bucketLabel(buckets[i].start, span))
        return out.reversed()
    }

    fun bucketLabel(start: LocalDate, span: Span): String {
        val month = start.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
        return when (span) {
            Span.DAYS, Span.WEEKS -> "${start.dayOfMonth} $month"
            Span.MONTHS -> month
        }
    }

    /** "850 g" under a kilo, "1.2 kg" over. */
    fun weight(grams: Double): String =
        if (grams < 1000) "${grams.toInt()} g" else String.format(Locale.ROOT, "%.1f kg", grams / 1000)
}
