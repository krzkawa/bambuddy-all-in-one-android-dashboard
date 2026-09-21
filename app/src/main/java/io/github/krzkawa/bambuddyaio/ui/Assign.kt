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

    /** One AMS slot, in the form the assignment endpoint wants it. */
    data class Slot(val amsId: Int, val trayId: Int, val label: String, val occupant: String?) {
        /** Global tray id, which is what the load/unload commands use instead. */
        val globalTrayId: Int get() = if (amsId == 255) 254 + trayId else amsId * 4 + trayId
    }

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
                        label = "AMS ${amsId + 1} · slot ${trayId + 1}",
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
