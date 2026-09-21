package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.nfc.SpoolTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a scanned spool carries into Bambuddy's inventory.
 *
 * `SpoolCreate` is a pydantic model, so a key it does not know is dropped without an
 * error. These tests pin the names and the shapes that were read off the server's own
 * schema, because a wrong one fails silently and the spool just arrives incomplete.
 */
class SpoolPayloadTest {

    private val full = SpoolTag(
        tagUid = "0411223344",
        trayUuid = "0123456789ABCDEF0123456789ABCDEF",
        source = SpoolTag.Source.BAMBU,
        brand = "Bambu Lab",
        material = "PLA",
        detailedType = "PLA Basic",
        rgba = "FF6A13FF",
        secondRgba = "1A2B3CFF",
        colorCount = 2,
        filamentWeightG = 1000,
        diameterMm = 1.75,
        nozzleTempMin = 190,
        nozzleTempMax = 230,
        bedTemp = 45,
        dryingTemp = 55,
        dryingHours = 8,
        materialId = "GFA00",
        variantId = "A00-K0",
        producedAt = "2023_10_12_08_30",
        lengthM = 330,
        spoolWidthMm = 66.0,
        nozzleDiameterMm = 0.4
    )

    @Test
    fun `the obvious fields go as they always did`() {
        val json = SpoolPayload.from(full)

        assertEquals("PLA", json.getString("material"))
        assertEquals("PLA Basic", json.getString("subtype"))
        assertEquals("Bambu Lab", json.getString("brand"))
        assertEquals("FF6A13FF", json.getString("rgba"))
        assertEquals(1000, json.getInt("label_weight"))
        assertEquals(190, json.getInt("nozzle_temp_min"))
        assertEquals(230, json.getInt("nozzle_temp_max"))
        assertEquals("0411223344", json.getString("tag_uid"))
        assertEquals("0123456789ABCDEF0123456789ABCDEF", json.getString("tray_uuid"))
        assertEquals("nfc", json.getString("data_origin"))
    }

    @Test
    fun `a Bambu tag is recorded the way Bambuddy records its own`() {
        assertEquals("bambulab", SpoolPayload.from(full).getString("tag_type"))
        assertEquals(
            "openspool",
            SpoolPayload.from(full.copy(source = SpoolTag.Source.OPENSPOOL)).getString("tag_type")
        )
    }

    @Test
    fun `a dual-colour spool keeps its second colour`() {
        val json = SpoolPayload.from(full)

        // Comma-separated hex tokens, lowercase and without a '#', is the only
        // shape the server's validator accepts.
        assertEquals("1a2b3cff", json.getString("extra_colors"))
        // Without this the colour is stored and never drawn.
        assertEquals("dual-color", json.getString("effect_type"))
    }

    @Test
    fun `a single-colour spool declares neither`() {
        val json = SpoolPayload.from(full.copy(secondRgba = null, colorCount = 1))

        assertFalse(json.has("extra_colors"))
        assertFalse(json.has("effect_type"))
    }

    @Test
    fun `the filament id goes where the slicer and the K-profile look for it`() {
        assertEquals("GFA00", SpoolPayload.from(full).getString("slicer_filament"))
    }

    @Test
    fun `what the schema has no column for is kept in the note`() {
        val note = SpoolPayload.from(full).getString("note")

        assertTrue(note, note.contains("1.75 mm filament"))
        assertTrue(note, note.contains("330 m"))
        assertTrue(note, note.contains("66.0 mm spool"))
        assertTrue(note, note.contains("0.4 mm nozzle"))
        assertTrue(note, note.contains("bed 45°"))
        assertTrue(note, note.contains("dry 55° for 8 h"))
        assertTrue(note, note.contains("variant A00-K0"))
        assertTrue(note, note.contains("made 2023_10_12_08_30"))
    }

    @Test
    fun `a tag that gave up nothing extra gets no note`() {
        val bare = SpoolTag(tagUid = "0411223344", material = "PLA")
        assertNull(SpoolPayload.note(bare))
        assertFalse(SpoolPayload.from(bare).has("note"))
    }

    @Test
    fun `a drying temperature without a time still reads properly`() {
        val note = SpoolPayload.note(full.copy(dryingHours = null))
        assertTrue(note!!, note.contains("dry 55°"))
        assertFalse(note, note.contains("for"))
    }

    @Test
    fun `a tag with no material still names one, because the server demands it`() {
        val json = SpoolPayload.from(SpoolTag(tagUid = "0411223344"))

        assertEquals("Unknown", json.getString("material"))
        assertEquals(1000, json.getInt("label_weight"))
    }

    @Test
    fun `a colour that is not eight hex characters is left out rather than rejected`() {
        val json = SpoolPayload.from(full.copy(rgba = "FF6A13", secondRgba = "nope"))

        assertFalse(json.has("rgba"))
        assertFalse(json.has("extra_colors"))
        assertFalse(json.has("effect_type"))
    }

    @Test
    fun `a very long material name is cut to what the column takes`() {
        val json = SpoolPayload.from(full.copy(material = "P".repeat(80)))
        assertEquals(50, json.getString("material").length)
    }
}
