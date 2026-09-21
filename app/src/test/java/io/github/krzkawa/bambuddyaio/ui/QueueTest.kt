package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.net.ApiError
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueTest {

    private fun deficit(vararg rows: JSONObject): ApiError {
        val array = JSONArray()
        rows.forEach { array.put(it) }
        val detail = JSONObject()
            .put("code", Queue.INSUFFICIENT_FILAMENT)
            .put("deficit", array)
        return ApiError(409, "insufficient filament", detail)
    }

    private fun row(
        slotId: Int = 1,
        amsId: Int? = 0,
        trayId: Int? = 1,
        filament: String? = "PLA",
        required: Double = 240.0,
        remaining: Double? = 100.0
    ): JSONObject {
        val o = JSONObject().put("slot_id", slotId).put("required_grams", required)
        amsId?.let { o.put("ams_id", it) }
        trayId?.let { o.put("tray_id", it) }
        filament?.let { o.put("filament_type", it) }
        if (remaining == null) o.put("remaining_grams", JSONObject.NULL) else o.put("remaining_grams", remaining)
        return o
    }

    @Test
    fun `a filament refusal is read back as the slots that are short`() {
        val short = Queue.shortfalls(deficit(row()))!!.single()
        assertEquals(0, short.amsId)
        assertEquals(1, short.trayId)
        assertEquals("PLA", short.filament)
        assertEquals(240.0, short.requiredGrams, 0.001)
        assertEquals(100.0, short.remainingGrams!!, 0.001)
    }

    @Test
    fun `the line says how much is missing, not how much is needed`() {
        // 240 needed against 100 left is 140 short, which is the number he acts on.
        assertTrue(Queue.shortfalls(deficit(row()))!!.single().line.contains("140 g short"))
    }

    @Test
    fun `the slot is named the way the AMS screen names it`() {
        assertEquals("AMS 1 · slot 2", Queue.shortfalls(deficit(row(amsId = 0, trayId = 1)))!!.single().where)
        assertEquals("External spool", Queue.shortfalls(deficit(row(amsId = 255, trayId = 0)))!!.single().where)
    }

    @Test
    fun `a slot the server did not name by unit falls back to its global id`() {
        val short = Queue.shortfalls(deficit(row(slotId = 5, amsId = null, trayId = null)))!!.single()
        assertEquals("AMS 2 · slot 2", short.where)
    }

    @Test
    fun `a spool whose remaining weight is unknown says so rather than claiming zero`() {
        val line = Queue.shortfalls(deficit(row(remaining = null)))!!.single().line
        assertTrue(line.contains("does not say how much is left"))
        assertFalse(line.contains("short"))
    }

    @Test
    fun `a spool with more left than the print needs is still reported verbatim`() {
        // The server only sends rows it considers short, and a pooled deficit
        // can name a slot whose own remaining covers its share. Never negative.
        val line = Queue.shortfalls(deficit(row(required = 100.0, remaining = 240.0)))!!.single().line
        assertTrue(line.contains("0 g short"))
    }

    @Test
    fun `every short slot gets its own line`() {
        val rows = Queue.shortfalls(deficit(row(amsId = 0, trayId = 0), row(amsId = 1, trayId = 2)))!!
        val message = Queue.shortfallMessage(rows)
        assertTrue(message.contains("AMS 1 · slot 1"))
        assertTrue(message.contains("AMS 2 · slot 3"))
    }

    @Test
    fun `a 409 that is not about filament is not turned into a prompt`() {
        val other = ApiError(409, "busy", JSONObject().put("code", "printer_busy"))
        assertNull(Queue.shortfalls(other))
    }

    @Test
    fun `an ordinary failure is left alone`() {
        assertNull(Queue.shortfalls(ApiError(400, "Can only start pending items")))
        assertNull(Queue.shortfalls(ApiError(409, "something", null)))
    }

    @Test
    fun `a deficit with no rows is not a question worth asking`() {
        assertNull(Queue.shortfalls(deficit()))
    }

    @Test
    fun `only a pending item is offered a start button`() {
        assertTrue(Queue.canStart(JSONObject().put("status", "pending")))
        assertFalse(Queue.canStart(JSONObject().put("status", "printing")))
        assertFalse(Queue.canStart(JSONObject().put("status", "completed")))
        assertFalse(Queue.canStart(JSONObject()))
    }

    @Test
    fun `the server's own reason for waiting wins over a guess at it`() {
        val item = JSONObject().put("waiting_reason", "Printer is busy").put("filament_short", true)
        assertEquals("Printer is busy", Queue.waitingReason(item))
    }

    @Test
    fun `a short item says so before he taps start`() {
        assertEquals("Not enough filament", Queue.waitingReason(JSONObject().put("filament_short", true)))
        assertEquals("Waiting for you to start it", Queue.waitingReason(JSONObject().put("manual_start", true)))
        assertNull(Queue.waitingReason(JSONObject()))
    }

    @Test
    fun `an item is named by its file, not by a placeholder`() {
        assertEquals("bracket.3mf", Queue.itemName(JSONObject().put("archive_name", "bracket.3mf")))
        assertEquals("lid.3mf", Queue.itemName(JSONObject().put("library_file_name", "lid.3mf")))
        assertEquals("Queued print", Queue.itemName(JSONObject()))
    }

    @Test
    fun `grams read as whole grams`() {
        assertEquals("140 g", Queue.grams(139.6))
        assertEquals("0 g", Queue.grams(0.0))
    }
}
