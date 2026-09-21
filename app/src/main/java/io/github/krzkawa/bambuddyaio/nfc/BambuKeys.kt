package io.github.krzkawa.bambuddyaio.nfc

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives the Mifare Classic sector keys of a genuine Bambu Lab spool tag from its UID.
 *
 * Bambu does not store the keys on the tag: every tag is locked with sixteen different
 * 6-byte Key A values that are a pure function of the tag UID. The derivation is
 * HKDF-SHA256 as published by the Bambu Research Group — the UID is the input keying
 * material, a fixed 16-byte constant is the salt, the info string is `RFID-A\u0000`, and
 * 96 bytes of output are cut into sixteen keys, one per sector.
 *
 * Kept free of Android APIs so it can be unit tested on a plain JVM; see BambuKeysTest,
 * which pins it against vectors from an independent implementation of the same algorithm.
 */
object BambuKeys {

    /** Salt from the published derivation, the same on every Bambu tag. */
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

    /** A Mifare Classic 1K has 16 sectors, and Bambu locks each with its own key. */
    const val SECTORS = 16

    /** Every Mifare Classic key is 6 bytes wide. */
    const val KEY_LENGTH = 6

    private const val HMAC = "HmacSHA256"

    /**
     * Returns the sixteen Key A values for [uid], index 0 being the key for sector 0.
     *
     * @throws IllegalArgumentException if [uid] is empty.
     */
    fun deriveKeys(uid: ByteArray): Array<ByteArray> {
        require(uid.isNotEmpty()) { "tag UID must not be empty" }
        val okm = hkdf(uid, MASTER_SALT, CONTEXT, KEY_LENGTH * SECTORS)
        return Array(SECTORS) { i -> okm.copyOfRange(i * KEY_LENGTH, (i + 1) * KEY_LENGTH) }
    }

    /** HKDF (RFC 5869) over HMAC-SHA256, so the app needs no crypto dependency. */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(salt, HMAC))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, HMAC))
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
}
