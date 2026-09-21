package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.nfc.SpoolTag
import org.json.JSONObject
import java.util.Locale

/**
 * Turns a scanned tag into the body of `POST /inventory/spools`.
 *
 * Bambuddy's `SpoolCreate` is a pydantic model, so a key it does not know is dropped
 * without a word — a wrong field name fails silently and the spool simply arrives
 * missing that value. Every key here was read off `backend/app/schemas/spool.py`, and
 * what the schema has no home for goes into `note` rather than being thrown away.
 *
 * Kept out of [ScanFragment] so it can be tested without a phone.
 */
object SpoolPayload {

    /** What the server's `rgba` column accepts: eight hex characters, RRGGBBAA. */
    private val RGBA = Regex("^[0-9A-Fa-f]{8}$")

    fun from(tag: SpoolTag): JSONObject {
        val payload = JSONObject()
            .put("material", (tag.material ?: "Unknown").take(50))
            .put("label_weight", tag.filamentWeightG ?: 1000)
            .put("data_origin", "nfc")
            // The same vocabulary Bambuddy writes when its own AMS creates a spool
            // from a tag, so a roll added from the phone and one added by the
            // printer read the same afterwards.
            .put("tag_type", if (tag.source == SpoolTag.Source.BAMBU) "bambulab" else "openspool")
        tag.detailedType?.let { payload.put("subtype", it) }
        tag.brand?.let { payload.put("brand", it) }
        tag.rgba?.takeIf { RGBA.matches(it) }?.let { payload.put("rgba", it) }
        tag.nozzleTempMin?.let { payload.put("nozzle_temp_min", it) }
        tag.nozzleTempMax?.let { payload.put("nozzle_temp_max", it) }
        payload.put("tag_uid", tag.tagUid)
        tag.trayUuid?.let { payload.put("tray_uuid", it) }

        // A dual-colour spool. `extra_colors` is comma-separated hex tokens rather
        // than a list, lowercase and without a leading '#'; the gradient is only
        // drawn when `effect_type` names the structure as well, so the two go
        // together or neither shows. Until now the scan card showed both swatches
        // and the inventory showed one.
        tag.secondRgba?.takeIf { RGBA.matches(it) }?.let {
            payload.put("extra_colors", it.lowercase())
            payload.put("effect_type", "dual-color")
        }

        // Bambu's own filament id, e.g. GFA00. This is what the AMS reports as
        // tray_info_idx, and what Bambuddy resolves into a slicer preset and a
        // K-profile — so setting it is what makes a scanned spool behave like one
        // the printer identified itself.
        tag.materialId?.let { payload.put("slicer_filament", it) }

        note(tag)?.let { payload.put("note", it) }
        return payload
    }

    /**
     * Everything the tag gave up that the spool schema has no column for.
     *
     * Diameter, bed and drying temperatures, length, spool width, the variant id and
     * the production date are all decoded and would otherwise be dropped on the floor.
     * A note keeps them where he can read them next to the spool.
     */
    internal fun note(tag: SpoolTag): String? {
        val lines = ArrayList<String>()

        val physical = ArrayList<String>()
        tag.diameterMm?.let { physical.add(String.format(Locale.US, "%.2f mm filament", it)) }
        tag.lengthM?.let { physical.add("$it m") }
        tag.spoolWidthMm?.let { physical.add(String.format(Locale.US, "%.1f mm spool", it)) }
        tag.nozzleDiameterMm?.let { physical.add(String.format(Locale.US, "%.1f mm nozzle", it)) }
        if (physical.isNotEmpty()) lines.add(physical.joinToString(" · "))

        val heat = ArrayList<String>()
        tag.bedTemp?.let { heat.add("bed $it°") }
        when {
            tag.dryingTemp != null && tag.dryingHours != null ->
                heat.add("dry ${tag.dryingTemp}° for ${tag.dryingHours} h")
            tag.dryingTemp != null -> heat.add("dry ${tag.dryingTemp}°")
        }
        if (heat.isNotEmpty()) lines.add(heat.joinToString(" · "))

        val ids = ArrayList<String>()
        tag.materialId?.let { ids.add("material $it") }
        tag.variantId?.let { ids.add("variant $it") }
        tag.producedAt?.let { ids.add("made $it") }
        if (ids.isNotEmpty()) lines.add(ids.joinToString(" · "))

        if (lines.isEmpty()) return null
        lines.add(0, "Read from the spool's tag.")
        return lines.joinToString("\n")
    }
}
