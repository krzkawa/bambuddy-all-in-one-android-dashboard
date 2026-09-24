package io.github.krzkawa.bambuddyaio.appliance

import io.github.krzkawa.bambuddyaio.ui.Hms
import io.github.krzkawa.bambuddyaio.util.bool
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/**
 * What changed on a printer between two polls that is worth making a sound for.
 *
 * Worked out from the status the dashboard already polls, so alerts cost no
 * extra requests and need nothing from the server. Android-free, so every
 * transition can be tested without a printer.
 */
object Events {

    /** How an alert sounds, from good news to something that needs him now. */
    enum class Tone { GOOD, ATTENTION, BAD }

    enum class Kind(val key: String, val label: String, val tone: Tone, val onByDefault: Boolean) {
        FINISHED("finished", "Finished", Tone.GOOD, true),
        FAILED("failed", "Failed", Tone.BAD, true),
        PAUSED("paused", "Paused", Tone.ATTENTION, true),
        FAULT("fault", "Faults", Tone.BAD, true),
        ALMOST_DONE("almost", "5 min left", Tone.GOOD, false),
        OFFLINE("offline", "Offline", Tone.ATTENTION, false),
        UNPLUGGED("unplugged", "Phone unplugged", Tone.ATTENTION, true)
    }

    data class Event(
        val kind: Kind,
        val printerId: Int,
        /** One line for the status strip. */
        val line: String,
        /** The same thing as a sentence, for the voice. */
        val speech: String
    )

    /** States in which a print is under way. A finish only counts coming out of one. */
    private val ACTIVE = setOf("RUNNING", "PAUSE", "PREPARE", "SLICING")

    /** The "about to finish" line fires once, as the countdown crosses this. */
    const val ALMOST_MINUTES = 5

    /**
     * Everything worth announcing in the step from [prev] to [next].
     *
     * Nothing is reported without a previous status, so opening the app on a
     * printer that finished an hour ago is silent: only changes seen happening
     * make a sound.
     *
     * [trayName] turns a global tray id into "AMS 1 slot 3", for the runout line.
     */
    fun between(
        printerId: Int,
        name: String,
        prev: JSONObject?,
        next: JSONObject?,
        trayName: (Int) -> String = { "slot ${it + 1}" }
    ): List<Event> {
        if (prev == null || next == null) return emptyList()
        val out = ArrayList<Event>()
        val was = prev.str("state")
        val now = next.str("state")
        val job = jobName(next) ?: jobName(prev)

        if (now != was && was in ACTIVE) {
            when (now) {
                "FINISH" -> out.add(
                    Event(
                        Kind.FINISHED, printerId,
                        if (job != null) "$name finished $job" else "$name finished",
                        if (job != null) "$name has finished $job." else "$name has finished printing."
                    )
                )
                // Stopping a print by hand lands here too; the firmware reports
                // both the same way, so the words cover both.
                "FAILED" -> out.add(
                    Event(
                        Kind.FAILED, printerId,
                        if (job != null) "$name stopped — $job did not finish" else "$name stopped before the end",
                        "$name has stopped. The print did not finish."
                    )
                )
            }
        }

        if (now == "PAUSE" && was != "PAUSE" && was in ACTIVE) {
            val ran = next.int("previous_tray")?.takeIf { it in 0..253 }
            val wants = next.int("expected_tray")?.takeIf { it in 0..253 }
            val why = when {
                ran != null -> "${trayName(ran)} ran out"
                wants != null -> "it wants ${trayName(wants)}"
                else -> null
            }
            out.add(
                Event(
                    Kind.PAUSED, printerId,
                    if (why != null) "$name paused — $why" else "$name paused",
                    if (why != null) "$name has paused. ${why.replaceFirstChar { it.uppercase() }}." else "$name has paused."
                )
            )
        }

        val before = faultKeys(prev)
        for (fault in Hms.faults(next)) {
            if (fault.severity == Hms.INFO) continue
            if (key(fault) in before) continue
            out.add(
                Event(
                    Kind.FAULT, printerId,
                    "$name: ${fault.description}",
                    "$name reports a problem. ${fault.description.trimEnd('.')}."
                )
            )
        }

        val left = next.int("remaining_time")
        val leftBefore = prev.int("remaining_time")
        if (now == "RUNNING" && was == "RUNNING" && left != null && leftBefore != null &&
            left in 1..ALMOST_MINUTES && leftBefore > ALMOST_MINUTES
        ) {
            out.add(
                Event(
                    Kind.ALMOST_DONE, printerId,
                    "$name: $left min left",
                    "$name will be done in $left ${if (left == 1) "minute" else "minutes"}."
                )
            )
        }

        if (prev.has("connected") && next.has("connected") &&
            prev.bool("connected") && !next.bool("connected")
        ) {
            out.add(Event(Kind.OFFLINE, printerId, "$name went offline", "$name has gone offline."))
        }
        return out
    }

    /** The tone for a batch: the most urgent one in it. */
    fun toneOf(events: List<Event>): Tone? = events.maxByOrNull { it.kind.tone.ordinal }?.kind?.tone

    private fun faultKeys(status: JSONObject): Set<String> =
        Hms.faults(status).map { key(it) }.toSet()

    private fun key(fault: Hms.Fault): String = fault.fullCode ?: fault.code ?: fault.description

    /** The job's name without the slicer's file extensions on it. */
    fun jobName(status: JSONObject): String? {
        val raw = status.str("subtask_name") ?: status.str("current_print") ?: status.str("gcode_file")
            ?: return null
        return raw.substringAfterLast('/')
            .removeSuffix(".3mf").removeSuffix(".gcode")
            .ifBlank { null }
    }
}
