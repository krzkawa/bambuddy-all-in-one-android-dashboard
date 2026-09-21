package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/** Shared pieces of "put this spool in that slot". */
object Assign {

    /** Unit ids an AMS-HT reports; the range comes straight from the firmware. */
    val HT_UNITS = 128..135

    /** One AMS slot, in the form the assignment endpoint wants it. */
    data class Slot(val amsId: Int, val trayId: Int, val label: String, val occupant: String?) {
        /**
         * Global tray id, which is what load, unload and the runout fields use
         * instead of the unit-and-slot pair.
         *
         * An AMS-HT's unit id arrives from MQTT in the range 128-135 and *is*
         * already the global tray id, so the usual four-per-unit arithmetic
         * would send a command to tray 512 and quietly do nothing.
         */
        val globalTrayId: Int
            get() = when {
                amsId == 255 -> 254 + trayId
                amsId in Assign.HT_UNITS -> amsId
                else -> amsId * 4 + trayId
            }
    }

    /** What to call a unit on screen. An HT is not "AMS 129". */
    fun unitName(amsId: Int): String = when {
        amsId == 255 -> "External spool"
        amsId in HT_UNITS -> "AMS HT ${amsId - HT_UNITS.first + 1}"
        else -> "AMS ${amsId + 1}"
    }

    /** What to call one slot. An HT holds a single spool, so it has no slot number. */
    fun slotName(amsId: Int, trayId: Int): String = when {
        amsId == 255 || amsId in HT_UNITS -> unitName(amsId)
        else -> "${unitName(amsId)} · slot ${trayId + 1}"
    }

    /** Turns a globalised tray id back into words, without consulting a status. */
    fun trayWords(globalTrayId: Int): String = when {
        globalTrayId == 254 || globalTrayId == 255 -> "External spool"
        globalTrayId in HT_UNITS -> unitName(globalTrayId)
        else -> slotName(globalTrayId / 4, globalTrayId % 4)
    }

    /**
     * Names a globalised tray id, preferring what this printer actually reports
     * so an HT or an odd unit id is named the way the AMS screen names it.
     */
    fun nameGlobalTray(printerId: Int, globalTrayId: Int): String =
        findSlot(printerId, globalTrayId)?.label ?: trayWords(globalTrayId)

    fun findSlot(printerId: Int, globalTrayId: Int): Slot? =
        slotsFor(printerId).firstOrNull { it.globalTrayId == globalTrayId }

    fun slotsFor(printerId: Int): List<Slot> {
        val status = Repo.statuses.value[printerId] ?: return emptyList()
        val slots = ArrayList<Slot>()
        for (unit in status.objects("ams")) {
            val amsId = unit.optInt("id")
            for (tray in unit.objects("tray")) {
                val trayId = tray.optInt("id")
                slots.add(
                    Slot(
                        amsId = amsId,
                        trayId = trayId,
                        label = slotName(amsId, trayId),
                        occupant = describe(tray)
                    )
                )
            }
        }
        for (vt in status.objects("vt_tray")) {
            // The external holder is reported with a global id of 254 or 255.
            val trayId = (vt.optInt("id", 254) - 254).coerceIn(0, 1)
            slots.add(Slot(255, trayId, "External spool", describe(vt)))
        }
        return slots
    }

    private fun describe(tray: JSONObject): String? {
        val type = tray.str("tray_sub_brands") ?: tray.str("tray_type")
        val remain = tray.optInt("remain", -1)
        return when {
            type == null -> null
            remain in 0..100 -> "$type · $remain%"
            else -> type
        }
    }

    /** Name a spool the way a person would say it out loud. */
    fun spoolName(spool: JSONObject): String {
        val material = spool.str("subtype") ?: spool.str("material") ?: "Filament"
        val colour = spool.str("color_name")
        val brand = spool.str("brand")
        return listOfNotNull(brand, material, colour).joinToString(" · ")
    }

    fun spoolRemaining(spool: JSONObject): String {
        val label = spool.dbl("label_weight") ?: return "—"
        val used = spool.dbl("weight_used") ?: 0.0
        val left = (label - used).coerceAtLeast(0.0)
        return "${left.toInt()} g left"
    }

    fun pickSlot(ctx: Context, printerId: Int, onPick: (Slot) -> Unit) {
        val slots = slotsFor(printerId)
        if (slots.isEmpty()) {
            Ui.toast(ctx, "This printer is not reporting any AMS slots")
            return
        }
        val labels = slots.map { slot ->
            if (slot.occupant == null) slot.label else "${slot.label}  —  ${slot.occupant}"
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Which slot?")
            .setItems(labels) { _, which -> onPick(slots[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Sends the assignment. Bambuddy keeps it locally and configures the tray. */
    fun send(spoolId: Int, printerId: Int, slot: Slot, done: (String) -> Unit) {
        Repo.action("Assign", {
            Repo.api.assign(spoolId, printerId, slot.amsId, slot.trayId)
        }, done)
    }
}
