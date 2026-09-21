package io.github.krzkawa.bambuddyaio.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
