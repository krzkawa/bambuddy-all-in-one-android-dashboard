package io.github.krzkawa.bambuddyaio.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The read path end to end: derive keys, authenticate, read blocks, parse. */
class BambuSectorsTest {

    @Test
    fun `decodes a PLA Basic spool`() {
        val spool = TagDump.scan("pla_basic_jade_white.txt")

        assertEquals(SpoolTag.Source.BAMBU, spool.source)
        assertEquals("A1B2C3D4", spool.tagUid)
        assertEquals("GFA00", spool.materialId)
        assertEquals("A00-K0", spool.variantId)
        assertEquals("PLA", spool.material)
        assertEquals("PLA Basic", spool.detailedType)
        assertEquals("Bambu Lab", spool.brand)
        assertEquals("FFFFFFFF", spool.rgba)
        assertEquals(1000, spool.filamentWeightG)
        assertEquals(1.75, spool.diameterMm!!, 0.0001)
        assertEquals(55, spool.dryingTemp)
        assertEquals(8, spool.dryingHours)
        assertEquals(35, spool.bedTemp)
        assertEquals(230, spool.nozzleTempMax)
        assertEquals(190, spool.nozzleTempMin)
        assertEquals(330, spool.lengthM)
        assertEquals(66.25, spool.spoolWidthMm!!, 0.0001)
        assertEquals("2024_10_05_14_32", spool.producedAt)
        assertEquals("24_10_05", spool.producedAtShort)
        assertEquals(0.4, spool.nozzleDiameterMm!!, 0.0001)
        assertNull(spool.warning)
        assertNull(spool.failure)
    }

    @Test
    fun `reads the two identifiers Bambuddy looks a spool up by`() {
        val spool = TagDump.scan("pla_basic_jade_white.txt")

        // tag_uid is the hex of the four UID bytes.
        assertEquals("A1B2C3D4", spool.tagUid)
        // tray_uuid is the hex of the sixteen bytes of block 9, 32 characters, which is
        // the form the AMS reports over MQTT.
        assertEquals("30313233343536373839414243444546", spool.trayUuid)
        assertEquals(32, spool.trayUuid!!.length)
    }

    @Test
    fun `decodes a dual colour spool`() {
        val spool = TagDump.scan("abs_dual_color_binary_trayuid.txt")

        assertEquals("ABS", spool.material)
        assertEquals("6A1C2E3F4D5B60718293A4B5C6D7E8F9", spool.trayUuid)
        assertTrue(spool.isDualColor)
        assertEquals(2, spool.colorCount)
        assertEquals("1A2B3CFF", spool.rgba)
        // Block 16 stores the second colour ABGR (FF 99 66 33); the app works in RRGGBBAA.
        assertEquals("336699FF", spool.secondRgba)
    }

    @Test
    fun `fields the tag leaves zeroed come back null, not zero`() {
        val spool = TagDump.scan("abs_dual_color_binary_trayuid.txt")

        assertNull(spool.spoolWidthMm)
        assertNull(spool.lengthM)
        assertNull(spool.nozzleDiameterMm)
    }

    @Test
    fun `a tag on the factory default key is not mistaken for a Bambu spool`() {
        val dump = TagDump.load("third_party_default_keys.txt")

        try {
            BambuSectors.read(dump.asSource())
            error("expected sector 0 to refuse the derived key")
        } catch (e: SectorLockedException) {
            assertEquals(0, e.sector)
        }
    }

    @Test
    fun `a clone written with a different UID fails authentication`() {
        val dump = TagDump.load("pla_basic_jade_white.txt")

        try {
            BambuSectors.read(dump.asSource(uidOverride = byteArrayOf(1, 2, 3, 4)))
            error("expected the derived keys to be rejected")
        } catch (e: SectorLockedException) {
            assertEquals(0, e.sector)
        }
    }

    @Test(expected = TagLostException::class)
    fun `pulling the tag away mid-read reports the tag as lost`() {
        val dump = TagDump.load("pla_basic_jade_white.txt")

        BambuSectors.read(dump.asSource().apply { loseTagAtBlock = 5 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty UID is refused before any radio traffic`() {
        val dump = TagDump.load("pla_basic_jade_white.txt")

        BambuSectors.read(dump.asSource(uidOverride = ByteArray(0)))
    }

    @Test
    fun `reads only the sectors holding filament data and never a key trailer`() {
        val dump = TagDump.load("pla_basic_jade_white.txt")
        val source = dump.asSource()

        BambuSectors.read(source)

        // Sectors 0-4 cover blocks 0-16; the RSA signature in sectors 10-15 is left alone,
        // which is several seconds of radio time saved with the spool held to the phone.
        assertEquals(listOf(0, 1, 2, 3, 4), source.attemptedSectors)
        assertEquals(listOf(1, 2, 4, 5, 6, 8, 9, 10, 12, 13, 14, 16), source.readBlocks)
        // Never a key trailer, and nothing from the signature sectors.
        assertTrue(source.readBlocks.none { it % BambuSectors.BLOCKS_PER_SECTOR == 3 })
    }

    @Test
    fun `an older tag missing its later sectors still decodes the fields it has`() {
        val dump = TagDump.load("pla_basic_jade_white.txt")
        val source = dump.asSource().apply { lockedSectors += setOf(3, 4) }

        val spool = BambuBlocks.parse(dump.uidHex, BambuSectors.read(source))

        assertEquals("PLA Basic", spool.detailedType)
        assertEquals(1000, spool.filamentWeightG)
        assertNull(spool.producedAt)
        assertNull(spool.lengthM)
        assertNull(spool.secondRgba)
        assertNull(spool.warning)
    }

    @Test
    fun `a tag that unlocks but holds unreadable data warns instead of showing a blank spool`() {
        val spool = TagDump.scan("corrupt_filament_type.txt")

        assertEquals(SpoolTag.Source.BAMBU, spool.source)
        assertNull(spool.material)
        assertNull(spool.detailedType)
        assertEquals("Unknown filament", spool.title)
        assertNotNull(spool.warning)
        assertEquals(ScanFailure.MALFORMED, spool.failure)
        // The UID still came through, so the spool can be linked by hand.
        assertEquals("DEADBEEF", spool.tagUid)
    }
}
