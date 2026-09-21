package io.github.krzkawa.bambuddyaio.nfc

/**
 * The little bit of Mifare Classic a Bambu tag read needs, behind an interface so the
 * read can be driven either by a real tag on the phone or by a captured dump in a test.
 *
 * Implementations are one-shot and not thread-safe: connect, read, close.
 */
interface MifareSectorSource {

    /** The tag's UID, which the sector keys are derived from. */
    val uid: ByteArray

    /** How many sectors this tag has. A 1K tag has 16. */
    val sectorCount: Int

    /**
     * Authenticates one sector with Key A.
     *
     * @return true when the key was accepted.
     * @throws TagLostException if the tag left the field.
     */
    fun authenticateSectorWithKeyA(sector: Int, key: ByteArray): Boolean

    /**
     * Reads one 16-byte block. Only called for a sector that just authenticated.
     *
     * @throws TagLostException if the tag left the field.
     */
    fun readBlock(block: Int): ByteArray
}

/** The tag moved out of range part-way through a read. */
class TagLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A sector that would not authenticate with the derived key. */
class SectorLockedException(val sector: Int) :
    Exception("sector $sector rejected the key derived from the tag UID")

/**
 * Reads the sectors of a Bambu tag that hold filament data.
 *
 * Platform-free, so the authentication path is covered by the same unit tests as the
 * parsing. Sectors 0 to 4 hold every field the app shows; sectors 10 to 15 hold an RSA
 * signature that is of no use here and would only cost radio time with the spool held
 * against the phone.
 */
object BambuSectors {

    /** Mifare Classic 1K: four blocks per sector, the fourth being the key trailer. */
    const val BLOCKS_PER_SECTOR = 4

    /** Sectors 0 to 4 cover every documented field. */
    const val SECTORS_WITH_DATA = 5

    /**
     * The blocks [BambuBlocks] actually reads a field out of.
     *
     * Every block skipped here is one less radio round-trip with the spool held against
     * the phone, and the rest of a Bambu tag is either a key trailer, an RSA signature or
     * empty.
     */
    val DATA_BLOCKS = listOf(1, 2, 4, 5, 6, 8, 9, 10, 12, 13, 14, 16)

    /**
     * Authenticates and reads the data sectors of [source].
     *
     * A sector past the first that will not authenticate is skipped, so an older tag that
     * carries fewer fields still reads; the block map simply has fewer entries. A single
     * unreadable block likewise costs one field rather than the scan.
     *
     * @throws SectorLockedException if sector 0 rejects the derived key, which means this
     *   is not a genuine Bambu tag.
     * @throws TagLostException if the tag leaves the field.
     */
    fun read(source: MifareSectorSource): Map<Int, ByteArray> {
        val keys = BambuKeys.deriveKeys(source.uid)
        val blocks = LinkedHashMap<Int, ByteArray>()

        for (sector in 0 until minOf(SECTORS_WITH_DATA, source.sectorCount)) {
            if (!source.authenticateSectorWithKeyA(sector, keys[sector])) {
                if (sector == 0) throw SectorLockedException(0)
                continue
            }
            val first = sector * BLOCKS_PER_SECTOR
            // The fourth block of every sector is the Mifare key trailer, never tag data.
            for (index in first until first + BLOCKS_PER_SECTOR - 1) {
                if (index in DATA_BLOCKS) blocks[index] = source.readBlock(index)
            }
        }
        return blocks
    }
}
