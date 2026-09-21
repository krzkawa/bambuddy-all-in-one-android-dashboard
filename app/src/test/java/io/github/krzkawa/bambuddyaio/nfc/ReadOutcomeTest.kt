package io.github.krzkawa.bambuddyaio.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Telling a tag that refused its keys apart from a tag that simply went away.
 *
 * A failure inside `MifareClassic.connect()` is a plain [IOException], and it used to
 * take the same path as a rejected key — so a spool the phone lost contact with was
 * reported to the user as not being a genuine Bambu tag.
 */
class ReadOutcomeTest {

    @Test
    fun `a connect or transceive failure is lost contact, not a rejected key`() {
        assertEquals(ReadOutcome.LOST, outcomeOf(IOException("tag was lost")))
    }

    @Test
    fun `a lost tag during the sector walk is lost contact`() {
        assertEquals(ReadOutcome.LOST, outcomeOf(TagLostException("lost the tag while reading block 5")))
    }

    @Test
    fun `sector zero refusing the derived key means this is not a Bambu tag`() {
        assertEquals(ReadOutcome.NOT_BAMBU, outcomeOf(SectorLockedException(0)))
    }

    @Test
    fun `a UID with nothing to derive keys from is not a Bambu tag`() {
        assertEquals(ReadOutcome.NOT_BAMBU, outcomeOf(IllegalArgumentException("empty uid")))
    }

    @Test
    fun `a stale tag handle is its own case, so it is not retried`() {
        assertEquals(ReadOutcome.STALE, outcomeOf(SecurityException("tag out of date")))
    }

    @Test
    fun `anything else is not the reader's to swallow`() {
        assertNull(outcomeOf(NullPointerException()))
        assertNull(outcomeOf(OutOfMemoryError()))
    }
}
