package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.nfc.ScanFailure
import io.github.krzkawa.bambuddyaio.nfc.SpoolTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The scan screen's rule about which read wins.
 *
 * A spool sits against the phone while the user reaches for a slot button, so the same
 * tag is read over and over. Those repeats used to overwrite a good scan with whatever
 * the last brush of the spool managed to get.
 */
class ScanStateTest {

    private val good = SpoolTag(
        tagUid = "0411223344",
        trayUuid = "0123456789ABCDEF0123456789ABCDEF",
        source = SpoolTag.Source.BAMBU,
        brand = "Bambu Lab",
        material = "PLA",
        detailedType = "PLA Basic",
        rgba = "FF6A13FF",
        filamentWeightG = 1000,
        nozzleTempMin = 190,
        nozzleTempMax = 230
    )

    private val lost = SpoolTag(
        tagUid = "0411223344",
        source = SpoolTag.Source.PLAIN,
        warning = "Lost contact with the tag part-way through, twice.",
        failure = ScanFailure.TAG_LOST
    )

    @Before fun reset() = ScanState.clear()
    @After fun clear() = ScanState.clear()

    @Test
    fun `first read is taken whatever it is`() {
        ScanState.found(lost)
        assertEquals(lost, ScanState.tag.value)
        assertNull(ScanState.hiccup.value)
    }

    @Test
    fun `a failed read does not replace a good one`() {
        ScanState.found(good)
        ScanState.found(lost)

        assertEquals(good, ScanState.tag.value)
        assertEquals(lost, ScanState.hiccup.value)
    }

    @Test
    fun `a failed read of a different tag does not replace a good one either`() {
        ScanState.found(good)
        ScanState.found(lost.copy(tagUid = "04AABBCCDD"))

        assertEquals(good, ScanState.tag.value)
        assertNotNull(ScanState.hiccup.value)
    }

    @Test
    fun `a thinner read of the same tag does not replace a fuller one`() {
        ScanState.found(good)
        // Sectors 1 to 4 were skipped: the tag still decoded, with less on it.
        val thin = good.copy(
            filamentWeightG = null, nozzleTempMin = null, nozzleTempMax = null, rgba = null
        )
        ScanState.found(thin)

        assertEquals(good, ScanState.tag.value)
        assertEquals(thin, ScanState.hiccup.value)
    }

    @Test
    fun `a fuller read of the same tag does replace a thinner one`() {
        val thin = good.copy(filamentWeightG = null, nozzleTempMin = null, nozzleTempMax = null)
        ScanState.found(thin)
        ScanState.found(good)

        assertEquals(good, ScanState.tag.value)
        assertNull(ScanState.hiccup.value)
    }

    @Test
    fun `a good read of another spool replaces the one on screen`() {
        ScanState.found(good)
        val other = good.copy(tagUid = "04AABBCCDD", detailedType = "PETG HF")
        ScanState.found(other)

        assertEquals(other, ScanState.tag.value)
        assertNull(ScanState.hiccup.value)
    }

    @Test
    fun `a good read clears a banner left by an earlier failure`() {
        ScanState.found(good)
        ScanState.found(lost)
        assertNotNull(ScanState.hiccup.value)

        ScanState.found(good.copy(tagUid = "04AABBCCDD"))
        assertNull(ScanState.hiccup.value)
    }

    @Test
    fun `a read always stops the reading spinner`() {
        ScanState.reading()
        ScanState.found(good)
        assertEquals(false, ScanState.busy.value)

        ScanState.reading()
        ScanState.found(lost)
        assertEquals(false, ScanState.busy.value)
    }

    @Test
    fun `scan another wipes everything`() {
        ScanState.found(good)
        ScanState.found(lost)
        ScanState.clear()

        assertNull(ScanState.tag.value)
        assertNull(ScanState.hiccup.value)
    }
}
