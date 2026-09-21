package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The half of an HMS fault that is a button rather than a sentence. */
class HmsActionTest {

    private fun error(
        fullCode: String? = null,
        code: String? = null,
        jobId: String? = null,
        actions: List<String> = emptyList()
    ): JSONObject {
        val o = JSONObject().put("description", "Filament ran out")
        fullCode?.let { o.put("full_code", it) }
        code?.let { o.put("code", it) }
        jobId?.let { o.put("job_id", it) }
        o.put("actions", JSONArray().also { a -> actions.forEach { a.put(it) } })
        return o
    }

    @Test
    fun `the job a fault belongs to is carried, because the command echoes it back`() {
        assertEquals("1042", Hms.read(error(jobId = "1042")).jobId)
        assertNull(Hms.read(error()).jobId)
    }

    @Test
    fun `an eight character print_error key is the firmware's own`() {
        assertTrue(Hms.isFirmwareKey("05008061"))
    }

    @Test
    fun `a sixteen character hms key is too`() {
        assertTrue(Hms.isFirmwareKey("0300010000020003"))
    }

    @Test
    fun `the display code is not a firmware key`() {
        // `code` arrives as "0x5008061" and `execute-action` rejects it outright,
        // so offering a button built from it would only ever produce a 422.
        assertFalse(Hms.isFirmwareKey("0x5008061"))
        assertFalse(Hms.isFirmwareKey("0500_8061"))
        assertFalse(Hms.isFirmwareKey(""))
        assertFalse(Hms.isFirmwareKey(null))
    }

    @Test
    fun `a fault with a proper key offers its actions`() {
        val fault = Hms.read(error(fullCode = "05008061", actions = listOf("RESUME_PRINTING")))
        assertEquals(listOf("RESUME_PRINTING"), fault.runnableActions)
    }

    @Test
    fun `a fault whose key never arrived offers nothing rather than a dead button`() {
        val fault = Hms.read(error(code = "0x5008061", actions = listOf("RESUME_PRINTING")))
        assertEquals(listOf("RESUME_PRINTING"), fault.actions)
        assertTrue(fault.runnableActions.isEmpty())
    }

    @Test
    fun `an action key is shown in the words Bambuddy's own screen uses`() {
        assertEquals("Resume printing", Hms.actionLabel("RESUME_PRINTING"))
        assertEquals("Filament loaded, resume", Hms.actionLabel("FILAMENT_LOAD_RESUME"))
        assertEquals("Ignore this and resume", Hms.actionLabel("IGNORE_RESUME"))
    }

    @Test
    fun `BambuStudio's own misspelling is matched verbatim and read out properly`() {
        assertEquals("Cancel", Hms.actionLabel("CANCLE"))
    }

    @Test
    fun `an action nobody has a name for is still offered, tidied up`() {
        // The printer suggested it for a reason; hiding it helps nobody.
        assertEquals("Check Something New", Hms.actionLabel("CHECK_SOMETHING_NEW"))
    }

    @Test
    fun `the actions that abandon or override a print are asked about twice`() {
        assertTrue(Hms.isWeighty("STOP_PRINTING"))
        assertTrue(Hms.isWeighty("IGNORE_RESUME"))
        assertTrue(Hms.isWeighty("TURN_OFF_FIRE_ALARM"))
        assertFalse(Hms.isWeighty("RESUME_PRINTING"))
        assertFalse(Hms.isWeighty("OK_BUTTON"))
    }
}
