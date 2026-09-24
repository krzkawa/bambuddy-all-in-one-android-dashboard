package io.github.krzkawa.bambuddyaio.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Third-party spools that are not Bambu usually carry an OpenSpool NDEF record, so this
 * is how those spools get a usable name instead of an error.
 */
class OpenSpoolTest {

    private val uid = "0A0B0C0D"

    private fun record(json: String): ByteArray =
        // A real NDEF text record carries a status byte and a language code first.
        byteArrayOf(0x02) + "en$json".toByteArray(Charsets.UTF_8)

    @Test
    fun `reads an OpenSpool record`() {
        val payload = record(
            """{"protocol":"openspool","version":"1.0","type":"PETG","brand":"Polymaker",""" +
                """"color_hex":"#1A2B3C","min_temp":230,"max_temp":260}"""
        )

        val spool = OpenSpool.parse(uid, listOf(payload))!!
        assertEquals(SpoolTag.Source.OPENSPOOL, spool.source)
        assertEquals("OpenSpool tag", spool.label)
        assertEquals("PETG", spool.material)
        assertEquals("PETG", spool.title)
        assertEquals("Polymaker", spool.brand)
        assertEquals("1A2B3CFF", spool.rgba)
        assertEquals(230, spool.nozzleTempMin)
        assertEquals(260, spool.nozzleTempMax)
        assertEquals(uid, spool.tagUid)
    }

    @Test
    fun `picks the OpenSpool record out of a message that holds others`() {
        val spool = OpenSpool.parse(
            uid,
            listOf(
                record("""{"protocol":"something-else","type":"ASA"}"""),
                "not json at all".toByteArray(),
                record("""{"protocol":"OpenSpool","type":"TPU"}""")
            )
        )!!

        assertEquals("TPU", spool.material)
    }

    @Test
    fun `ignores a tag that is not OpenSpool`() {
        assertNull(OpenSpool.parse(uid, listOf(record("""{"type":"PLA"}"""))))
        assertNull(OpenSpool.parse(uid, listOf("https://example.com".toByteArray())))
        assertNull(OpenSpool.parse(uid, emptyList()))
    }

    @Test
    fun `malformed JSON does not throw`() {
        assertNull(OpenSpool.parse(uid, listOf(record("""{"protocol":"openspool","type":"""))))
    }

    @Test
    fun `colour is normalised to eight hex characters`() {
        assertEquals("1A2B3CFF", OpenSpool.normaliseColor("#1a2b3c"))
        assertEquals("1A2B3C80", OpenSpool.normaliseColor("1A2B3C80"))
        assertNull(OpenSpool.normaliseColor("#12345"))
        assertNull(OpenSpool.normaliseColor("orange"))
        assertNull(OpenSpool.normaliseColor(""))
        assertNull(OpenSpool.normaliseColor(null))
    }

    @Test
    fun `a temperature of zero is treated as absent`() {
        val spool = OpenSpool.parse(
            uid,
            listOf(record("""{"protocol":"openspool","type":"PLA","min_temp":0,"max_temp":0}"""))
        )!!

        assertNull(spool.nozzleTempMin)
        assertNull(spool.nozzleTempMax)
    }

    // ----------------------------------------------------------------- writing

    private val sticker = OpenSpool.Record(
        type = "PETG", colorHex = "1A2B3C", brand = "Polymaker", minTemp = 230, maxTemp = 260
    )

    @Test
    fun `a written record reads back as the same spool`() {
        val json = OpenSpool.encode(sticker)
        // A MIME record's payload is the JSON itself, with no language prefix.
        val spool = OpenSpool.parse(uid, listOf(json.toByteArray(Charsets.UTF_8)))!!

        assertEquals("PETG", spool.material)
        assertEquals("Polymaker", spool.brand)
        assertEquals("1A2B3CFF", spool.rgba)
        assertEquals(230, spool.nozzleTempMin)
        assertEquals(260, spool.nozzleTempMax)
    }

    @Test
    fun `temperatures are written as strings, the way the spec's example writes them`() {
        val json = OpenSpool.encode(sticker)
        assertTrue(json, json.contains("\"min_temp\":\"230\""))
        assertTrue(json, json.contains("\"version\":\"1.0\""))
        assertTrue(json, json.contains("\"protocol\":\"openspool\""))
    }

    @Test
    fun `missing temperatures are left out rather than written as zero`() {
        val json = OpenSpool.encode(sticker.copy(minTemp = null, maxTemp = null))
        assertFalse(json, json.contains("min_temp"))
        assertFalse(json, json.contains("max_temp"))
    }

    /**
     * Pins the sizing the sticker advice rests on. The spec's own example record comes to
     * 145 bytes as an NDEF message, against the 137 Android reports an NTAG213 can hold —
     * so even the shortest real record needs an NTAG215 or 216.
     */
    @Test
    fun `message size counts header, type and payload`() {
        val example = OpenSpool.Record("PLA", "FFAABB", "Generic", 220, 240)
        val json = OpenSpool.encode(example)
        assertEquals(3 + OpenSpool.MIME.length + json.toByteArray().size, OpenSpool.messageSize(json))
        assertEquals(145, OpenSpool.messageSize(json))
        assertTrue(OpenSpool.messageSize(json) > OpenSpool.NTAG213_BYTES)
        assertTrue(OpenSpool.messageSize(OpenSpool.encode(sticker)) > OpenSpool.NTAG213_BYTES)
    }

    @Test
    fun `a payload over 255 bytes takes a four byte length`() {
        val long = "x".repeat(300)
        assertEquals(1 + 1 + 4 + OpenSpool.MIME.length + 300, OpenSpool.messageSize(long))
    }

    @Test
    fun `only a blank tag or an OpenSpool one is safe to write over`() {
        assertTrue(OpenSpool.safeToOverwrite(emptyList()))
        assertTrue(OpenSpool.safeToOverwrite(listOf(ByteArray(0))))
        assertTrue(OpenSpool.safeToOverwrite(listOf(OpenSpool.encode(sticker).toByteArray())))
        assertFalse(OpenSpool.safeToOverwrite(listOf("https://example.com".toByteArray())))
        assertFalse(OpenSpool.safeToOverwrite(listOf(record("""{"type":"PLA"}"""))))
    }

    @Test
    fun `default temperatures cover the common families and nothing else`() {
        assertEquals(190 to 230, OpenSpool.defaultTemps("PLA"))
        assertEquals(190 to 230, OpenSpool.defaultTemps("pla-cf"))
        assertEquals(230 to 260, OpenSpool.defaultTemps("PETG"))
        assertEquals(260 to 290, OpenSpool.defaultTemps("PA6-CF"))
        assertNull(OpenSpool.defaultTemps("Mystery"))
        assertNull(OpenSpool.defaultTemps(null))
    }
}
