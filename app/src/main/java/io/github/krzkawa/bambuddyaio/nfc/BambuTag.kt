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
     * returns a [SpoolTag] with a warning so the UI can still offer to link the UID to a
     * spool by hand.
     *
     * Blocking radio I/O; call it off the main thread, and expect the spool to stay
     * against the phone for the few hundred milliseconds it takes.
     */
    fun read(tag: Tag): SpoolTag {
        val uid = uidHex(tag)

        if (supportsMifareClassic(tag)) {
            readBambu(tag, uid)?.let { return it }
            // A Mifare Classic tag that is not Bambu may still carry an OpenSpool record.
            tryOpenSpool(tag, uid)?.let { return it }
            return SpoolTag(
                tagUid = uid,
                source = SpoolTag.Source.PLAIN,
                warning = "This tag is not a Bambu Lab spool tag, or its keys do not match. " +
                    "You can still link it to a spool by hand."
            )
        }

        tryOpenSpool(tag, uid)?.let { return it }

        return SpoolTag(tagUid = uid, source = SpoolTag.Source.PLAIN, warning = plainWarning(tag))
    }

    /** What to tell the user about a tag that held nothing we could use. */
    private fun plainWarning(tag: Tag): String = when {
        tag.techList.contains(NfcA::class.java.name) ->
            "This phone's NFC chip cannot read Mifare Classic, which is what Bambu spools use. " +
                "You can still link this tag to a spool by hand."
        else -> "Tag read, but it holds no filament data."
    }

    // ------------------------------------------------------------ Bambu tags

    /** Returns null when this is not a Bambu tag or the read did not survive. */
    private fun readBambu(tag: Tag, uid: String): SpoolTag? {
        val mfc = MifareClassic.get(tag) ?: return null

        return try {
            mfc.connect()
            mfc.timeout = TAG_TIMEOUT_MS
            val blocks = BambuSectors.read(MifareClassicSource(tag.id, mfc))
            if (blocks.isEmpty()) null else BambuBlocks.parse(uid, blocks)
        } catch (e: SectorLockedException) {
            // Sector 0 refused the derived key, so this is not a genuine Bambu tag.
            null
        } catch (e: TagLostException) {
            SpoolTag(
                tagUid = uid,
                source = SpoolTag.Source.PLAIN,
                warning = "Lost contact with the tag part-way through. Hold the spool still against " +
                    "the phone and try again."
            )
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            // An empty or nonsensical UID; nothing to derive keys from.
            null
        } catch (e: SecurityException) {
            // The tag handle went stale, usually because another tag arrived first.
            null
        } finally {
            closeQuietly(mfc)
        }
    }

    /** Milliseconds per Mifare transceive. The default is short for a fifteen-block read. */
    private const val TAG_TIMEOUT_MS = 2000

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
