package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HmsTest {

    private fun status(vararg errors: JSONObject): JSONObject {
        val array = JSONArray()
        errors.forEach { array.put(it) }
        return JSONObject().put("hms_errors", array)
    }

    private fun error(
        description: String? = null,
        code: String? = null,
        severity: Int? = null,
        actions: List<String>? = null
    ): JSONObject {
        val o = JSONObject()
        description?.let { o.put("description", it) }
        code?.let { o.put("code", it) }
        severity?.let { o.put("severity", it) }
        actions?.let { list -> o.put("actions", JSONArray().also { a -> list.forEach { a.put(it) } }) }
        return o
    }

    @Test
    fun `the description is what the printer actually said`() {
        val faults = Hms.faults(status(error(description = "Nozzle temperature is abnormal", code = "0300_0100")))
        assertEquals("Nozzle temperature is abnormal", faults.single().description)
        assertEquals("0300_0100", faults.single().code)
    }

    @Test
    fun `a fault with no description falls back to its code, not to a placeholder`() {
        val faults = Hms.faults(status(error(code = "0300_0100")))
        assertEquals("0300_0100", faults.single().description)
    }

    @Test
    fun `a fault with nothing usable still says something`() {
        val faults = Hms.faults(status(error()))
        assertEquals("Printer reported an error", faults.single().description)
        assertNull(faults.single().code)
    }

    @Test
    fun `the worst fault comes first`() {
        val faults = Hms.faults(
            status(
                error(description = "Just so you know", severity = Hms.INFO),
                error(description = "The printer has stopped", severity = Hms.FATAL),
                error(description = "Keep an eye on this", severity = Hms.COMMON)
            )
        )
        assertEquals(
            listOf("The printer has stopped", "Keep an eye on this", "Just so you know"),
            faults.map { it.description }
        )
    }

    @Test
    fun `a fault with no severity sorts last rather than first`() {
        val faults = Hms.faults(
            status(
                error(description = "Unranked"),
                error(description = "Serious", severity = Hms.SERIOUS)
            )
        )
        assertEquals("Serious", faults.first().description)
    }

    @Test
    fun `a severity outside the documented range is not trusted`() {
        assertNull(Hms.faults(status(error(description = "Odd", severity = 9))).single().severity)
    }

    @Test
    fun `suggested actions are carried through`() {
        val faults = Hms.faults(status(error(description = "Load filament", actions = listOf("retry", "cancel"))))
        assertEquals(listOf("retry", "cancel"), faults.single().actions)
    }

    @Test
    fun `no actions is an empty list rather than a crash`() {
        assertTrue(Hms.faults(status(error(description = "x"))).single().actions.isEmpty())
    }

    @Test
    fun `a status with no errors has no faults`() {
        assertTrue(Hms.faults(JSONObject()).isEmpty())
        assertTrue(Hms.faults(null).isEmpty())
    }

    @Test
    fun `the detail line carries how bad it is and its code`() {
        val fault = Hms.faults(status(error(description = "x", code = "0300_0100", severity = Hms.FATAL))).single()
        assertEquals("Fatal · 0300_0100", fault.detail)
    }
}
