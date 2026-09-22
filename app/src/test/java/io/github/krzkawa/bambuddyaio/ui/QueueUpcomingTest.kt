package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the Queue screen is allowed to show, and what belongs to History. */
class QueueUpcomingTest {

    private fun item(id: Int, status: String, printer: Int? = null): JSONObject {
        val o = JSONObject().put("id", id).put("status", status)
        if (printer != null) o.put("printer_id", printer)
        return o
    }

    private fun ids(rows: List<JSONObject>) = rows.map { it.optInt("id") }

    @Test
    fun `finished prints are dropped`() {
        val rows = listOf(
            item(1, "completed"), item(2, "pending"), item(3, "failed"),
            item(4, "cancelled"), item(5, "printing")
        )
        assertEquals(listOf(5, 2), ids(Queue.upcoming(rows)))
    }

    @Test
    fun `the one on the printer sorts first`() {
        val rows = listOf(item(1, "pending"), item(2, "pending"), item(3, "printing"))
        assertEquals(listOf(3, 1, 2), ids(Queue.upcoming(rows)))
    }

    @Test
    fun `a status this app has never heard of is kept`() {
        val rows = listOf(item(1, "staged_for_tomorrow"), item(2, "done"))
        assertEquals(listOf(1), ids(Queue.upcoming(rows)))
    }

    @Test
    fun `case and whitespace from the server do not matter`() {
        val rows = listOf(item(1, " Completed "), item(2, "PENDING"))
        assertEquals(listOf(2), ids(Queue.upcoming(rows)))
    }

    @Test
    fun `next for a printer skips the one already printing`() {
        val rows = listOf(
            item(1, "printing", printer = 7), item(2, "pending", printer = 7),
            item(3, "pending", printer = 9)
        )
        assertEquals(2, Queue.nextFor(rows, 7)?.optInt("id"))
        assertEquals(3, Queue.nextFor(rows, 9)?.optInt("id"))
    }

    @Test
    fun `an item tied to no printer counts for the one asking`() {
        val rows = listOf(item(1, "pending"))
        assertEquals(1, Queue.nextFor(rows, 4)?.optInt("id"))
    }

    @Test
    fun `nothing waiting means nothing to report`() {
        assertNull(Queue.nextFor(listOf(item(1, "completed"), item(2, "printing")), 1))
    }
}
