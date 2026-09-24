package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotSpoolTest {

    private fun tray(
        type: String? = "PLA",
        sub: String? = "PLA Basic",
        tagUid: String? = null,
        trayUuid: String? = null,
        remain: Int = -1
    ): JSONObject {
        val t = JSONObject().put("id", 1).put("tray_color", "E53935FF").put("tray_info_idx", "GFA00")
            .put("nozzle_temp_min", 190).put("nozzle_temp_max", 230).put("remain", remain)
        type?.let { t.put("tray_type", it) }
        sub?.let { t.put("tray_sub_brands", it) }
        tagUid?.let { t.put("tag_uid", it) }
        trayUuid?.let { t.put("tray_uuid", it) }
        return t
    }

    @Test
    fun `an all-zero tag is no tag, by the server's rule`() {
        assertFalse(SlotSpool.hasTag(tray(tagUid = "0000000000000000", trayUuid = "0".repeat(32))))
        assertFalse(SlotSpool.hasTag(tray()))
        assertTrue(SlotSpool.hasTag(tray(tagUid = "A1B2C3D4")))
        assertTrue(SlotSpool.hasTag(tray(trayUuid = "0".repeat(31) + "1")))
    }

    @Test
    fun `material and subtype split the way Bambuddy splits them`() {
        assertEquals("PLA" to "Basic", SlotSpool.materialAndSubtype(tray()))
        assertEquals("PETG-HF" to null, SlotSpool.materialAndSubtype(tray(type = "PETG", sub = "PETG-HF")))
        assertEquals("Support W" to null, SlotSpool.materialAndSubtype(tray(type = "PLA", sub = "Support W")))
        assertEquals("PLA" to null, SlotSpool.materialAndSubtype(tray(sub = null)))
    }

    @Test
    fun `payload carries what the tray says`() {
        val p = SlotSpool.payload(tray())
        assertEquals("PLA", p.getString("material"))
        assertEquals("Basic", p.getString("subtype"))
        assertEquals("E53935FF", p.getString("rgba"))
        assertEquals("GFA00", p.getString("slicer_filament"))
        assertEquals(190, p.getInt("nozzle_temp_min"))
        assertEquals(1000, p.getInt("label_weight"))
        assertFalse(p.has("weight_used"))
    }

    @Test
    fun `a remaining estimate becomes grams used, but a zero one is ignored`() {
        assertEquals(360.0, SlotSpool.payload(tray(remain = 64)).getDouble("weight_used"), 0.001)
        assertFalse(SlotSpool.payload(tray(remain = 0)).has("weight_used"))
        assertFalse(SlotSpool.payload(tray(remain = 100)).has("weight_used"))
    }

    @Test
    fun `finds the tray by unit and slot, and the external holder`() {
        val status = JSONObject()
            .put("ams", JSONArray().put(JSONObject().put("id", 0).put("tray",
                JSONArray().put(tray().put("id", 2)))))
            .put("vt_tray", JSONArray().put(JSONObject().put("id", 254).put("tray_type", "TPU")))
        assertEquals("PLA", SlotSpool.tray(status, 0, 2)?.getString("tray_type"))
        assertEquals("TPU", SlotSpool.tray(status, 255, 0)?.getString("tray_type"))
        assertNull(SlotSpool.tray(status, 1, 0))
        assertNull(SlotSpool.tray(null, 0, 0))
    }

    @Test
    fun `finds the spool already assigned to a slot`() {
        val assignments = listOf(
            JSONObject().put("printer_id", 1).put("ams_id", 0).put("tray_id", 2)
                .put("spool", JSONObject().put("id", 9))
        )
        assertEquals(9, SlotSpool.assignedSpool(assignments, 1, 0, 2)?.optInt("id"))
        assertNull(SlotSpool.assignedSpool(assignments, 1, 0, 3))
        assertNull(SlotSpool.assignedSpool(assignments, 2, 0, 2))
    }

    @Test
    fun `describe says the filament and how much is left`() {
        assertEquals("PLA Basic · 64%", SlotSpool.describe(tray(remain = 64)))
        assertEquals("PLA Basic", SlotSpool.describe(tray()))
    }
}
