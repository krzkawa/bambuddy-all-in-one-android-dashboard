package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineExtrasTest {

    // ------------------------------------------------------------------ plugs

    private fun plug(type: String = "tasmota", entity: String? = null, lastState: String? = null) =
        JSONObject().put("id", 7).put("name", "Shelf plug").put("plug_type", type)
            .also { o -> entity?.let { o.put("ha_entity_id", it) } }
            .also { o -> lastState?.let { o.put("last_state", it) } }

    @Test
    fun `an mqtt plug or a script can be read but not switched`() {
        assertTrue(Power.canSwitch(plug()))
        assertTrue(Power.canSwitch(plug("homeassistant", "switch.printer")))
        assertFalse(Power.canSwitch(plug("mqtt")))
        assertFalse(Power.canSwitch(plug("homeassistant", "script.warm_up")))
    }

    @Test
    fun `a live status gives state and draw`() {
        val status = JSONObject().put("state", "ON").put("reachable", true)
            .put("energy", JSONObject().put("power", 141.6).put("today", 0.84))
        val p = Power.parse(plug(), status)
        assertEquals(true, p.on)
        assertTrue(p.reachable)
        assertEquals("On · 142 W · 0.84 kWh today", Power.describe(p))
    }

    @Test
    fun `an off plug does not claim a draw`() {
        val status = JSONObject().put("state", "OFF").put("reachable", true)
            .put("energy", JSONObject().put("power", 0.4))
        assertEquals("Off", Power.describe(Power.parse(plug(), status)))
    }

    @Test
    fun `a plug that did not answer falls back to its last known state`() {
        val p = Power.parse(plug(lastState = "OFF"), null)
        assertEquals(false, p.on)
        assertFalse(p.reachable)
        assertEquals("Off · not answering", Power.describe(p))

        val unknown = Power.parse(plug(), JSONObject().put("state", JSONObject.NULL).put("reachable", false))
        assertEquals("Plug not answering", Power.describe(unknown))
    }

    @Test
    fun `only states with a job on the plate count as busy`() {
        listOf("RUNNING", "PAUSE", "PREPARE").forEach {
            assertTrue(it, Power.busy(JSONObject().put("state", it)))
        }
        listOf("IDLE", "FINISH", "FAILED").forEach {
            assertFalse(it, Power.busy(JSONObject().put("state", it)))
        }
        assertFalse(Power.busy(null))
    }

    // ------------------------------------------------------------ maintenance

    private fun item(
        id: Int, name: String, due: Boolean = false, warning: Boolean = false,
        type: String = "hours", hours: Double = 10.0, days: Double? = null,
        enabled: Boolean = true, performed: String? = "2026-09-01T10:00:00"
    ) = JSONObject().put("id", id).put("maintenance_type_name", name).put("enabled", enabled)
        .put("is_due", due).put("is_warning", warning).put("interval_type", type)
        .put("hours_until_due", hours)
        .put("days_until_due", days ?: JSONObject.NULL)
        .put("last_performed_at", performed ?: JSONObject.NULL)

    private fun overview(vararg items: JSONObject): JSONArray {
        val list = JSONArray()
        items.forEach { list.put(it) }
        return JSONArray().put(JSONObject().put("printer_id", 3).put("maintenance_items", list))
    }

    @Test
    fun `due items come most overdue first and disabled ones never`() {
        val parsed = Maintenance.parse(overview(
            item(1, "Clean nozzle", due = true, hours = -2.0),
            item(2, "Lubricate rods", due = true, hours = -40.0),
            item(3, "Check belts", due = true, hours = -99.0, enabled = false),
            item(4, "Clean plate", warning = true, hours = 3.0),
            item(5, "Replace PTFE", hours = 300.0)
        ))[3]!!
        assertEquals(listOf("Lubricate rods", "Clean nozzle"), Maintenance.due(parsed).map { it.name })
        assertEquals(listOf("Clean plate"), Maintenance.soon(parsed).map { it.name })
        assertEquals(
            listOf("Lubricate rods", "Clean nozzle", "Clean plate", "Replace PTFE"),
            Maintenance.ordered(parsed).map { it.name }
        )
    }

    @Test
    fun `when an item is due is said in its own unit`() {
        val parsed = Maintenance.parse(overview(
            item(1, "a", due = true, hours = -12.4),
            item(2, "b", hours = 7.6),
            item(3, "c", type = "days", days = -3.0, due = true),
            item(4, "d", type = "days", days = 1.2),
            item(5, "e", type = "days", days = -1.0, due = true, performed = null),
            item(6, "f", due = true, hours = -0.2)
        ))[3]!!.associateBy { it.name }
        assertEquals("12 h overdue", Maintenance.whenDue(parsed["a"]!!))
        assertEquals("in 8 h", Maintenance.whenDue(parsed["b"]!!))
        assertEquals("3 days overdue", Maintenance.whenDue(parsed["c"]!!))
        assertEquals("in 1 day", Maintenance.whenDue(parsed["d"]!!))
        assertEquals("never done", Maintenance.whenDue(parsed["e"]!!))
        assertEquals("due now", Maintenance.whenDue(parsed["f"]!!))
    }

    // --------------------------------------------------------------- firmware

    @Test
    fun `an update counts only when the printer's version is known`() {
        val response = JSONObject().put("updates", JSONArray()
            .put(JSONObject().put("printer_id", 1).put("current_version", "01.08.02.00")
                .put("latest_version", "01.09.00.00").put("update_available", true))
            .put(JSONObject().put("printer_id", 2).put("current_version", JSONObject.NULL)
                .put("latest_version", "01.09.00.00").put("update_available", true)))
        val info = Firmware.parse(response)
        assertTrue(info[1]!!.updateAvailable)
        assertEquals("01.09.00.00", info[1]!!.latest)
        assertFalse(info[2]!!.updateAvailable)
    }

    @Test
    fun `release notes lose their markup`() {
        assertEquals(
            "Fixes\n• Better AMS\n• Quieter fans",
            Firmware.plain("<p>Fixes</p><ul><li>Better AMS</li><li>Quieter&nbsp;fans</li></ul>")
        )
    }

    // ------------------------------------------------------------------- move

    @Test
    fun `up goes up whichever part moves on Z`() {
        // Bed on Z: raising the bed closes the gap.
        assertEquals(-10.0, Move.gapFor(up = true, headMoves = false, step = 10.0), 0.0)
        assertEquals(10.0, Move.gapFor(up = false, headMoves = false, step = 10.0), 0.0)
        // A1: raising the head opens it.
        assertEquals(1.0, Move.gapFor(up = true, headMoves = true, step = 1.0), 0.0)
        assertEquals(-1.0, Move.gapFor(up = false, headMoves = true, step = 1.0), 0.0)
    }

    @Test
    fun `only the A1 family moves its head on Z`() {
        assertTrue(Move.headMovesOnZ("A1"))
        assertTrue(Move.headMovesOnZ("a1 mini"))
        assertTrue(Move.headMovesOnZ("N2S"))
        assertFalse(Move.headMovesOnZ("X1C"))
        assertFalse(Move.headMovesOnZ("P1S"))
        assertFalse(Move.headMovesOnZ(null))
    }

    @Test
    fun `calibrations a printer cannot run are not offered`() {
        val p1s = Move.calibrations("P1S", 1).map { it.key }
        assertEquals(listOf("bed_leveling", "vibration", "motor_noise"), p1s)
        val h2d = Move.calibrations("H2D", 2).map { it.key }
        assertTrue("nozzle_offset" in h2d)
        assertTrue("high_temp_heatbed" in h2d)
    }

    @Test
    fun `the jog pad starts locked`() {
        assertFalse(Move.unlocked(1))
        assertEquals(1.0, Move.step(), 0.0)
    }

    // ------------------------------------------------------------ print checks

    @Test
    fun `the air duct is offered on the models that have one`() {
        assertTrue(PrintChecks.hasAirduct("H2D"))
        assertTrue(PrintChecks.hasAirduct("P2S"))
        assertFalse(PrintChecks.hasAirduct("X1C"))
        assertFalse(PrintChecks.hasAirduct(null))
    }

    @Test
    fun `a printer without camera settings has no signature to rebuild on`() {
        assertEquals("", PrintChecks.signature(JSONObject()))
        assertNull(JSONObject().optJSONObject("print_options"))
    }
}
