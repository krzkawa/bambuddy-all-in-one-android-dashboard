package io.github.krzkawa.bambuddyaio.nfc

/**
 * Turns the raw Mifare blocks of a Bambu Lab spool tag into a [SpoolTag].
 *
 * Layout follows the Bambu Research Group's published documentation
 * (github.com/Bambu-Research-Group/RFID-Tag-Guide/blob/main/BambuLabRfid.md). All numbers
 * are little-endian.
 *
 * | block | holds |
 * | --- | --- |
 * | 1 | material variant ID (0..7), material ID (8..15), both ASCII |
 * | 2 | filament type, ASCII |
 * | 4 | detailed filament type, ASCII |
 * | 5 | colour RGBA (0..3), spool weight in g (4..5), diameter in mm as a float (8..11) |
 * | 6 | drying °C, drying hours, bed temp type, bed °C, hotend max °C, hotend min °C |
 * | 8 | X Cam info (0..11), nozzle diameter in mm as a float (12..15) |
 * | 9 | tray UID, 16 bytes |
 * | 10 | spool width in mm×100 (4..5) |
 * | 12 | production date, ASCII `<year>_<month>_<day>_<hour>_<minute>` |
 * | 13 | a shorter production date, format undocumented, kept as stored |
 * | 14 | filament length in metres (4..5) |
 * | 16 | colour format id, colour count, second colour as ABGR (4..7) |
 *
 * Android-free, so every field here is covered by unit tests over captured dumps rather
 * than needing a phone and a spool.
 */
object BambuBlocks {

    // A spool of 1.75 mm filament is the norm and 2.85 mm exists; outside this band we are
    // not looking at a diameter, and passing a wrong one on would mislead the slicer.
    private const val MIN_DIAMETER_MM = 0.5
    private const val MAX_DIAMETER_MM = 5.0

    /**
     * Parses [blocks] into a [SpoolTag] with [SpoolTag.Source.BAMBU].
     *
     * A field the tag leaves zeroed, or holds something unreadable in, comes back null
     * rather than as a plausible-looking zero. When nothing identifying could be read at
     * all the result carries a [SpoolTag.warning], so the scan card says the tag is
     * damaged instead of showing a blank spool as if it were fine.
     */
    fun parse(tagUid: String, blocks: Map<Int, ByteArray>): SpoolTag {
        val b1 = blocks.sized(1)
        val b2 = blocks.sized(2)
        val b4 = blocks.sized(4)
        val b5 = blocks.sized(5)
        val b6 = blocks.sized(6)
        val b8 = blocks.sized(8)
        val b9 = blocks.sized(9)
        val b10 = blocks.sized(10)
        val b12 = blocks.sized(12)
        val b13 = blocks.sized(13)
        val b14 = blocks.sized(14)
        val b16 = blocks.sized(16)

        val material = b2?.ascii(0, 16)
        val detailedType = b4?.ascii(0, 16)
        // Nothing names the filament, so the tag is damaged rather than merely sparse.
        val unreadable = material == null && detailedType == null

        // Block 16 is on newer tags only, and its format id says whether a second colour
        // is really there rather than leftover bytes.
        val colorFormat = b16?.u16(0) ?: 0
        val colorCount = if (colorFormat != 0) b16?.u16(2) else null
        val secondRgba = if (colorFormat != 0 && (colorCount ?: 0) > 1) b16?.abgrAsRgbaHex(4) else null

        return SpoolTag(
            tagUid = tagUid,
            trayUuid = b9?.let { hex(it) },
            source = SpoolTag.Source.BAMBU,
            brand = "Bambu Lab",
            material = material,
            detailedType = detailedType,
            rgba = b5?.let { hex(it.copyOfRange(0, 4)) },
            secondRgba = secondRgba,
            colorCount = colorCount,
            filamentWeightG = b5?.u16(4)?.takeIf { it in 1..10_000 },
            diameterMm = b5?.f32(8)?.toDouble()?.takeIf { it > MIN_DIAMETER_MM && it < MAX_DIAMETER_MM },
            dryingTemp = b6?.u16(0)?.takeIf { it in 1..200 },
            dryingHours = b6?.u16(2)?.takeIf { it in 1..100 },
            bedTemp = b6?.u16(6)?.takeIf { it in 1..200 },
            nozzleTempMax = b6?.u16(8)?.takeIf { it in 1..500 },
            nozzleTempMin = b6?.u16(10)?.takeIf { it in 1..500 },
            variantId = b1?.ascii(0, 8),
            materialId = b1?.ascii(8, 8),
            spoolWidthMm = b10?.u16(4)?.let { it / 100.0 }?.takeIf { it > 1.0 },
            producedAt = b12?.ascii(0, 16),
            lengthM = b14?.u16(4)?.takeIf { it in 1..100_000 },
            // Tags commonly leave this zeroed, in which case it is absent rather than 0 mm.
            nozzleDiameterMm = b8?.f32(12)?.toDouble()
                ?.takeIf { it > 0.0 && it < MAX_DIAMETER_MM },
            producedAtShort = b13?.ascii(0, 16),
            warning = if (unreadable) {
                "This tag unlocked as a Bambu spool, but its filament data is unreadable. " +
                    "You can still link it to a spool by hand."
            } else {
                null
            },
            failure = if (unreadable) ScanFailure.MALFORMED else null
        )
    }

    private fun Map<Int, ByteArray>.sized(block: Int): ByteArray? = this[block]?.takeIf { it.size == 16 }

    /**
     * Reads a fixed-width text field: stops at the first NUL, then trims trailing spaces.
     *
     * Returns null when the bytes are not printable ASCII, rather than stripping the
     * offending bytes — a damaged block should read as "unknown", not as a shorter word
     * that looks like real data.
     */
    internal fun ByteArray.ascii(offset: Int, length: Int): String? {
        if (offset + length > size) return null
        val out = StringBuilder(length)
        for (i in offset until offset + length) {
            val c = this[i].toInt() and 0xFF
            if (c == 0) break
            if (c < 0x20 || c > 0x7E) return null
            out.append(c.toChar())
        }
        return out.toString().trimEnd().ifBlank { null }
    }

    /** Little-endian unsigned 16-bit read. */
    internal fun ByteArray.u16(offset: Int): Int {
        if (offset + 2 > size) return 0
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** Little-endian 32-bit float read. */
    internal fun ByteArray.f32(offset: Int): Float {
        if (offset + 4 > size) return 0f
        val bits = (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    /** Four bytes stored ABGR, returned as the RRGGBBAA hex the rest of the app uses. */
    internal fun ByteArray.abgrAsRgbaHex(offset: Int): String? {
        if (offset + 4 > size) return null
        return hex(byteArrayOf(this[offset + 3], this[offset + 2], this[offset + 1], this[offset]))
    }

    /** Uppercase hex, no separators. */
    fun hex(bytes: ByteArray): String {
        val digits = "0123456789ABCDEF"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }
}
