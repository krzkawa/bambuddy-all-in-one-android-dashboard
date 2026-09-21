package io.github.krzkawa.bambuddyaio.nfc

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import java.io.IOException

/**
 * Reads a filament spool tag.
 *
 * Genuine Bambu Lab spools carry a Mifare Classic 1K tag whose sectors are locked with
 * keys derived from the tag's own UID — see [BambuKeys]. OpenSpool tags are plain NDEF and
 * are read as a fallback, as is a bare tag with nothing on it, so an unknown spool can
 * still be linked by its UID. Nothing here writes to a tag.
 *
 * This is the only file in the package that touches Android; the derivation, the sector
 * walk and the block parsing all live in [BambuKeys], [BambuSectors], [BambuBlocks] and
 * [OpenSpool], which are unit tested against captured dumps.
 */
object BambuTag {

    /** True when *this tag* presents the Mifare Classic technology. */
    fun supportsMifareClassic(tag: Tag): Boolean =
        tag.techList.contains(MifareClassic::class.java.name)

    /**
     * True when *this phone's* NFC controller can do Mifare Classic at all.
     *
     * Worth checking once at startup: phones built around a Qualcomm NFC controller
     * cannot read genuine Bambu tags, and no amount of retrying will change that. Such a
     * phone never sees the Mifare Classic technology on any tag, so without this check a
     * hardware limitation looks exactly like an unrecognised spool.
     */
    fun phoneSupportsMifareClassic(context: Context): Boolean =
        context.packageManager.hasSystemFeature("com.nxp.mifare")

    fun uidHex(tag: Tag): String = hex(tag.id)

    fun hex(bytes: ByteArray): String = BambuBlocks.hex(bytes)

    /** Sixteen 6-byte Key A values, one per sector, derived from [uid]. */
    fun deriveKeys(uid: ByteArray): Array<ByteArray> = BambuKeys.deriveKeys(uid)

    /**
     * Reads whatever the tag will give up. Never throws for an unreadable tag — it
     * returns a [SpoolTag] carrying a [SpoolTag.warning] and a [SpoolTag.failure] so the
     * UI can still offer to link the UID to a spool by hand.
     *
     * Pass [context] when you have one. Without it, a tag that arrives without the Mifare
     * Classic technology is ambiguous: the phone's NFC controller may not support Mifare
     * Classic at all, or this may simply be some other kind of tag. With it, the two are
     * told apart properly and the user gets the right advice.
     *
     * Blocking radio I/O; call it off the main thread, and expect the spool to stay
     * against the phone for the few hundred milliseconds it takes.
     */
    @JvmOverloads
    fun read(tag: Tag, context: Context? = null): SpoolTag {
        val uid = uidHex(tag)

        if (supportsMifareClassic(tag)) {
            readBambu(tag, uid)?.let { return it }
            // A Mifare Classic tag that is not Bambu may still carry an OpenSpool record.
            tryOpenSpool(tag, uid)?.let { return it }
            return SpoolTag(
                tagUid = uid,
                source = SpoolTag.Source.PLAIN,
                warning = "This tag is not a Bambu Lab spool tag, or its keys do not match. " +
                    "You can still link it to a spool by hand.",
                failure = ScanFailure.AUTH_FAILED
            )
        }

        tryOpenSpool(tag, uid)?.let { return it }

        val unsupportedPhone = when (context) {
            null -> tag.techList.contains(NfcA::class.java.name)
            else -> !phoneSupportsMifareClassic(context)
        }

        return SpoolTag(
            tagUid = uid,
            source = SpoolTag.Source.PLAIN,
            warning = if (unsupportedPhone) {
                "This phone's NFC chip cannot read Mifare Classic, which is what Bambu spools use. " +
                    "You can still link this tag to a spool by hand."
            } else {
                "Tag read, but it holds no filament data."
            },
            failure = if (unsupportedPhone) ScanFailure.UNSUPPORTED_DEVICE else ScanFailure.NOT_MIFARE_CLASSIC
        )
    }

    // ------------------------------------------------------------ Bambu tags

