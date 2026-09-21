package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.util.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoolsTest {

    private fun spool(
        brand: String? = "Bambu",
        material: String? = "PLA",
        subtype: String? = "PLA Basic",
        colorName: String? = "Black",
        labelWeight: Int = 1000,
        weightUsed: Double = 250.0,
        note: String? = null,
        location: String? = null
    ): JSONObject {
        val o = JSONObject()
        o.put("id", 7)
        brand?.let { o.put("brand", it) }
        material?.let { o.put("material", it) }
        subtype?.let { o.put("subtype", it) }
        colorName?.let { o.put("color_name", it) }
        o.put("label_weight", labelWeight)
        o.put("weight_used", weightUsed)
        note?.let { o.put("note", it) }
        location?.let { o.put("storage_location", it) }
        return o
    }

    // ------------------------------------------------------------- searching

    @Test
    fun `every word of the query has to match, in any order`() {
        val s = spool()
        assertTrue(Spools.matches(s, "pla black"))
        assertTrue(Spools.matches(s, "black bambu"))
        assertTrue(Spools.matches(s, "BLACK"))
        assertFalse(Spools.matches(s, "pla red"))
    }

    @Test
    fun `an empty query matches everything`() {
        assertTrue(Spools.matches(spool(), ""))
        assertTrue(Spools.matches(spool(), "   "))
    }

    @Test
    fun `search reaches the note and the location, not just the name`() {
        val s = spool(note = "wet, needs drying", location = "Drybox 2")
        assertTrue(Spools.matches(s, "drybox"))
        assertTrue(Spools.matches(s, "wet"))
    }

    @Test
    fun `a spool missing most of its fields is still searchable`() {
        val bare = JSONObject().put("id", 1).put("material", "PETG")
        assertTrue(Spools.matches(bare, "petg"))
        assertFalse(Spools.matches(bare, "bambu"))
    }

    // -------------------------------------------------------------- weights

    @Test
    fun `grams left is the label weight minus what was used`() {
        assertEquals(750.0, Spools.gramsLeft(spool()), 0.001)
    }

    @Test
    fun `an overdrawn spool reads as empty rather than negative`() {
        assertEquals(0.0, Spools.gramsLeft(spool(labelWeight = 1000, weightUsed = 1200.0)), 0.001)
    }

    @Test
    fun `consumed counts from the baseline the reset moves`() {
        val s = spool(weightUsed = 400.0).put("weight_used_baseline", 250.0)
        assertEquals(150.0, Spools.consumed(s), 0.001)
    }

    // ------------------------------------------------------------- editing

    @Test
    fun `an unchanged form sends nothing at all`() {
        val s = spool()
        val payload = Spools.editPayload(s, "750", "1000", "", null).getOrThrow()
        assertNull(payload)
    }

    @Test
    fun `correcting grams left sends weight_used and nothing else`() {
        val s = spool()
        val payload = Spools.editPayload(s, "600", "1000", "", null).getOrThrow()!!
        assertEquals(400.0, payload.getDouble("weight_used"), 0.001)
        assertFalse(payload.has("label_weight"))
        assertFalse(payload.has("note"))
    }

    /**
     * The server locks a spool's weight against AMS updates whenever weight_used
     * is written, so an edit that only changes the note must not carry a weight.
     */
    @Test
    fun `editing only the note leaves the weight alone`() {
        val s = spool()
        val payload = Spools.editPayload(s, "750", "1000", "keep dry", null).getOrThrow()!!
        assertEquals("keep dry", payload.getString("note"))
        assertFalse(payload.has("weight_used"))
        assertFalse(payload.has("weight_locked"))
    }

    @Test
    fun `changing the full spool weight keeps grams left where the user put it`() {
        val s = spool(labelWeight = 1000, weightUsed = 250.0)
        // 750 g left on a 500 g spool is not possible; on a 800 g one it is.
        val payload = Spools.editPayload(s, "750", "800", "", null).getOrThrow()!!
        assertEquals(800, payload.getInt("label_weight"))
        assertEquals(50.0, payload.getDouble("weight_used"), 0.001)
    }

    @Test
    fun `clearing the note sends an explicit null`() {
        val s = spool(note = "old note")
        val payload = Spools.editPayload(s, "750", "1000", "  ", null).getOrThrow()!!
        assertTrue(payload.isNull("note"))
    }

    @Test
    fun `clearing the location sends an explicit null`() {
        val s = spool(location = "Drybox 2")
        val payload = Spools.editPayload(s, "750", "1000", "", null).getOrThrow()!!
        assertTrue(payload.isNull("storage_location"))
    }

    @Test
    fun `setting a location sends its name`() {
        val s = spool()
        val payload = Spools.editPayload(s, "750", "1000", "", "Drybox 2").getOrThrow()!!
        assertEquals("Drybox 2", payload.getString("storage_location"))
    }

    @Test
    fun `a location that did not change is not resent`() {
        val s = spool(location = "Drybox 2")
        val payload = Spools.editPayload(s, "750", "1000", "", "Drybox 2").getOrThrow()
        assertNull(payload)
    }

    @Test
    fun `more left than the spool holds is refused`() {
        val result = Spools.editPayload(spool(), "1500", "1000", "", null)
        assertTrue(result.isFailure)
    }

    @Test
    fun `a blank or nonsense weight is refused rather than sent as zero`() {
        assertTrue(Spools.editPayload(spool(), "", "1000", "", null).isFailure)
        assertTrue(Spools.editPayload(spool(), "750", "abc", "", null).isFailure)
        assertTrue(Spools.editPayload(spool(), "-5", "1000", "", null).isFailure)
        assertTrue(Spools.editPayload(spool(), "750", "0", "", null).isFailure)
    }

    // -------------------------------------------------------------- reading

    @Test
    fun `archived is decided by the timestamp the server stamps`() {
        assertFalse(Spools.isArchived(spool()))
        assertTrue(Spools.isArchived(spool().put("archived_at", "2026-09-21T14:00:00Z")))
    }

    @Test
    fun `a null archived_at is not an archived spool`() {
        assertFalse(Spools.isArchived(spool().put("archived_at", JSONObject.NULL)))
    }

    @Test
    fun `the summary says what is left and where it is`() {
        val s = spool(location = "Drybox 2").put("tag_uid", "A1B2C3D4")
        val summary = Spools.summary(s)
        assertTrue(summary.contains("750 g left"))
        assertTrue(summary.contains("tagged"))
        assertTrue(summary.contains("Drybox 2"))
    }

    @Test
    fun `short date reads as a person would say it`() {
        assertEquals("21 Sep", Spools.shortDate("2026-09-21T14:53:35.123456Z"))
        assertEquals("1 Jan", Spools.shortDate("2026-01-01T00:00:00Z"))
    }

    @Test
    fun `an unreadable date is dropped rather than shown raw`() {
        assertEquals("", Spools.shortDate(null))
        assertEquals("", Spools.shortDate("not a date"))
        assertEquals("", Spools.shortDate("2026-13-01T00:00:00Z"))
    }

    @Test
    fun `a usage line names the print and what it took`() {
        val entry = JSONObject()
            .put("created_at", "2026-09-20T09:00:00Z")
            .put("print_name", "Benchy")
            .put("weight_used", 12.4)
            .put("status", "completed")
        assertEquals("20 Sep · Benchy · 12 g", Spools.usageLine(entry))
    }

    @Test
    fun `a usage line calls out a print that did not finish`() {
        val entry = JSONObject()
            .put("created_at", "2026-09-20T09:00:00Z")
            .put("weight_used", 3.0)
            .put("status", "failed")
        assertEquals("20 Sep · Unnamed print · 3 g · failed", Spools.usageLine(entry))
    }

    @Test
    fun `location options keep the saved name apart from the shown label`() {
        val locations = JSONArray()
            .put(JSONObject().put("id", 1).put("name", "Drybox 2").put("spool_count", 3))
            .put(JSONObject().put("id", 2).put("name", "Shelf").put("spool_count", 0))
        val options = Spools.locationOptions(locations.objects())
        assertEquals(listOf("Drybox 2", "Shelf"), options.map { it.first })
        assertEquals("Drybox 2  (3)", options[0].second)
        assertEquals("Shelf", options[1].second)
    }
}
