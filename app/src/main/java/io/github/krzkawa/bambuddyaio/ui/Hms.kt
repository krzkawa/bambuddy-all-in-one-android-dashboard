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
        val actions: List<String>
    ) {
        /** The small print under the description: how bad it is, and its code. */
        val detail: String?
            get() = listOfNotNull(severityName(severity), code).joinToString(" · ").ifBlank { null }
    }

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
            actions = actionsOf(error)
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

    fun severityName(severity: Int?): String? = when (severity) {
        FATAL -> "Fatal"
        SERIOUS -> "Serious"
        COMMON -> "Warning"
        INFO -> "Notice"
        else -> null
    }
}
