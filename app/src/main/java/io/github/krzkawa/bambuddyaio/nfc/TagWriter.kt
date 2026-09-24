package io.github.krzkawa.bambuddyaio.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException

/**
 * Writes an OpenSpool record to a blank NFC sticker, so a spool that came without a
 * chip scans like one that has.
 *
 * Writing is the one thing in this app that cannot be taken back by pressing another
 * button, so it refuses far more than it has to:
 *
 *  * Anything presenting Mifare Classic is refused outright. That is what genuine Bambu
 *    spools carry, and nothing here should ever be pointed at one.
 *  * A tag that already holds something other than an OpenSpool record is left alone.
 *  * A record too big for the tag is refused before a byte is sent, rather than
 *    half-written.
 *  * Tags are never locked. A sticker written wrong can always be written again.
 *
 * Blocking radio I/O: call it off the main thread.
 */
object TagWriter {

    /** What happened, in words to show him, and whether the tag now holds [json]. */
    data class Outcome(
        val written: Boolean,
        val tagUid: String,
        val message: String,
        /** True when the record was read straight back off the tag and matched. */
        val verified: Boolean = false
    )

    fun write(tag: Tag, json: String): Outcome {
        val uid = BambuTag.uidHex(tag)

        if (BambuTag.supportsMifareClassic(tag)) {
            return Outcome(
                false, uid,
                "That is a Mifare Classic tag, the kind Bambu spools carry. Nothing was written. " +
                    "Use a blank NTAG215 or NTAG216 sticker."
            )
        }

        val message = NdefMessage(
            NdefRecord.createMime(OpenSpool.MIME, json.toByteArray(Charsets.UTF_8))
        )

        Ndef.get(tag)?.let { return writeNdef(it, uid, message, json) }
        NdefFormatable.get(tag)?.let { return format(it, uid, message) }

        return Outcome(false, uid, "This tag cannot hold an NDEF record. Use an NTAG215 or NTAG216 sticker.")
    }

    private fun writeNdef(ndef: Ndef, uid: String, message: NdefMessage, json: String): Outcome =
        try {
            ndef.connect()
            when {
                !ndef.isWritable -> Outcome(false, uid, "This sticker is locked and cannot be written.")

                message.byteArrayLength > ndef.maxSize -> Outcome(
                    false, uid,
                    "This sticker holds ${ndef.maxSize} bytes and the record needs " +
                        "${message.byteArrayLength}. Use an NTAG215 or NTAG216."
                )

                !OpenSpool.safeToOverwrite(payloads(ndef.ndefMessage)) -> Outcome(
                    false, uid,
                    "This tag already holds something that is not a spool record, so it was left alone."
                )

                else -> {
                    ndef.writeNdefMessage(message)
                    val back = try {
                        payloads(ndef.ndefMessage)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val verified = OpenSpool.parse(uid, back) == OpenSpool.parse(
                        uid, listOf(json.toByteArray(Charsets.UTF_8))
                    )
                    Outcome(
                        true, uid,
                        if (verified) "Written and checked." else "Written, but it did not read back the same. Scan it to check.",
                        verified
                    )
                }
            }
        } catch (e: IOException) {
            Outcome(false, uid, "Lost the sticker part-way through. Hold it still and try again.")
        } catch (e: Exception) {
            Outcome(false, uid, "The sticker refused the write: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            close(ndef)
        }

    /**
     * A factory-blank tag that has never held NDEF. Formatting writes the record in the
     * same step; the tag cannot be read back as NDEF through the same discovery, so the
     * result is not verified and says so.
     */
    private fun format(formatable: NdefFormatable, uid: String, message: NdefMessage): Outcome =
        try {
            formatable.connect()
            formatable.format(message)
            Outcome(true, uid, "Written. Scan it once to check.")
        } catch (e: IOException) {
            Outcome(false, uid, "Lost the sticker part-way through. Hold it still and try again.")
        } catch (e: Exception) {
            Outcome(false, uid, "The sticker refused the write: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            close(formatable)
        }

    /** A blank but formatted NTAG reports no message at all, which is the same as empty. */
    private fun payloads(message: NdefMessage?): List<ByteArray> =
        message?.records?.map { if (it.tnf == NdefRecord.TNF_EMPTY) ByteArray(0) else it.payload }
            ?: emptyList()

    private fun close(technology: android.nfc.tech.TagTechnology) {
        try {
            technology.close()
        } catch (e: IOException) {
            // Tag already gone; nothing to release.
        }
    }
}
