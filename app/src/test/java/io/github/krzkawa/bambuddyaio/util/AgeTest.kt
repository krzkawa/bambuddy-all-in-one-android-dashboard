package io.github.krzkawa.bambuddyaio.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AgeTest {

    @Test
    fun `a fresh reading says so rather than counting`() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(1_999))
    }

    @Test
    fun `seconds, then minutes, then hours`() {
        assertEquals("2s ago", ago(2_000))
        assertEquals("59s ago", ago(59_999))
        assertEquals("1m ago", ago(60_000))
        assertEquals("4m ago", ago(4 * 60_000L))
        assertEquals("1h ago", ago(3_600_000))
        assertEquals("2h 30m ago", ago(2 * 3_600_000L + 30 * 60_000L))
        assertEquals("1d ago", ago(25 * 3_600_000L))
    }

    @Test
    fun `a clock that has gone backwards does not print a negative age`() {
        assertEquals("just now", ago(-5_000))
    }
}
