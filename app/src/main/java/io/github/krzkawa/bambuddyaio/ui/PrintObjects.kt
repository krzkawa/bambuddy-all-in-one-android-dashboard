package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/**
 * The objects on the plate the printer is running.
 *
 * `print/objects` answers with every object the slicer put on the current
 * plate, each with whether it has already been abandoned, and says outright
 * whether the printer is in a state where skipping means anything. The
 * firmware cannot un-skip one, so the screen has to be exact about which is
 * which; this keeps that reading testable without a printer.
 */
object PrintObjects {

    data class Obj(
        /** The `identify_id` the skip route takes, not a position in a list. */
        val id: Int,
        val name: String,
        val skipped: Boolean,
        val x: Double?,
        val y: Double?
    ) {
        /** Where it sits on the plate, for telling two identical parts apart. */
        val position: String?
            get() = if (x == null || y == null) null else "${Math.round(x)}, ${Math.round(y)} mm"

        val label: String
            get() = listOfNotNull(name, position?.let { "($it)" }).joinToString(" ")
    }

    data class Plate(
        val objects: List<Obj>,
        /** The server's own word for whether a skip would reach the printer. */
        val printing: Boolean
    ) {
        val remaining: List<Obj> get() = objects.filter { !it.skipped }
        val skipped: List<Obj> get() = objects.filter { it.skipped }

        /**
         * True when there is something to skip *and* something left over.
         *
         * Skipping the last object is not a way to stop a print — the printer
         * keeps running the plate either way — so the screen never offers it.
         */
        val canSkip: Boolean get() = printing && remaining.size > 1
    }

    fun read(payload: JSONObject): Plate = Plate(
        objects = payload.objects("objects").mapNotNull { row ->
            val id = row.int("id") ?: return@mapNotNull null
            Obj(
                id = id,
                name = row.str("name") ?: "Object $id",
                skipped = row.optBoolean("skipped"),
                x = row.dbl("x"),
                y = row.dbl("y")
            )
        },
        printing = payload.optBoolean("is_printing")
    )

    /** Why there is nothing to offer, in words rather than an empty dialog. */
    fun nothingToSkip(plate: Plate): String = when {
        plate.objects.isEmpty() ->
            "The printer is not reporting the objects on this plate."
        !plate.printing ->
            "Objects can only be skipped while the plate is running."
        plate.remaining.size <= 1 ->
            "Only one object is still printing. Skipping it would not end the print — stop it instead."
        else -> "Nothing to skip."
    }
}
