package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintObjectsTest {

    private fun obj(
        id: Int,
        name: String? = null,
        skipped: Boolean = false,
        x: Double? = null,
        y: Double? = null
    ): JSONObject {
        val o = JSONObject().put("id", id).put("skipped", skipped)
        name?.let { o.put("name", it) }
        o.put("x", x ?: JSONObject.NULL)
        o.put("y", y ?: JSONObject.NULL)
        return o
    }

    private fun payload(printing: Boolean, vararg objects: JSONObject): JSONObject {
        val array = JSONArray()
        objects.forEach { array.put(it) }
        return JSONObject().put("objects", array).put("is_printing", printing)
    }

    @Test
    fun `an object keeps the id the printer skips on, not its place in the list`() {
        val plate = PrintObjects.read(payload(true, obj(41, "Handle"), obj(7, "Lid")))
        assertEquals(listOf(41, 7), plate.objects.map { it.id })
    }

    @Test
    fun `an object with no name is still identifiable`() {
        assertEquals("Object 9", PrintObjects.read(payload(true, obj(9))).objects.single().name)
    }

    @Test
    fun `two identical parts are told apart by where they sit`() {
        val plate = PrintObjects.read(payload(true, obj(1, "Peg", x = 12.4, y = 98.7), obj(2, "Peg")))
        assertEquals("Peg (12, 99 mm)", plate.objects[0].label)
        assertEquals("Peg", plate.objects[1].label)
        assertNull(plate.objects[1].position)
    }

    @Test
    fun `what has already been abandoned is kept apart from what is left`() {
        val plate = PrintObjects.read(
            payload(true, obj(1, "A", skipped = true), obj(2, "B"), obj(3, "C"))
        )
        assertEquals(listOf(1), plate.skipped.map { it.id })
        assertEquals(listOf(2, 3), plate.remaining.map { it.id })
    }

    @Test
    fun `skipping is only offered while the plate is actually running`() {
        assertTrue(PrintObjects.read(payload(true, obj(1), obj(2))).canSkip)
        assertFalse(PrintObjects.read(payload(false, obj(1), obj(2))).canSkip)
    }

    @Test
    fun `the last object standing is not offered, because skipping it ends nothing`() {
        val plate = PrintObjects.read(payload(true, obj(1, skipped = true), obj(2)))
        assertFalse(plate.canSkip)
        assertTrue(PrintObjects.nothingToSkip(plate).contains("stop it instead"))
    }

    @Test
    fun `a printer that reports no objects says so rather than showing an empty list`() {
        val plate = PrintObjects.read(payload(true))
        assertFalse(plate.canSkip)
        assertTrue(PrintObjects.nothingToSkip(plate).contains("not reporting"))
    }

    @Test
    fun `an idle printer is told apart from a plate with nothing left`() {
        val plate = PrintObjects.read(payload(false, obj(1), obj(2)))
        assertTrue(PrintObjects.nothingToSkip(plate).contains("while the plate is running"))
    }

    @Test
    fun `a row with no id is dropped rather than skipping object zero`() {
        val rows = JSONArray().put(JSONObject().put("name", "Nameless")).put(obj(3, "Real"))
        val plate = PrintObjects.read(JSONObject().put("objects", rows).put("is_printing", true))
        assertEquals(listOf(3), plate.objects.map { it.id })
    }

    @Test
    fun `an empty payload is not a crash`() {
        val plate = PrintObjects.read(JSONObject())
        assertTrue(plate.objects.isEmpty())
        assertFalse(plate.printing)
    }
}
