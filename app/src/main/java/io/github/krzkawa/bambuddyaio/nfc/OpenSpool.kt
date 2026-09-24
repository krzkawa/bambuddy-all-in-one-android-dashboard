package io.github.krzkawa.bambuddyaio.nfc

import org.json.JSONObject

/**
 * Reads and writes OpenSpool tags — the open NDEF format third-party spools and re-usable
 * tags use, which is plain JSON rather than Bambu's locked sectors.
 *
 * Kept free of Android APIs (the NDEF records arrive as raw payloads) so the format
 * handling is unit tested alongside the Bambu parsing. [TagWriter] does the radio part.
 */
object OpenSpool {

    private const val PROTOCOL = "openspool"

    /** The record type the OpenSpool spec writes its JSON under. */
    const val MIME = "application/json"

    /**
     * What goes on a sticker.
     *
     * Exactly the fields of OpenSpool 1.0 and nothing more: readers on the other end —
     * AMS add-ons, other apps — only know these, and every extra byte is a byte an
     * NTAG213 does not have.
     */
    data class Record(
        val type: String,
        /** Six hex characters, RRGGBB, no '#'. */
        val colorHex: String,
        val brand: String,
        val minTemp: Int?,
        val maxTemp: Int?
    )

    /**
     * The JSON as it will sit on the tag.
     *
     * Temperatures are written as strings because that is how the spec's own example
     * writes them, and readers built from that example expect it. [parseOne] accepts
     * either.
     */
    fun encode(record: Record): String {
        val obj = JSONObject()
            .put("protocol", PROTOCOL)
            .put("version", "1.0")
            .put("type", record.type)
            .put("color_hex", record.colorHex)
            .put("brand", record.brand)
        record.minTemp?.let { obj.put("min_temp", it.toString()) }
        record.maxTemp?.let { obj.put("max_temp", it.toString()) }
        return obj.toString()
    }

    /**
     * Bytes the whole NDEF message takes for [json], as Android's `Ndef.getMaxSize`
     * counts them: one MIME record with the header, the type and the payload.
     *
     * The payload length field is one byte up to 255 and four after that, which is
     * the short-record flag Android sets by itself.
     */
    fun messageSize(json: String): Int {
        val payload = json.toByteArray(Charsets.UTF_8).size
        val lengthField = if (payload < 256) 1 else 4
        return 1 + 1 + lengthField + MIME.length + payload
    }

    /**
     * What Android reports as the NDEF capacity of an NTAG213, the smallest common
     * sticker. A record over this needs an NTAG215 (496) or NTAG216 (868).
     */
    const val NTAG213_BYTES = 137

    /**
     * A sensible nozzle range when the spool itself has none, so a sticker never goes
     * out without temperatures an AMS reader would then have to guess. Taken from the
     * ranges Bambu prints on its own spools for each family.
     */
    fun defaultTemps(material: String?): Pair<Int, Int>? {
        val m = material?.uppercase()?.trim() ?: return null
        return when {
            m.startsWith("PLA") -> 190 to 230
            m.startsWith("PETG") || m.startsWith("PET") -> 230 to 260
            m.startsWith("ABS") -> 240 to 270
            m.startsWith("ASA") -> 240 to 270
            m.startsWith("TPU") -> 200 to 240
            m.startsWith("PC") -> 260 to 290
            m.startsWith("PA") || m.startsWith("NYLON") -> 260 to 290
            m.startsWith("PVA") -> 190 to 230
            m.startsWith("HIPS") -> 220 to 250
            else -> null
        }
    }

    /**
     * True when [payloads] are something a sticker can safely be written over: nothing
     * at all, or an OpenSpool record (rewriting a sticker is the point of the feature).
     * Anything else belongs to something else, and is left alone.
     */
    fun safeToOverwrite(payloads: List<ByteArray>): Boolean =
        payloads.all { it.isEmpty() } || parse("", payloads) != null

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
