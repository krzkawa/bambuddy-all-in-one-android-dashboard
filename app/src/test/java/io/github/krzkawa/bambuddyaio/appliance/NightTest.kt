package io.github.krzkawa.bambuddyaio.appliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightTest {

    private val config = Night.Config(
        dimAfterMs = 5 * 60_000L, brightWhilePrinting = true, nightOn = true, nightFrom = 23, nightTo = 7
    )

    @Test
    fun nightHoursWrapPastMidnight() {
        assertTrue(Night.inHours(23, 23, 7))
        assertTrue(Night.inHours(3, 23, 7))
        assertFalse(Night.inHours(7, 23, 7))
        assertFalse(Night.inHours(12, 23, 7))
        assertTrue(Night.inHours(14, 13, 15))
        assertFalse(Night.inHours(5, 5, 5))
    }

    @Test
    fun daytimeWaitsForTheIdleSpell() {
        assertFalse(Night.shouldDim(config, 4 * 60_000L, 12, printing = false))
        assertTrue(Night.shouldDim(config, 6 * 60_000L, 12, printing = false))
    }

    @Test
    fun daytimePrintingStaysBrightUnlessToldOtherwise() {
        assertFalse(Night.shouldDim(config, 60 * 60_000L, 12, printing = true))
        assertTrue(Night.shouldDim(config.copy(brightWhilePrinting = false), 6 * 60_000L, 12, printing = true))
    }

    @Test
    fun nightDimsSoonEvenWhilePrinting() {
        assertFalse(Night.shouldDim(config, 20_000L, 1, printing = true))
        assertTrue(Night.shouldDim(config, 40_000L, 1, printing = true))
    }

    @Test
    fun neverMeansNeverByDay() {
        val never = config.copy(dimAfterMs = 0L, nightOn = false)
        assertFalse(Night.shouldDim(never, 24 * 3_600_000L, 2, printing = false))
        assertEquals(true, Night.shouldDim(never.copy(nightOn = true), 60_000L, 2, printing = false))
    }
}
