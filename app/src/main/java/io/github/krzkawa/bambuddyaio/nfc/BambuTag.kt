package io.github.krzkawa.bambuddyaio.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Reads a filament spool tag.
 *
 * Genuine Bambu Lab spools carry a Mifare Classic 1K tag whose sectors are
 * locked with keys derived from the tag's own UID. The derivation is public
 * research (github.com/Bambu-Research-Group/RFID-Tag-Guide): HKDF-SHA256 over
 * the 4-byte UID with a fixed salt, giving one 6-byte key A per sector. Nothing
 * here writes to a tag.
 *
 * OpenSpool tags are plain NDEF and are read as a fallback, as is a bare tag
 * with nothing on it, so an unknown spool can still be linked by its UID.
 */
object BambuTag {

    private val MASTER_SALT = byteArrayOf(
        0x9a.toByte(), 0x75, 0x9c.toByte(), 0xf2.toByte(),
        0xc4.toByte(), 0xf7.toByte(), 0xca.toByte(), 0xff.toByte(),
        0x22, 0x2c, 0xb9.toByte(), 0x76,
        0x9b.toByte(), 0x41, 0xbc.toByte(), 0x96.toByte()
    )

    private val CONTEXT = byteArrayOf(
        'R'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(),
        'D'.code.toByte(), '-'.code.toByte(), 'A'.code.toByte(), 0
    )

    private const val KEY_LENGTH = 6
    private const val SECTORS = 16

    /** True when this phone's NFC chip can talk to Mifare Classic at all. */
    fun supportsMifareClassic(tag: Tag): Boolean =
        tag.techList.contains(MifareClassic::class.java.name)

    fun uidHex(tag: Tag): String = hex(tag.id)

    /**
     * Reads whatever the tag will give up. Never throws for an unreadable tag —
     * it returns a [SpoolTag] with a warning so the UI can still offer to link
     * the UID to a spool by hand.
     */
    fun read(tag: Tag): SpoolTag {
        val uid = uidHex(tag)

        if (supportsMifareClassic(tag)) {
            try {
                return readBambu(tag, uid)
            } catch (e: Exception) {
                val openspool = tryOpenSpool(tag, uid)
                if (openspool != null) return openspool
                return SpoolTag(
                    tagUid = uid,
                    source = SpoolTag.Source.PLAIN,
                    warning = e.message ?: "Could not read the tag — hold it still and try again"
                )
            }
        }

        tryOpenSpool(tag, uid)?.let { return it }

        val why = if (tag.techList.contains(NfcA::class.java.name))
            "This phone's NFC chip cannot read Mifare Classic, which is what Bambu spools use. " +
                "You can still link this tag to a spool by hand."
        else
            "Tag read, but it holds no filament data."

        return SpoolTag(tagUid = uid, source = SpoolTag.Source.PLAIN, warning = why)
    }

    // ------------------------------------------------------------ Bambu tags

