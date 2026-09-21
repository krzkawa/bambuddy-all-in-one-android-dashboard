package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/**
 * The printer's own fault reports, in the words the server already has for them.
 *
 * Bambuddy's `HMSErrorResponse` carries `description` — added precisely so a
 * client would not have to ship its own fault table — alongside `code`,
 * `full_code`, `severity` and the action keys that `hms/execute-action` takes.
 * There is no `text` field, and org.json answers a missing key with silence, so
 * asking for one produced a placeholder on every error the printer ever raised.
 *
 * Android-free on purpose, so the parsing can be tested without a printer.
 */
object Hms {

    const val FATAL = 1
    const val SERIOUS = 2
    const val COMMON = 3
    const val INFO = 4

    data class Fault(
        /** What went wrong, in words a person can act on. */
        val description: String,
        val code: String?,
        val fullCode: String?,
        /** 1 fatal, 2 serious, 3 common, 4 info; null when the server said nothing. */
        val severity: Int?,
        /** Action keys `hms/execute-action` accepts for this fault. */
        val actions: List<String>,
        /**
         * The print this fault belongs to, which Bambu echoes back in an
         * HMS-aware command. Absent for a fault raised while idle.
         */
        val jobId: String? = null
    ) {
        /** The small print under the description: how bad it is, and its code. */
        val detail: String?
            get() = listOfNotNull(severityName(severity), code).joinToString(" · ").ifBlank { null }

        /**
         * The actions this app can actually send for this fault.
         *
         * `execute-action` matches on the firmware's own hex key and rejects
         * anything else outright, so a fault whose `full_code` did not arrive
         * offers nothing rather than a button that answers 422.
         */
        val runnableActions: List<String>
            get() = if (isFirmwareKey(fullCode)) actions else emptyList()
    }

    /**
     * Whether a code is the key the firmware matches on: 8 hex characters for a
     * `print_error` fault, 16 for one from the `hms[]` array
     * (`bambu_mqtt.py:4790`). `code` is a `0x…` display string and never
     * qualifies, which is why this is checked rather than assumed.
     */
    fun isFirmwareKey(fullCode: String?): Boolean =
        fullCode != null && (fullCode.length == 8 || fullCode.length == 16) &&
            fullCode.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Every fault on a status, worst first, so a card can lead with the one that matters. */
    fun faults(status: JSONObject?): List<Fault> =
        status?.objects("hms_errors").orEmpty()
            .map { read(it) }
            .sortedBy { it.severity ?: INFO }

    fun read(error: JSONObject): Fault {
        val code = error.str("code") ?: error.str("full_code")
        return Fault(
            description = error.str("description") ?: code ?: "Printer reported an error",
            code = code,
            fullCode = error.str("full_code") ?: code,
            severity = error.int("severity")?.takeIf { it in FATAL..INFO },
            actions = actionsOf(error),
            jobId = error.str("job_id")
        )
    }

    private fun actionsOf(error: JSONObject): List<String> {
        val raw = error.optJSONArray("actions") ?: return emptyList()
        val out = ArrayList<String>(raw.length())
        for (i in 0 until raw.length()) {
            raw.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out
    }

    /**
     * What a suggested action's key means, in the words Bambuddy's own screen
     * uses for it (`frontend/src/i18n/locales/en.ts`). The keys come from
     * BambuStudio, misspellings and all, so an unknown one is shown as it
     * arrived rather than hidden: the printer offered it for a reason.
     */
    fun actionLabel(action: String): String = LABELS[action] ?: tidy(action)

    private fun tidy(action: String): String =
        action.split('_').filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }.ifBlank { action }

    private val LABELS = mapOf(
        "RESUME_PRINTING" to "Resume printing",
        "RESUME_PRINTING_DEFECTS" to "Resume, defects acceptable",
        "RESUME_PRINTING_PROBELM_SOLVED" to "Resume, problem solved",
        "STOP_PRINTING" to "Stop printing",
        "CHECK_ASSISTANT" to "Check assistant",
        "FILAMENT_EXTRUDED" to "Filament extruded, continue",
        "RETRY_FILAMENT_EXTRUDED" to "Not extruded yet, retry",
        "CONTINUE" to "Finished, continue",
        "LOAD_VIRTUAL_TRAY" to "Load filament",
        "OK_BUTTON" to "OK",
        "FILAMENT_LOAD_RESUME" to "Filament loaded, resume",
        "JUMP_TO_LIVEVIEW" to "View the camera",
        "NO_REMINDER_NEXT_TIME" to "No reminder next time",
        "REFRESH_NOZZLE" to "Recheck the nozzle",
        "IGNORE_NO_REMINDER_NEXT_TIME" to "Ignore, and do not remind me",
        "IGNORE_RESUME" to "Ignore this and resume",
        "PROBLEM_SOLVED_RESUME" to "Problem solved, resume",
        "TURN_OFF_FIRE_ALARM" to "Got it, turn off the fire alarm",
        "RETRY_PROBLEM_SOLVED" to "Retry, problem solved",
        "STOP_DRYING" to "Stop drying",
        // Spelled the way BambuStudio spells it; the key is matched verbatim.
        "CANCLE" to "Cancel",
        "REMOVE_CLOSE_BTN" to "Close",
        "PROCEED" to "Proceed",
        "OK_JUMP_RACK" to "OK",
        "ABORT" to "Abort",
        "DISABLE_PURIFICATION" to "Disable purification for this print",
        "DONT_REMIND_NEXT_TIME" to "Do not remind me",
        "DBL_CHECK_CANCEL" to "Cancel",
        "DBL_CHECK_DONE" to "Done",
        "DBL_CHECK_RETRY" to "Retry",
        "DBL_CHECK_RESUME" to "Resume",
        "DBL_CHECK_OK" to "Confirm"
    )

    /**
     * Actions that stop, abandon or override a running print, which get a
     * confirmation. Everything else is a nudge he can safely repeat.
     */
    fun isWeighty(action: String): Boolean = action in WEIGHTY

    private val WEIGHTY = setOf(
        "STOP_PRINTING",
        "ABORT",
        "RESUME_PRINTING_DEFECTS",
        "IGNORE_RESUME",
        "IGNORE_NO_REMINDER_NEXT_TIME",
        "TURN_OFF_FIRE_ALARM",
        "DISABLE_PURIFICATION"
    )

    fun severityName(severity: Int?): String? = when (severity) {
        FATAL -> "Fatal"
        SERIOUS -> "Serious"
        COMMON -> "Warning"
        INFO -> "Notice"
        else -> null
    }
}
