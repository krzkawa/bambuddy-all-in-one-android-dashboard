package io.github.krzkawa.bambuddyaio.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Field-level parsing, driven straight from dump blocks with the radio path skipped. */
class BambuBlocksTest {

    private val uid = "A1B2C3D4"

    private fun blocks() = TagDump.load("pla_basic_jade_white.txt").dataBlocks()

    private fun parseWith(mutate: MutableMap<Int, ByteArray>.() -> Unit): SpoolTag =
        BambuBlocks.parse(uid, blocks().apply(mutate))

    @Test
    fun `reads the colour as the RRGGBBAA the rest of the app uses`() {
        val spool = parseWith {
            put(5, getValue(5).copyOf().also {
                it[0] = 0xFF.toByte(); it[1] = 0x6A; it[2] = 0x13; it[3] = 0xFF.toByte()
            })
        }

        assertEquals("FF6A13FF", spool.rgba)
    }

    @Test
    fun `a missing block leaves its fields blank rather than guessing`() {
        val spool = parseWith { remove(6) }

        assertNull(spool.bedTemp)
        assertNull(spool.nozzleTempMax)
        assertNull(spool.dryingTemp)
        // The fields from other blocks are unaffected.
        assertEquals("PLA Basic", spool.detailedType)
    }

    @Test
    fun `a short block is ignored rather than causing an index crash`() {
        val spool = parseWith { put(6, ByteArray(8)) }

        assertNull(spool.bedTemp)
        assertEquals("PLA", spool.material)
    }

    @Test
    fun `an implausible diameter is dropped rather than passed on to the slicer`() {
        val spool = parseWith {
            put(5, getValue(5).copyOf().also { java.util.Arrays.fill(it, 8, 12, 0.toByte()) })
        }

        assertNull(spool.diameterMm)
    }

    @Test
    fun `an absurd spool weight is dropped`() {
        val spool = parseWith {
            put(5, getValue(5).copyOf().also { it[4] = 0xFF.toByte(); it[5] = 0xFF.toByte() })
        }

        assertNull(spool.filamentWeightG)
    }

    /**
     * The point of reading strictly: a block with non-ASCII bytes must not be filtered
     * down into a shorter word that looks like a real material name.
     */
    @Test
    fun `a text field with non-ASCII bytes reads as unknown, not as a trimmed word`() {
        val spool = parseWith {
            put(2, byteArrayOf(0x50, 0x4C, 0xE2.toByte(), 0x99.toByte(), 0x41, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        }

        assertNull(spool.material)
        assertEquals("PLA Basic", spool.detailedType)
    }

    @Test
    fun `block 16 with an empty format identifier means one colour`() {
        val spool = parseWith { put(16, ByteArray(16)) }

        assertNull(spool.colorCount)
        assertNull(spool.secondRgba)
        assertEquals(false, spool.isDualColor)
    }

    @Test
    fun `a tag declaring one colour carries no second colour`() {
        val spool = parseWith {
            put(16, ByteArray(16).also {
                it[0] = 2; it[2] = 1
                it[4] = 0xFF.toByte(); it[5] = 0x99.toByte(); it[6] = 0x66; it[7] = 0x33
            })
        }

        assertEquals(1, spool.colorCount)
        assertNull(spool.secondRgba)
    }

    @Test
    fun `the parser never reports a warning while it can still name the filament`() {
        assertNull(parseWith { remove(2) }.warning)
        assertNull(parseWith { remove(4) }.warning)
        // Only when neither name survives is the tag called unreadable.
        assertEquals(
            true,
            parseWith { remove(2); remove(4) }.warning!!.contains("unreadable")
        )
    }

    @Test
    fun `two scans of the same spool compare equal`() {
        assertEquals(BambuBlocks.parse(uid, blocks()), BambuBlocks.parse(uid, blocks()))
    }
}
