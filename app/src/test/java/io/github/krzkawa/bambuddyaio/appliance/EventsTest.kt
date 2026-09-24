package io.github.krzkawa.bambuddyaio.appliance

import io.github.krzkawa.bambuddyaio.appliance.Events.Kind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventsTest {

    private fun status(state: String, job: String? = "benchy.gcode.3mf", left: Int? = null) =
        JSONObject().put("state", state).put("connected", true).also {
            if (job != null) it.put("subtask_name", job)
            if (left != null) it.put("remaining_time", left)
        }

    private fun kinds(prev: JSONObject?, next: JSONObject?) =
        Events.between(1, "X1C", prev, next).map { it.kind }

    @Test
    fun nothingOnTheFirstLook() {
        assertEquals(emptyList<Kind>(), kinds(null, status("FINISH")))
    }

    @Test
    fun finishingAPrintIsAnnouncedOnce() {
        val events = Events.between(1, "X1C", status("RUNNING"), status("FINISH"))
        assertEquals(listOf(Kind.FINISHED), events.map { it.kind })
        assertEquals("X1C finished benchy", events[0].line)
        assertEquals(emptyList<Kind>(), kinds(status("FINISH"), status("FINISH")))
    }

    @Test
    fun aFinishSeenAfterReconnectingFromIdleIsNotNews() {
        assertEquals(emptyList<Kind>(), kinds(status("IDLE"), status("FINISH")))
    }

    @Test
    fun failingOutOfAPause() {
        assertEquals(listOf(Kind.FAILED), kinds(status("PAUSE"), status("FAILED")))
    }

    @Test
    fun pauseNamesTheSlotThatRanOut() {
        val paused = status("PAUSE").put("previous_tray", 2).put("expected_tray", 3)
        val events = Events.between(1, "X1C", status("RUNNING"), paused) { "AMS 1 slot ${it + 1}" }
        assertEquals(listOf(Kind.PAUSED), events.map { it.kind })
        assertEquals("X1C paused — AMS 1 slot 3 ran out", events[0].line)
        assertEquals("X1C has paused. AMS 1 slot 3 ran out.", events[0].speech)
    }

    @Test
    fun noRunoutWordsForTheFirmwaresEmptyMarker() {
        val paused = status("PAUSE").put("previous_tray", 255).put("expected_tray", 254)
        assertEquals("X1C paused", Events.between(1, "X1C", status("RUNNING"), paused)[0].line)
    }

    private fun fault(code: String, severity: Int, description: String) =
        JSONObject().put("full_code", code).put("code", "0x$code").put("severity", severity)
            .put("description", description).put("actions", JSONArray())

    @Test
    fun onlyNewFaultsAreAnnounced() {
        val old = fault("0500040000020001", 2, "Door open")
        val new = fault("0300120000010001", 1, "Nozzle clog")
        val prev = status("RUNNING").put("hms_errors", JSONArray().put(old))
        val next = status("RUNNING").put("hms_errors", JSONArray().put(old).put(new))
        val events = Events.between(1, "X1C", prev, next)
        assertEquals(listOf(Kind.FAULT), events.map { it.kind })
        assertEquals("X1C: Nozzle clog", events[0].line)
    }

    @Test
    fun informationalFaultsStayQuiet() {
        val next = status("RUNNING").put("hms_errors", JSONArray().put(fault("0C00030000010001", 4, "Tip")))
        assertEquals(emptyList<Kind>(), kinds(status("RUNNING"), next))
    }

    @Test
    fun almostDoneFiresAsTheCountdownCrossesFiveMinutes() {
        assertEquals(listOf(Kind.ALMOST_DONE), kinds(status("RUNNING", left = 7), status("RUNNING", left = 5)))
        assertEquals(emptyList<Kind>(), kinds(status("RUNNING", left = 5), status("RUNNING", left = 4)))
        assertEquals(emptyList<Kind>(), kinds(status("RUNNING", left = 0), status("RUNNING", left = 0)))
    }

    @Test
    fun droppingOffline() {
        val gone = status("RUNNING").put("connected", false)
        assertEquals(listOf(Kind.OFFLINE), kinds(status("RUNNING"), gone))
    }

    @Test
    fun theLoudestToneWins() {
        val a = Events.between(1, "A", status("RUNNING"), status("FINISH"))
        val b = Events.between(2, "B", status("RUNNING"), status("FAILED"))
        assertEquals(Events.Tone.BAD, Events.toneOf(a + b))
        assertEquals(Events.Tone.GOOD, Events.toneOf(a))
    }

    @Test
    fun jobNamesLoseTheirExtensions() {
        assertEquals("cube", Events.jobName(JSONObject().put("gcode_file", "/cache/cube.gcode")))
        assertEquals("benchy", Events.jobName(JSONObject().put("subtask_name", "benchy.gcode.3mf")))
        assertTrue(Events.jobName(JSONObject()) == null)
    }
}
