package io.github.krzkawa.bambuddyaio.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTest {

    @Test
    fun aStatusMessageNamesItsPrinter() {
        val text = """{"type":"printer_status","printer_id":2,"data":{"state":"RUNNING","ams":[]}}"""
        assertEquals(2, Live.printerIdOf(text))
    }

    @Test
    fun printStartAndFinishAlsoRing() {
        assertEquals(1, Live.printerIdOf("""{"type":"print_start","printer_id":1,"data":{}}"""))
        assertEquals(1, Live.printerIdOf("""{"type":"print_complete","printer_id":1,"data":{}}"""))
    }

    @Test
    fun everythingElseIsIgnored() {
        assertNull(Live.printerIdOf("""{"type":"pong"}"""))
        // Upload progress arrives many times a second and is not a status change.
        assertNull(Live.printerIdOf("""{"type":"queue_item_upload_progress","printer_id":1,"percent":40}"""))
        assertNull(Live.printerIdOf("""{"type":"printer_status","data":{}}"""))
        assertNull(Live.printerIdOf("not json"))
        assertNull(Live.printerIdOf(""))
    }

    @Test
    fun reconnectsBackOffAndLevelOutAtAMinute() {
        assertEquals(2_000L, Live.backoffMs(1))
        assertEquals(4_000L, Live.backoffMs(2))
        assertEquals(32_000L, Live.backoffMs(5))
        assertEquals(60_000L, Live.backoffMs(6))
        assertEquals(60_000L, Live.backoffMs(500))
    }

    @Test
    fun jitterOnlyEverStretchesTheWaitByAFifth() {
        assertEquals(72_000L, Live.backoffMs(9, jitter = 1.0))
        assertEquals(2_400L, Live.backoffMs(1, jitter = 1.0))
        assertTrue(Live.backoffMs(3, jitter = 0.5) in 8_000L..9_600L)
    }
}
