package io.github.krzkawa.bambuddyaio.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BambuKeysTest {

    /**
     * Expected values come from an independent Python implementation of the published
     * derivation — HKDF-SHA256, the fixed master salt, context "RFID-A\u0000", 96 bytes
     * cut into sixteen 6-byte keys. If this fails, the Kotlin HKDF has drifted, and every
     * genuine spool would stop reading.
     */
    @Test
    fun `derives the published key set for a known UID`() {
        val expected = listOf(
            "03A4F0B4F9C7", "056E45495385", "3FF60B7B5102", "EF2EDC12848F",
            "275AFE4BE3F6", "664CDF464559", "5910D9BB3EC5", "5A4A6CDBCA11",
            "26789A561EA6", "02D8705FAF39", "83D577FE0737", "9077409059D3",
            "C54E950674A8", "7F8EB4C5764C", "92EAB71C7822", "E0CFF2654F2A"
        )

        val keys = BambuTag.deriveKeys(byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte()))

        assertEquals(expected, keys.map { BambuBlocks.hex(it) })
    }

    @Test
    fun `derives sixteen six-byte keys`() {
        val keys = BambuKeys.deriveKeys(byteArrayOf(0x0F, 0x1E, 0x2D, 0x3C))

        assertEquals(BambuKeys.SECTORS, keys.size)
        assertTrue(keys.all { it.size == BambuKeys.KEY_LENGTH })
        assertEquals("07ED41947020", BambuBlocks.hex(keys[0]))
    }

    /** Some Mifare tags carry a 7-byte UID, so the derivation must not assume four. */
    @Test
    fun `handles a UID that is not four bytes`() {
        val keys = BambuKeys.deriveKeys(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))

        assertEquals("E76B2F1433C0", BambuBlocks.hex(keys[0]))
    }

    @Test
    fun `a one-bit change in the UID changes every key`() {
        val a = BambuKeys.deriveKeys(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val b = BambuKeys.deriveKeys(byteArrayOf(0x01, 0x02, 0x03, 0x05))

        for (sector in 0 until BambuKeys.SECTORS) {
            assertNotEquals(BambuBlocks.hex(a[sector]), BambuBlocks.hex(b[sector]))
        }
    }

    @Test
    fun `the derivation is deterministic`() {
        val uid = byteArrayOf(0x11, 0x22, 0x33, 0x44)

        assertEquals(
            BambuKeys.deriveKeys(uid).map { BambuBlocks.hex(it) },
            BambuKeys.deriveKeys(uid).map { BambuBlocks.hex(it) }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an empty UID`() {
        BambuKeys.deriveKeys(ByteArray(0))
    }
}
