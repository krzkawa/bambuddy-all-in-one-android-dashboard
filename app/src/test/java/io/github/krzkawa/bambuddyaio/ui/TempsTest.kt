package io.github.krzkawa.bambuddyaio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TempsTest {

    @Test
    fun `a step builds on what he last asked for, not on what the printer reports`() {
        // Two taps from cold aim at 20, even while the nozzle is climbing past 160.
        var pending = Temps.startingPoint(0.0)
        pending = Temps.step(pending, 10, Temps.NOZZLE_MAX)
        pending = Temps.step(pending, 10, Temps.NOZZLE_MAX)
        assertEquals(20, pending)
    }

    @Test
    fun `steps stop at the heater's ceiling and at cold`() {
        assertEquals(Temps.NOZZLE_MAX, Temps.step(295, 10, Temps.NOZZLE_MAX))
        assertEquals(0, Temps.step(5, -10, Temps.NOZZLE_MAX))
        assertEquals(Temps.BED_MAX, Temps.step(115, 10, Temps.BED_MAX))
    }

    @Test
    fun `a typed temperature is taken as it was typed`() {
        assertEquals(220, Temps.typed("220", Temps.NOZZLE_MAX))
        assertEquals(60, Temps.typed(" 60 ", Temps.BED_MAX))
        assertEquals(0, Temps.typed("0", Temps.BED_MAX))
    }

    @Test
    fun `a degree sign he typed out of habit is not a reason to refuse him`() {
        assertEquals(220, Temps.typed("220°", Temps.NOZZLE_MAX))
    }

    @Test
    fun `a number past the ceiling is refused rather than quietly changed`() {
        // Clamping 2200 to 300 would send the printer somewhere he never asked for
        // and he would never learn that the digit slipped.
        assertNull(Temps.typed("2200", Temps.NOZZLE_MAX))
        assertNull(Temps.typed("-20", Temps.NOZZLE_MAX))
        assertNull(Temps.typed("121", Temps.BED_MAX))
    }

    @Test
    fun `anything that is not a temperature is refused`() {
        assertNull(Temps.typed("", Temps.NOZZLE_MAX))
        assertNull(Temps.typed("hot", Temps.NOZZLE_MAX))
        assertNull(Temps.typed("21.5", Temps.NOZZLE_MAX))
        assertNull(Temps.typed(null, Temps.NOZZLE_MAX))
    }

    @Test
    fun `a heater with no target starts from cold`() {
        assertEquals(0, Temps.startingPoint(null))
        assertEquals(0, Temps.startingPoint(-5.0))
        assertEquals(210, Temps.startingPoint(210.0))
    }

    @Test
    fun `the settle window is long enough to cover a run of taps`() {
        // Short enough that a single tap still feels immediate, long enough that
        // a quick run of them lands as one command to the printer.
        assertEquals(600L, Temps.SETTLE_MS)
    }
}
