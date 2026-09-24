package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class PrintPlanTest {

    private fun tray(id: Int, type: String?, colour: String?, idx: String? = null) =
        JSONObject().put("id", id).put("tray_type", type ?: JSONObject.NULL)
            .put("tray_color", colour ?: JSONObject.NULL).put("tray_info_idx", idx ?: JSONObject.NULL)
            .put("remain", 50)

    private fun status(vararg units: Pair<Int, List<JSONObject>>, external: List<JSONObject> = emptyList()): JSONObject {
        val ams = JSONArray()
        for ((id, trays) in units) {
            ams.put(JSONObject().put("id", id).put("tray", JSONArray().also { a -> trays.forEach { a.put(it) } }))
        }
        return JSONObject().put("ams", ams).put("vt_tray", JSONArray().also { a -> external.forEach { a.put(it) } })
    }

    private fun need(slot: Int, type: String, colour: String?, idx: String? = null) =
        PrintPlan.Need(slot, type, colour, 10.0, idx)

    @Test
    fun `only sliced files can be printed`() {
        assertTrue(PrintPlan.isSliced(JSONObject().put("file_type", "gcode.3mf").put("filename", "a.gcode.3mf")))
        assertTrue(PrintPlan.isSliced(JSONObject().put("file_type", "gcode").put("filename", "a.gcode")))
        // An external scan can leave the type plain; the name still says sliced.
        assertTrue(PrintPlan.isSliced(JSONObject().put("file_type", "3mf").put("filename", "A.GCODE.3MF")))
        assertFalse(PrintPlan.isSliced(JSONObject().put("file_type", "3mf").put("filename", "project.3mf")))
        assertFalse(PrintPlan.isSliced(JSONObject().put("file_type", "stl").put("filename", "part.stl")))
    }

    @Test
    fun `the folder tree flattens and walks back up to the top`() {
        val tree = JSONArray().put(
            JSONObject().put("id", 1).put("name", "Parts").put("parent_id", JSONObject.NULL).put("file_count", 2)
                .put("children", JSONArray().put(
                    JSONObject().put("id", 2).put("name", "Brackets").put("parent_id", 1).put("file_count", 5)
                        .put("children", JSONArray())
                ))
        ).put(JSONObject().put("id", 3).put("name", "Art").put("parent_id", JSONObject.NULL))
        val all = PrintPlan.folders(tree)
        assertEquals(listOf(1, 2, 3), all.map { it.id })
        assertEquals(listOf("Art", "Parts"), PrintPlan.childrenOf(all, null).map { it.name })
        assertEquals(listOf("Brackets"), PrintPlan.childrenOf(all, 1).map { it.name })
        assertEquals(listOf("Parts", "Brackets"), PrintPlan.pathTo(all, 2).map { it.name })
        assertTrue(PrintPlan.pathTo(all, null).isEmpty())
    }

    @Test
    fun `trays use global ids, including an HT and the external holder`() {
        val s = status(
            0 to listOf(tray(0, "PLA", "FFFFFFFF"), tray(1, null, null), tray(2, "PETG", "000000FF")),
            128 to listOf(tray(0, "PA-CF", "333333FF")),
            external = listOf(tray(254, "TPU", "FF0000FF"))
        )
        val trays = PrintPlan.trays(s)
        // The tray with no type is left out: nothing can be matched to it.
        assertEquals(listOf(0, 2, 128, 254), trays.map { it.globalId })
        assertEquals("External spool", trays.last().label)
    }

    @Test
    fun `a unique tray_info_idx wins over colour`() {
        val trays = PrintPlan.trays(status(0 to listOf(
            tray(0, "PLA", "FF0000FF", "GFA00"),
            tray(1, "PLA", "00FF00FF", "GFA01")
        )))
        val picks = PrintPlan.match(listOf(need(1, "PLA", "#FF0000", "GFA01")), trays)
        assertEquals(1, picks[0].tray?.globalId)
        // Picked by variant, but the colour is still judged honestly.
        assertEquals(PrintPlan.Fit.TYPE_ONLY, picks[0].fit)
    }

    @Test
    fun `exact colour, then nearest similar, then any tray of the type`() {
        val trays = PrintPlan.trays(status(0 to listOf(
            tray(0, "PLA", "101010FF"),
            tray(1, "PLA", "F0F0F0FF"),
            tray(2, "PLA", "FFFFFFFF"),
            tray(3, "PETG", "FFFFFFFF")
        )))
        val picks = PrintPlan.match(
            listOf(need(1, "PLA", "#FFFFFF"), need(2, "PLA", "#FAFAFA"), need(3, "PLA", "#FF0000"), need(4, "ABS", "#FFFFFF")),
            trays
        )
        assertEquals(2, picks[0].tray?.globalId)       // exact
        assertEquals(1, picks[1].tray?.globalId)       // nearest similar, tray 2 already taken
        assertEquals(0, picks[2].tray?.globalId)       // type only
        assertEquals(PrintPlan.Fit.TYPE_ONLY, picks[2].fit)
        assertNull(picks[3].tray)                      // nothing of the type
        assertEquals(PrintPlan.Fit.NONE, picks[3].fit)
        assertEquals(listOf(2, 1, 0, -1), PrintPlan.amsMapping(picks))
    }

    @Test
    fun `his own choice is kept and taken out of the automatic pool`() {
        val trays = PrintPlan.trays(status(0 to listOf(tray(0, "PLA", "FFFFFFFF"), tray(1, "PLA", "FFFFFFFF"))))
        val picks = PrintPlan.match(listOf(need(1, "PLA", "#FFFFFF"), need(2, "PLA", "#FFFFFF")), trays, mapOf(2 to 0))
        assertEquals(1, picks[0].tray?.globalId)
        assertEquals(0, picks[1].tray?.globalId)
        assertTrue(picks[1].manual)
    }

    @Test
    fun `the mapping is indexed by the file's own slot numbers`() {
        val trays = PrintPlan.trays(status(1 to listOf(tray(2, "PLA", "FFFFFFFF"))))
        val picks = PrintPlan.match(listOf(need(3, "PLA", "#FFFFFF")), trays)
        // AMS 2 slot 3 is global tray 6, and a plate that only prints slot 3
        // still sends the two slots ahead of it as unused.
        assertEquals(listOf(-1, -1, 6), PrintPlan.amsMapping(picks))
        assertNull(PrintPlan.amsMapping(emptyList()))
    }

    @Test
    fun `material groups the firmware treats as one`() {
        assertTrue(PrintPlan.sameType("PA12-CF", "pa-cf"))
        assertFalse(PrintPlan.sameType("PLA", "PETG"))
        assertTrue(PrintPlan.colourFits("FFFFFFFF", null))
        assertTrue(PrintPlan.colourFits("F0F0F0FF", "#FFFFFF"))
        assertFalse(PrintPlan.colourFits("000000FF", "#FFFFFF"))
    }

    @Test
    fun `a model mismatch is only called when both sides are known`() {
        assertTrue(PrintPlan.modelMismatch("P1S", "X1C"))
        assertFalse(PrintPlan.modelMismatch("Bambu Lab X1C", "X1C"))
        assertFalse(PrintPlan.modelMismatch(null, "X1C"))
        assertFalse(PrintPlan.modelMismatch("A1", ""))
    }

    @Test
    fun `readiness reads the status the way the sheet needs it`() {
        assertEquals(PrintPlan.Readiness.UNKNOWN, PrintPlan.readiness(null))
        assertEquals(PrintPlan.Readiness.OFFLINE, PrintPlan.readiness(JSONObject().put("connected", false)))
        assertEquals(PrintPlan.Readiness.PLATE,
            PrintPlan.readiness(JSONObject().put("state", "FINISH").put("awaiting_plate_clear", true)))
        assertEquals(PrintPlan.Readiness.BUSY, PrintPlan.readiness(JSONObject().put("state", "RUNNING")))
        assertEquals(PrintPlan.Readiness.READY, PrintPlan.readiness(JSONObject().put("state", "IDLE")))
    }

    @Test
    fun `a print for now is created staged and put at the front`() {
        val p = PrintPlan.payload(PrintPlan.Request(printerId = 2, libraryFileId = 9, plateId = 3, mapping = listOf(4, -1)))
        assertEquals(2, p.getInt("printer_id"))
        assertEquals(9, p.getInt("library_file_id"))
        assertFalse(p.has("archive_id"))
        assertEquals(3, p.getInt("plate_id"))
        assertEquals("[4,-1]", p.getJSONArray("ams_mapping").toString())
        assertTrue(p.getBoolean("manual_start"))
        assertTrue(p.getBoolean("insert_at_top"))
        assertFalse(p.has("scheduled_time"))
        assertEquals("auto", p.getString("bed_levelling"))
    }

    @Test
    fun `a print for later is left to the scheduler`() {
        val at = Instant.parse("2026-09-24T17:00:00Z")
        val p = PrintPlan.payload(
            PrintPlan.Request(printerId = 1, archiveId = 5, whenToPrint = PrintPlan.When.LATER, at = at, timelapse = true)
        )
        assertEquals(5, p.getInt("archive_id"))
        assertFalse(p.getBoolean("manual_start"))
        assertFalse(p.has("insert_at_top"))
        assertEquals("2026-09-24T17:00:00Z", p.getString("scheduled_time"))
        assertTrue(p.getBoolean("timelapse"))
        assertFalse(p.has("ams_mapping"))
    }

    @Test
    fun `a time already past today means tomorrow`() {
        val now = LocalDateTime.of(2026, 9, 24, 20, 30)
        assertEquals(Instant.parse("2026-09-24T21:00:00Z"), PrintPlan.nextAt(21, 0, now, ZoneOffset.UTC))
        assertEquals(Instant.parse("2026-09-25T07:15:00Z"), PrintPlan.nextAt(7, 15, now, ZoneOffset.UTC))
        assertEquals("today 21:00", PrintPlan.sayWhen(Instant.parse("2026-09-24T21:00:00Z"), now, ZoneOffset.UTC))
        assertEquals("tomorrow 07:15", PrintPlan.sayWhen(Instant.parse("2026-09-25T07:15:00Z"), now, ZoneOffset.UTC))
    }

    @Test
    fun `server timestamps parse with or without a zone`() {
        assertEquals(Instant.parse("2026-09-24T10:00:00Z"), PrintPlan.parseInstant("2026-09-24T10:00:00Z"))
        assertEquals(Instant.parse("2026-09-24T10:00:00Z"), PrintPlan.parseInstant("2026-09-24T12:00:00+02:00"))
        assertEquals(Instant.parse("2026-09-24T10:00:00Z"), PrintPlan.parseInstant("2026-09-24T10:00:00"))
        assertNull(PrintPlan.parseInstant(""))
        assertNull(PrintPlan.parseInstant("soon"))
    }
}
