package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TrendsTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")

    @Test
    fun `a server time with no zone is UTC`() {
        val bare = Trends.parseTime("2026-09-24T01:16:22.123456")
        val zoned = Trends.parseTime("2026-09-24T01:16:22.123456Z")
        assertEquals(zoned, bare)
        assertEquals(Trends.parseTime("2026-09-24T03:16:22.123456+02:00"), bare)
        assertEquals(Trends.parseTime("2026-09-24 01:16:22.123456"), bare)
    }

    @Test
    fun `an unreadable time is no time`() {
        assertNull(Trends.parseTime(null))
        assertNull(Trends.parseTime(""))
        assertNull(Trends.parseTime("yesterday"))
    }

    @Test
    fun `clock labels land on round hours in the phone's zone`() {
        // 01:16 in Warsaw (UTC+2) on the 24th.
        val now = Trends.parseTime("2026-09-23T23:16:00Z")!!
        val labels = Trends.clockLabels(now, 24f, warsaw, 5)
        assertEquals(listOf("06:00", "12:00", "18:00", "00:00"), labels.map { it.second })
        // Midnight local is 76 minutes before now.
        assertEquals(-76f / 60f, labels.last().first, 1e-3f)
    }

    @Test
    fun `gridlines fall on round numbers`() {
        assertEquals(listOf(0f, 50f, 100f, 150f, 200f, 250f), Trends.ticks(0f, 250f, 5))
        assertEquals(listOf(0f, 1f, 2f, 3f), Trends.ticks(0f, 3f, 3))
        assertEquals(2.5f, Trends.niceStep(2.2f))
        assertEquals(500f, Trends.niceStep(420f))
        assertEquals(250f, Trends.roundUp(229f, 50f))
    }

    @Test
    fun `AMS history prefers the real percentage`() {
        val json = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("recorded_at", "2026-09-24T00:00:00").put("humidity", 3).put("humidity_raw", 28.0).put("temperature", 24.5))
            .put(JSONObject().put("recorded_at", "2026-09-24T00:05:00").put("humidity", 3).put("humidity_raw", 31.0).put("temperature", 25.0)))
        val now = Trends.parseTime("2026-09-24T01:00:00")!!
        val h = Trends.amsHistory(json, now)
        assertFalse(h.levelOnly)
        assertEquals(28f, h.humidity[0])
        assertEquals(-1f, h.hours[0], 1e-4f)
        assertEquals(28f, h.minHumidity)
        assertEquals(31f, h.maxHumidity)
        assertEquals(25f, h.maxTemp)
    }

    @Test
    fun `an AMS that only reports a level is drawn as one`() {
        val json = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("recorded_at", "2026-09-24T00:00:00").put("humidity", 2).put("humidity_raw", JSONObject.NULL)))
        assertTrue(Trends.amsHistory(json, 0L).levelOnly)
    }

    @Test
    fun `heaters come back nozzle first, whatever order the server sent`() {
        fun series(kind: String) = JSONObject().put("sensor_kind", kind).put("data", JSONArray()
            .put(JSONObject().put("recorded_at", "2026-09-24T00:00:00").put("value", 60.0).put("target", 0.0)))
        val json = JSONObject().put("series", JSONArray().put(series("chamber")).put(series("bed")).put(series("nozzle"))
            .put(JSONObject().put("sensor_kind", "nozzle_2").put("data", JSONArray())))
        val out = Trends.heaterHistory(json, 0L)
        assertEquals(listOf("nozzle", "bed", "chamber"), out.map { it.kind })
        assertFalse(out[0].hasTarget)
    }

    @Test
    fun `elapsed print time is worked out from progress and time left`() {
        assertEquals(172, Trends.elapsedMinutes(64.0, 97))
        assertNull(Trends.elapsedMinutes(0.0, 97))
        assertNull(Trends.elapsedMinutes(50.0, null))
    }

    private fun run(at: String, status: String, grams: Double, type: String = "PLA") =
        JSONObject().put("created_at", at).put("status", status)
            .put("filament_used_grams", grams).put("filament_type", type)

    @Test
    fun `runs land on the phone's calendar day, not UTC's`() {
        val today = LocalDate.of(2026, 9, 24)
        val runs = JSONArray()
            // 23:30 UTC on the 23rd is 01:30 on the 24th in Warsaw.
            .put(run("2026-09-23T23:30:00", "completed", 40.0))
            .put(run("2026-09-23T12:00:00", "failed", 10.0))
            .put(run("2026-09-23T13:00:00", "cancelled", 5.0))
            // Before the span: dropped.
            .put(run("2026-08-01T12:00:00", "completed", 999.0))
        val buckets = Trends.buckets(runs, Trends.Span.DAYS, today, warsaw)
        assertEquals(30, buckets.size)
        assertEquals(today, buckets.last().start)
        assertEquals(1, buckets.last().counts[Trends.OK])
        assertEquals(40.0, buckets.last().grams, 1e-9)
        val yesterday = buckets[28]
        assertEquals(1, yesterday.counts[Trends.FAILED])
        assertEquals(1, yesterday.counts[Trends.OTHER])
        assertEquals(15.0, yesterday.grams, 1e-9)
        assertEquals(3, buckets.sumOf { it.prints })
    }

    @Test
    fun `weeks start on Monday and months on the first`() {
        val thursday = LocalDate.of(2026, 9, 24)
        assertEquals(LocalDate.of(2026, 7, 6), Trends.firstDay(Trends.Span.WEEKS, thursday))
        assertEquals(LocalDate.of(2025, 10, 1), Trends.firstDay(Trends.Span.MONTHS, thursday))
        val runs = JSONArray().put(run("2026-09-21T10:00:00", "completed", 1.0))
            .put(run("2026-09-01T10:00:00", "completed", 1.0))
        val weeks = Trends.buckets(runs, Trends.Span.WEEKS, thursday, ZoneOffset.UTC)
        assertEquals(1, weeks.last().prints)
        val months = Trends.buckets(runs, Trends.Span.MONTHS, thursday, ZoneOffset.UTC)
        assertEquals(2, months.last().prints)
        assertEquals("Sep", Trends.bucketLabel(months.last().start, Trends.Span.MONTHS))
    }

    @Test
    fun `filament by material takes the main material of a mixed print`() {
        val runs = JSONArray()
            .put(run("2026-09-20T10:00:00", "completed", 100.0, "PLA, PETG"))
            .put(run("2026-09-21T10:00:00", "completed", 300.0, "PETG"))
            .put(run("2026-09-22T10:00:00", "completed", 50.0, "PLA"))
        val out = Trends.gramsByMaterial(runs, LocalDate.of(2026, 9, 1), ZoneOffset.UTC)
        assertEquals(listOf("PETG" to 300.0, "PLA" to 150.0), out)
    }

    @Test
    fun `the newest bar is always labelled`() {
        val buckets = Trends.buckets(JSONArray(), Trends.Span.DAYS, LocalDate.of(2026, 9, 24), ZoneOffset.UTC)
        val labels = Trends.barLabels(buckets, Trends.Span.DAYS, 4)
        assertEquals(listOf(5, 13, 21, 29), labels.map { it.first })
        assertEquals("24 Sep", labels.last().second)
    }

    @Test
    fun `weights read as a person would say them`() {
        assertEquals("850 g", Trends.weight(850.4))
        assertEquals("1.2 kg", Trends.weight(1234.0))
    }
}
