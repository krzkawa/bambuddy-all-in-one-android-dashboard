package io.github.krzkawa.bambuddyaio.nfc

/** Everything a scan managed to learn about the spool in front of the phone. */
data class SpoolTag(
    /** Hex, uppercase. The Mifare UID — what Bambuddy calls tag_uid. */
    val tagUid: String,
    /** 32 hex chars, matching what the AMS reports over MQTT as tray_uuid. */
    val trayUuid: String? = null,
    /** How it was read: shown to the user so a partial read is never mistaken for a full one. */
    val source: Source = Source.PLAIN,
    val material: String? = null,
    val detailedType: String? = null,
    val brand: String? = null,
    /** 8 hex chars, RRGGBBAA. */
    val rgba: String? = null,
    /** The second colour of a dual-colour filament, 8 hex chars RRGGBBAA, or null. */
    val secondRgba: String? = null,
    /** How many colours the tag declares. Null when the tag carries no colour count. */
    val colorCount: Int? = null,
    val filamentWeightG: Int? = null,
    val diameterMm: Double? = null,
    val nozzleTempMin: Int? = null,
    val nozzleTempMax: Int? = null,
    val bedTemp: Int? = null,
    val dryingTemp: Int? = null,
    val dryingHours: Int? = null,
    val materialId: String? = null,
    val variantId: String? = null,
    val producedAt: String? = null,
    val lengthM: Int? = null,
    val spoolWidthMm: Double? = null,
    /** Set when the tag was found but could not be decoded, e.g. an unreadable sector. */
    val warning: String? = null
) {
    enum class Source { BAMBU, OPENSPOOL, PLAIN }

    val label: String
        get() = when (source) {
            Source.BAMBU -> "Bambu Lab RFID"
            Source.OPENSPOOL -> "OpenSpool tag"
            Source.PLAIN -> "Unrecognised tag"
        }

    /** A human title for the scan card. */
    val title: String
        get() = detailedType?.takeIf { it.isNotBlank() }
            ?: material?.takeIf { it.isNotBlank() }
            ?: "Unknown filament"

    /** True when the tag declares a second colour, so the swatch should show both. */
    val isDualColor: Boolean get() = secondRgba != null
}
