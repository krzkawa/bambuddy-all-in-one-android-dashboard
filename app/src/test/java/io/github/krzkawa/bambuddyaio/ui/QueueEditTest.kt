package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class QueueEditTest {

    private fun item(id: Int, position: Int, printer: Int? = 1, status: String = "pending", batch: Int? = null) =
        JSONObject().put("id", id).put("position", position).put("status", status)
            .put("printer_id", printer ?: JSONObject.NULL)
            .put("batch_id", batch ?: JSONObject.NULL)
            .put("archive_name", "item $id")

    @Test
    fun `moving renumbers only the item's own printer line`() {
        val items = listOf(
            item(1, 1), item(2, 2), item(3, 2), // a repeat left by an earlier delete
            item(4, 1, printer = 2),
            item(5, 0, status = "printing")
        )
        assertEquals(listOf(2 to 1, 1 to 2, 3 to 3), Queue.moved(items, items[1], -1))
        assertEquals(listOf(1 to 1, 3 to 2, 2 to 3), Queue.moved(items, items[1], 1))
        // Already first, and a line of one, cannot move.
        assertNull(Queue.moved(items, items[0], -1))
        assertNull(Queue.moved(items, items[3], 1))
        // "Move to the front" is a big step that stops at the front.
        assertEquals(listOf(3 to 1, 1 to 2, 2 to 3), Queue.moved(items, items[2], -1000))
    }

    @Test
    fun `items without a printer form their own line`() {
        val items = listOf(item(1, 1, printer = null), item(2, 2, printer = null), item(3, 1))
        assertEquals(listOf(2 to 1, 1 to 2), Queue.moved(items, items[1], -1))
    }

    @Test
    fun `batch copies fold into one row at the place of the first`() {
        val items = listOf(
            item(1, 1, batch = 7), item(2, 2), item(3, 3, batch = 7), item(4, 4, batch = 7),
            item(5, 0, status = "printing", batch = 7)
        )
        val rows = Queue.rows(items)
        assertEquals(listOf(listOf(1, 3, 4), listOf(2), listOf(5)), rows.map { r -> r.items.map { it.getInt("id") } })
        assertTrue(rows[0].isBatch)
        assertEquals("item 1 ×3", Queue.rowName(rows[0]))
        val named = Queue.Row(listOf(item(1, 1, batch = 7).put("batch_name", "Hinge ×4"), item(2, 2, batch = 7)))
        assertEquals("Hinge ×4 · 2 left", Queue.rowName(named))
    }

    @Test
    fun `a far-off placeholder time is not a schedule`() {
        val now = Instant.parse("2026-09-24T10:00:00Z")
        val soon = item(1, 1).put("scheduled_time", "2026-09-24T18:00:00Z")
        assertEquals(Instant.parse("2026-09-24T18:00:00Z"), Queue.scheduledAt(soon, now))
        assertTrue(Queue.isScheduled(soon, now))
        val placeholder = item(2, 1).put("scheduled_time", "2099-01-01T00:00:00Z")
        assertNull(Queue.scheduledAt(placeholder, now))
        assertFalse(Queue.isScheduled(placeholder, now))
        // Held for a tap, a time does not start it, so it is not called scheduled.
        assertFalse(Queue.isScheduled(JSONObject(soon.toString()).put("manual_start", true), now))
        // A time already gone is just waiting for the printer.
        assertFalse(Queue.isScheduled(item(3, 1).put("scheduled_time", "2026-09-24T09:00:00Z"), now))
    }
}
