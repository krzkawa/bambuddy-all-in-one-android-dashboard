package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/**
 * Turning what an AMS slot reports into an inventory spool.
 *
 * Two routes, because the server only offers one of them. A slot whose spool has an
 * RFID tag goes through `POST /inventory/spools/from-slot`, which builds the spool the
 * way Bambuddy's own auto-add does. A slot with no tag is refused there, so the app
 * builds the spool from the same tray fields itself; it is then found again by its
 * slot assignment, or by a sticker written for it.
 *
 * Kept clear of Android so it can be unit tested.
 */
object SlotSpool {

    private val RGBA = Regex("^[0-9A-Fa-f]{8}$")

    /** The tray JSON for a unit and slot, from a printer status. */
    fun tray(status: JSONObject?, amsId: Int, trayId: Int): JSONObject? {
        if (status == null) return null
        if (amsId == 255) {
            return status.objects("vt_tray").firstOrNull {
                (it.optInt("id", 254) - 254).coerceIn(0, 1) == trayId
            }
        }
        val unit = status.objects("ams").firstOrNull { it.optInt("id", -1) == amsId } ?: return null
        return unit.objects("tray").firstOrNull { it.optInt("id", -1) == trayId }
    }

    /**
     * True when the slot's spool has a real RFID identity, by the server's own rule
     * (`spool_tag_matcher.is_valid_tag`): a tag UID or a tray UUID that is not empty and
     * not all zeros.
     */
    fun hasTag(tray: JSONObject): Boolean = real(tray.str("tag_uid")) || real(tray.str("tray_uuid"))

    private fun real(id: String?): Boolean {
        val hex = id?.filter { it.isLetterOrDigit() } ?: return false
        return hex.isNotEmpty() && hex.any { it != '0' }
    }

    /** True when the AMS has named what is in the slot, so there is something to add. */
    fun hasFilament(tray: JSONObject): Boolean = tray.str("tray_type") != null

    /**
     * Material and variant from the tray, split the way Bambuddy splits them
     * (`create_spool_from_tray`): "PLA Basic" under type "PLA" is material PLA, subtype
     * Basic; a sub-brand that does not start with the type is the material itself.
     */
    fun materialAndSubtype(tray: JSONObject): Pair<String, String?> {
        val type = tray.str("tray_type") ?: "PLA"
        val sub = tray.str("tray_sub_brands") ?: return type to null
        if (sub.contains(' ')) {
            val (first, rest) = sub.split(' ', limit = 2)
            return if (first.equals(type, ignoreCase = true)) type to rest else sub to null
        }
        return if (sub.equals(type, ignoreCase = true)) type to null else sub to null
    }

    /** "PLA Basic · 64%" for the confirmation, or the bare type. */
    fun describe(tray: JSONObject): String {
        val (material, subtype) = materialAndSubtype(tray)
        val name = if (subtype != null) "$material $subtype" else material
        val remain = tray.optInt("remain", -1)
        return if (remain in 0..100) "$name · $remain%" else name
    }

    /**
     * The body of `POST /inventory/spools` for a slot with no tag.
     *
     * Grams used are set from the AMS's own remaining estimate when it reports one, so
     * a half-used spool does not arrive as full. `slicer_filament` is the tray's
     * `tray_info_idx`, which is what Bambuddy resolves to a slicer preset.
     */
    fun payload(tray: JSONObject, labelWeight: Int = 1000): JSONObject {
        val (material, subtype) = materialAndSubtype(tray)
        val payload = JSONObject()
            .put("material", material.take(50))
            .put("label_weight", labelWeight)
        subtype?.let { payload.put("subtype", it) }
        tray.str("tray_color")?.takeIf { RGBA.matches(it) }?.let { payload.put("rgba", it.uppercase()) }
        tray.str("tray_info_idx")?.let { payload.put("slicer_filament", it) }
        tray.int("nozzle_temp_min")?.takeIf { it > 0 }?.let { payload.put("nozzle_temp_min", it) }
        tray.int("nozzle_temp_max")?.takeIf { it > 0 }?.let { payload.put("nozzle_temp_max", it) }
        val remain = tray.optInt("remain", -1)
        // Zero is left out on purpose: an AMS reports 0 for a spool it cannot estimate,
        // and taking that at its word would add a full spool as an empty one.
        if (remain in 1..99) {
            payload.put("weight_used", Math.round(labelWeight * (100 - remain) / 100.0).toDouble())
        }
        return payload
    }

    /** The spool Bambuddy has assigned to this slot already, if any. */
    fun assignedSpool(assignments: List<JSONObject>, printerId: Int, amsId: Int, trayId: Int): JSONObject? =
        assignments.firstOrNull {
            it.optInt("printer_id") == printerId && it.optInt("ams_id") == amsId && it.optInt("tray_id") == trayId
        }?.optJSONObject("spool")
}
