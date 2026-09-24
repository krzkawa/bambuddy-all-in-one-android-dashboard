package io.github.krzkawa.bambuddyaio.appliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BatteryChimeTest {

    @Test
    fun comingOffTheChargerIsAnEvent() {
        val on = Battery.Reading(90, 30.0, plugged = true)
        val off = Battery.Reading(90, 30.0, plugged = false)
        assertEquals(listOf(Events.Kind.UNPLUGGED), Battery.events(on, off).map { it.kind })
        assertEquals(emptyList<Events.Kind>(), Battery.events(null, off).map { it.kind })
        assertEquals(emptyList<Events.Kind>(), Battery.events(off, off).map { it.kind })
    }

    @Test
    fun warningsSayWhatIsWrong() {
        assertNull(Battery.warning(Battery.Reading(100, 32.0, plugged = true)))
        assertTrue(Battery.warning(Battery.Reading(100, 47.0, plugged = true))!!.contains("47°C"))
        assertEquals("Phone on battery, 15% left", Battery.warning(Battery.Reading(15, 30.0, plugged = false)))
    }

    @Test
    fun chimesAreTheLengthTheySayAndNeverClip() {
        for (tone in Events.Tone.values()) {
            val pcm = Chime.samples(tone, 1f)
            assertEquals(Chime.lengthMs(tone) * 22_050 / 1000.0, pcm.size.toDouble(), 22_050 / 100.0)
            assertTrue(pcm.all { abs(it.toInt()) < Short.MAX_VALUE })
            assertTrue(pcm.any { abs(it.toInt()) > 10_000 })
        }
    }

    @Test
    fun quietIsQuieter() {
        val loud = Chime.samples(Events.Tone.GOOD, 1f).maxOf { abs(it.toInt()) }
        val quiet = Chime.samples(Events.Tone.GOOD, 0.3f).maxOf { abs(it.toInt()) }
        assertTrue(quiet < loud / 2)
    }
}
