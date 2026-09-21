package io.github.krzkawa.bambuddyaio.ui

/**
 * Builds JPEG byte sequences for the frame-splitting tests.
 *
 * Only the structure matters here — segment markers, length fields, the scan header and
 * the compressed data's byte stuffing — since nothing in these tests decodes an image.
 * The point is to reproduce the shapes a real camera emits that a naive `FF D8` … `FF D9`
 * scan gets wrong.
 */
object Jpeg {

    val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** A marker segment: `FF <marker>`, then a length covering itself, then the payload. */
    fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(),
            marker.toByte(),
            (length ushr 8).toByte(),
            (length and 0xFF).toByte()
        ) + payload
    }

    /** A plain JFIF frame: APP0, a quantisation table, a scan header, compressed data, EOI. */
    fun frame(
        scanData: ByteArray = byteArrayOf(0x11, 0x22, 0x33, 0x44),
        extraSegments: ByteArray = ByteArray(0)
    ): ByteArray =
        SOI +
            segment(0xE0, "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)) +
            extraSegments +
            segment(0xDB, ByteArray(65) { (it and 0x3F).toByte() }) +
            segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) +
            scanData +
            EOI

    /**
     * A frame whose EXIF segment carries a complete nested JPEG thumbnail.
     *
     * This is the shape that breaks a naive scan: the thumbnail contributes its own
     * `FF D8` and `FF D9` inside the APP1 segment.
     */
    fun frameWithThumbnail(): ByteArray {
        val thumbnail = frame(scanData = byteArrayOf(0x7A, 0x7B))
        return frame(extraSegments = segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + thumbnail))
    }

    /** Compressed data containing a literal FF, which JPEG writes as the pair `FF 00`. */
    fun stuffedScanData(): ByteArray =
        byteArrayOf(0x01, 0xFF.toByte(), 0x00, 0x02, 0xFF.toByte(), 0x00, 0x03)

    /** Compressed data broken up by restart markers, as some encoders emit. */
    fun scanDataWithRestarts(): ByteArray =
        byteArrayOf(0x01, 0x02) +
            byteArrayOf(0xFF.toByte(), 0xD0.toByte()) +
            byteArrayOf(0x03, 0x04) +
            byteArrayOf(0xFF.toByte(), 0xD1.toByte()) +
            byteArrayOf(0x05, 0x06)

    /** Where [needle] first appears in [haystack], or -1. */
    fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