    /**
     * Returns null when this is not a Bambu tag, so the caller can try OpenSpool next.
     * A tag that was lost rather than refused comes back as a [ScanFailure.TAG_LOST]
     * result, which is a different thing and says so.
     *
     * Twelve blocks across five sectors is a long time to hold a spool steady against an
     * old phone's antenna, so a lost tag is read again in place before giving up. The
     * user has not moved yet at that point, which is the moment a retry is most likely
     * to work — and much better than asking him to start over.
     */
    private fun readBambu(tag: Tag, uid: String): SpoolTag? {
        var attempts = 0
        while (true) {
            val pass = attemptBambu(tag, uid)
            when (pass.outcome) {
                ReadOutcome.DECODED -> return pass.tag
                ReadOutcome.NOT_BAMBU -> return null
                ReadOutcome.STALE -> return SpoolTag(
                    tagUid = uid,
                    source = SpoolTag.Source.PLAIN,
                    warning = "Another tag arrived before this one had finished reading. Hold one " +
                        "spool at a time against the phone and try again.",
                    failure = ScanFailure.TAG_LOST
                )
                ReadOutcome.LOST -> {
                    attempts++
                    if (attempts >= READ_ATTEMPTS) {
                        return SpoolTag(
                            tagUid = uid,
                            source = SpoolTag.Source.PLAIN,
                            warning = "Lost contact with the tag part-way through, twice. Lay the " +
                                "spool flat against the back of the phone and hold it there.",
                            failure = ScanFailure.TAG_LOST
                        )
                    }
                    settle()
                }
            }
        }
    }

    /** One connect-read-close pass at the tag. */
    private class Pass(val outcome: ReadOutcome, val tag: SpoolTag? = null)

    private fun attemptBambu(tag: Tag, uid: String): Pass {
        val mfc = MifareClassic.get(tag) ?: return Pass(ReadOutcome.NOT_BAMBU)

        return try {
            mfc.connect()
            mfc.timeout = TAG_TIMEOUT_MS
            val blocks = BambuSectors.read(MifareClassicSource(tag.id, mfc))
            if (blocks.isEmpty()) Pass(ReadOutcome.NOT_BAMBU)
            else Pass(ReadOutcome.DECODED, BambuBlocks.parse(uid, blocks))
        } catch (e: Exception) {
            // Anything the reader has no answer for is not a spool problem; let it out.
            Pass(outcomeOf(e) ?: throw e)
        } finally {
            closeQuietly(mfc)
        }
    }

    /** A moment for the field to settle before reading the tag again. */
    private fun settle() {
        try {
            Thread.sleep(RETRY_PAUSE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Milliseconds per Mifare transceive. The default is short for a fifteen-block read. */
    private const val TAG_TIMEOUT_MS = 2000

    /** One retry. A second failure means the spool really has moved. */
    private const val READ_ATTEMPTS = 2

    private const val RETRY_PAUSE_MS = 40L

    /** Adapts [MifareClassic] to the platform-free [MifareSectorSource] the reader works against. */
    private class MifareClassicSource(
        override val uid: ByteArray,
        private val mfc: MifareClassic
    ) : MifareSectorSource {

        override val sectorCount: Int get() = mfc.sectorCount

        override fun authenticateSectorWithKeyA(sector: Int, key: ByteArray): Boolean =
            try {
                mfc.authenticateSectorWithKeyA(sector, key)
            } catch (e: IOException) {
                throw TagLostException("lost the tag while authenticating sector $sector", e)
            }

        override fun readBlock(block: Int): ByteArray =
            try {
                mfc.readBlock(block)
            } catch (e: IOException) {
                throw TagLostException("lost the tag while reading block $block", e)
            }
    }

    // -------------------------------------------------------- OpenSpool tags

    private fun tryOpenSpool(tag: Tag, uid: String): SpoolTag? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage ?: return null
            OpenSpool.parse(uid, message.records.map { it.payload })
        } catch (e: Exception) {
            null
        } finally {
            closeQuietly(ndef)
        }
    }

    private fun closeQuietly(technology: android.nfc.tech.TagTechnology) {
        try {
            technology.close()
        } catch (e: IOException) {
            // Tag already gone; nothing to release.
        }
    }
}