    private fun readBambu(tag: Tag, uid: String): SpoolTag {
        val mfc = MifareClassic.get(tag) ?: throw IOException("Not a Mifare Classic tag")
        val keys = deriveKeys(tag.id)

        mfc.connect()
        try {
            mfc.timeout = 2000
            val blocks = HashMap<Int, ByteArray>()
            // Sectors 0-4 hold every field this app shows; the rest is a
            // signature and padding, so there is no reason to spend the extra
            // seconds of radio time on them.
            for (sector in 0 until minOf(5, mfc.sectorCount)) {
                if (!mfc.authenticateSectorWithKeyA(sector, keys[sector])) {
                    if (sector == 0) throw IOException("Not a Bambu Lab spool tag, or the keys did not match")
                    continue
                }
                val first = mfc.sectorToBlock(sector)
                for (i in 0 until 3) {
                    val index = first + i
                    try {
                        blocks[index] = mfc.readBlock(index)
                    } catch (e: IOException) {
                        // A single unreadable block just leaves that field blank.
                    }
                }
            }

            if (blocks.isEmpty()) throw IOException("Tag read returned nothing")

            val b1 = blocks[1]
            val b2 = blocks[2]
            val b4 = blocks[4]
            val b5 = blocks[5]
            val b6 = blocks[6]
            val b9 = blocks[9]
            val b10 = blocks[10]
            val b12 = blocks[12]
            val b14 = blocks[14]

            val diameter = b5?.let { f32(it, 8).toDouble() }?.takeIf { it > 0.5 && it < 5.0 }

            return SpoolTag(
                tagUid = uid,
                trayUuid = b9?.let { hex(it) },
                source = SpoolTag.Source.BAMBU,
                brand = "Bambu Lab",
                material = b2?.let { ascii(it, 0, 16) },
                detailedType = b4?.let { ascii(it, 0, 16) },
                rgba = b5?.let { hex(it.copyOfRange(0, 4)) },
                filamentWeightG = b5?.let { u16(it, 4) }?.takeIf { it in 1..10000 },
                diameterMm = diameter,
                dryingTemp = b6?.let { u16(it, 0) }?.takeIf { it in 1..200 },
                dryingHours = b6?.let { u16(it, 2) }?.takeIf { it in 1..100 },
                bedTemp = b6?.let { u16(it, 6) }?.takeIf { it in 1..200 },
                nozzleTempMax = b6?.let { u16(it, 8) }?.takeIf { it in 1..500 },
                nozzleTempMin = b6?.let { u16(it, 10) }?.takeIf { it in 1..500 },
                variantId = b1?.let { ascii(it, 0, 8) },
                materialId = b1?.let { ascii(it, 8, 8) },
                spoolWidthMm = b10?.let { u16(it, 4) / 100.0 }?.takeIf { it > 1.0 },
                producedAt = b12?.let { ascii(it, 0, 16) },
                lengthM = b14?.let { u16(it, 4) }?.takeIf { it in 1..100000 }
            )
        } finally {
            try {
                mfc.close()
            } catch (e: IOException) {
                // Nothing useful to do if the tag already left the field.
            }
        }
    }

    /**
     * HKDF-SHA256 as the published research describes it: the tag UID is the
     * key material, the fixed master value is the salt, and 96 bytes of output
     * split into one 6-byte key A per sector.
     */
    fun deriveKeys(uid: ByteArray): Array<ByteArray> {
        val okm = hkdf(uid, MASTER_SALT, CONTEXT, KEY_LENGTH * SECTORS)
        return Array(SECTORS) { i -> okm.copyOfRange(i * KEY_LENGTH, (i + 1) * KEY_LENGTH) }
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    // -------------------------------------------------------- OpenSpool tags

    private fun tryOpenSpool(tag: Tag, uid: String): SpoolTag? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage ?: return null
            for (record in message.records) {
                val text = String(record.payload, Charsets.UTF_8)
                val start = text.indexOf('{')
                if (start < 0) continue
                val obj = try {
                    JSONObject(text.substring(start))
                } catch (e: Exception) {
                    continue
                }
                if (!obj.optString("protocol").equals("openspool", ignoreCase = true)) continue
                val color = obj.optString("color_hex").trim().removePrefix("#").uppercase()
                return SpoolTag(
                    tagUid = uid,
                    source = SpoolTag.Source.OPENSPOOL,
                    material = obj.optString("type").ifBlank { null },
                    detailedType = obj.optString("type").ifBlank { null },
                    brand = obj.optString("brand").ifBlank { null },
                    rgba = when (color.length) {
                        6 -> color + "FF"
                        8 -> color
                        else -> null
                    },
                    nozzleTempMin = obj.optInt("min_temp").takeIf { it > 0 },
                    nozzleTempMax = obj.optInt("max_temp").takeIf { it > 0 }
                )
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            try {
                ndef.close()
            } catch (e: IOException) {
                // Tag already gone; nothing to release.
            }
        }
    }

    // ------------------------------------------------------------- utilities

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    private fun ascii(block: ByteArray, offset: Int, length: Int): String? {
        if (offset + length > block.size) return null
        val slice = block.copyOfRange(offset, offset + length)
        val text = String(slice, Charsets.US_ASCII)
            .trimEnd('\u0000', ' ')
            .filter { it.code in 32..126 }
        return text.ifBlank { null }
    }

    private fun u16(block: ByteArray, offset: Int): Int {
        if (offset + 2 > block.size) return 0
        return (block[offset].toInt() and 0xFF) or ((block[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun f32(block: ByteArray, offset: Int): Float {
        if (offset + 4 > block.size) return 0f
        return ByteBuffer.wrap(block, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
    }
}
