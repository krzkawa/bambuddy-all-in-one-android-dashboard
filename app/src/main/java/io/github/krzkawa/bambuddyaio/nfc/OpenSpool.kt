package io.github.krzkawa.bambuddyaio.nfc

import org.json.JSONObject

/**
 * Reads an OpenSpool tag — the open NDEF format third-party spools and re-usable tags
 * use, which is plain JSON rather than Bambu's locked sectors.
 *
 * Kept free of Android APIs (the NDEF records arrive as raw payloads) so the format
 * handling is unit tested alongside the Bambu parsing.
 */
object OpenSpool {

    private const val PROTOCOL = "openspool"

    /**
     * Returns a [SpoolTag] for the first payload that holds an OpenSpool record, or null
     * when none of them do.
     */
    fun parse(tagUid: String, payloads: List<ByteArray>): SpoolTag? {
        for (payload in payloads) {
            parseOne(tagUid, String(payload, Charsets.UTF_8))?.let { return it }
        }
        return null
    }

    fun parseOne(tagUid: String, text: String): SpoolTag? {
        // The payload usually carries an NDEF header and a language code before the JSON,
        // so find where the object actually starts rather than insisting it is the whole
        // payload.
        val start = text.indexOf('{')
        if (start < 0) return null

        val obj = try {
            JSONObject(text.substring(start))
        } catch (e: Exception) {
            return null
        }
        if (!obj.optString("protocol").equals(PROTOCOL, ignoreCase = true)) return null

        val type = obj.optString("type").ifBlank { null }
        return SpoolTag(
            tagUid = tagUid,
            source = SpoolTag.Source.OPENSPOOL,
            material = type,
            detailedType = type,
            brand = obj.optString("brand").ifBlank { null },
            rgba = normaliseColor(obj.optString("color_hex")),
            nozzleTempMin = obj.optInt("min_temp").takeIf { it > 0 },
            nozzleTempMax = obj.optInt("max_temp").takeIf { it > 0 }
        )
    }

    /** OpenSpool writes `#RRGGBB` or `RRGGBBAA`; the app works in 8-character RRGGBBAA. */
    fun normaliseColor(raw: String?): String? {
        val color = raw?.trim()?.removePrefix("#")?.uppercase() ?: return null
        if (!color.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return when (color.length) {
            6 -> color + "FF"
            8 -> color
            else -> null
        }
    }
}
