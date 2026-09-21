package io.github.krzkawa.bambuddyaio.ui

/**
 * Splits an MJPEG byte stream into whole JPEG frames.
 *
 * An MJPEG stream is JPEG images back to back, so it is tempting to cut at every
 * `FF D8` … `FF D9` pair. That is wrong on real camera output: a JPEG carrying an EXIF or
 * JFIF thumbnail holds a complete, nested `FF D8` … `FF D9` inside its APP segment, so a
 * naive scan cuts the frame short at the thumbnail's end-of-image and the decoder is
 * handed half a picture. Restart markers inside the compressed data (`FF D0` to `FF D7`)
 * are a second way to trip up.
 *
 * So this walks the JPEG's own structure instead: past the start-of-image, each segment
 * declares its length, which steps over a nested thumbnail in one move; once the
 * start-of-scan is reached the compressed data is scanned for the real end-of-image,
 * respecting the `FF 00` stuffing that lets a literal `FF` appear in it.
 *
 * Android-free on purpose, so the awkward cases can be tested without a camera.
 *
 * Not thread-safe; a stream is pumped by one thread.
 */
class MjpegFrames(private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES) {

    private var buffer = ByteArray(INITIAL_CAPACITY)

    /** Bytes of [buffer] that hold stream data; everything past this is spare capacity. */
    private var length = 0

    /** How many bytes are held, either part of a frame or not yet recognised as junk. */
    val buffered: Int get() = length

    /**
     * True when [buffered] is the beginning of a real frame and the splitter is simply
     * waiting for the rest of it; false when the held bytes are not a frame yet.
     *
     * This is what tells "this frame is big or the network is slow" apart from "this
     * stream is not MJPEG and the buffer is filling with nothing".
     */
    val awaitingFrame: Boolean
        get() = length >= 2 && buffer.at(0) == 0xFF && buffer.at(1) == SOI

    /**
     * Bytes thrown away since this splitter was made: multipart boundaries, HTTP headers,
     * anything before the first frame, and whatever a resync stepped over. A number that
     * climbs while no frames arrive means the stream is not what we think it is.
     */
    var discardedBytes: Long = 0
        private set

    /**
     * Adds the first [count] bytes of [data] to the stream and returns every frame that
     * is now complete, in order. Usually empty or a single frame.
     *
     * A frame split across several reads is held until its last byte arrives. Bytes that
     * are not part of any JPEG are dropped. If the buffer grows past [maxBufferedBytes]
     * without yielding a frame — a stream that is not MJPEG at all, or one that lost its
     * framing — it is discarded rather than allowed to exhaust the heap.
     */
    fun append(data: ByteArray, count: Int = data.size): List<ByteArray> {
        require(count >= 0 && count <= data.size) { "count $count is not within ${data.size} bytes" }
        ensureCapacity(length + count)
        System.arraycopy(data, 0, buffer, length, count)
        length += count

        var frames: MutableList<ByteArray>? = null
        var consumed = 0
        var framedBytes = 0

        while (true) {
            val start = indexOfStartOfImage(buffer, consumed, length)
            if (start < 0) {
                // Keep a trailing FF, which may be the first half of a start-of-image
                // whose second byte is in the next read.
                consumed = if (length > 0 && buffer[length - 1].toInt() and 0xFF == 0xFF) length - 1 else length
                break
            }

            when (val end = endOfFrame(buffer, start, length)) {
                NEED_MORE_DATA -> {
                    consumed = start
                    break
                }
                MALFORMED -> {
                    // Resync: this was not a frame after all, so look for the next
                    // start-of-image beyond it rather than giving up on the stream.
                    consumed = start + 2
                }
                else -> {
                    val frame = buffer.copyOfRange(start, end)
                    (frames ?: mutableListOf<ByteArray>().also { frames = it }).add(frame)
                    framedBytes += frame.size
                    consumed = end
                }
            }
        }

        discardedBytes += (consumed - framedBytes).toLong()
        discard(consumed)

        // A buffer this large without a frame in it means the framing is lost, and holding
        // on to it only risks the heap on a phone that has little of it.
        if (length > maxBufferedBytes) {
            discardedBytes += length.toLong()
            reset()
        }

        return frames ?: emptyList()
    }

    /** Forgets any partial frame, for a stream that is being restarted. */
    fun reset() {
        length = 0
        if (buffer.size > INITIAL_CAPACITY) buffer = ByteArray(INITIAL_CAPACITY)
    }

    private fun ByteArray.at(index: Int): Int = this[index].toInt() and 0xFF

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var capacity = buffer.size
        while (capacity < needed) capacity = capacity shl 1
        buffer = buffer.copyOf(capacity)
    }

    /** Drops the first [count] bytes, sliding the rest down rather than reallocating. */
    private fun discard(count: Int) {
        if (count <= 0) return
        if (count >= length) {
            length = 0
            return
        }
        System.arraycopy(buffer, count, buffer, 0, length - count)
        length -= count
    }

    companion object {
        /** Returned by [endOfFrame] when the frame is not all here yet. */
        const val NEED_MORE_DATA = -1

        /** Returned by [endOfFrame] when the bytes cannot be a JPEG and the caller should resync. */
        const val MALFORMED = -2

        private const val INITIAL_CAPACITY = 64 * 1024

        /** Four megabytes of unframed data means the stream is not what we think it is. */
        const val DEFAULT_MAX_BUFFERED_BYTES = 4 * 1024 * 1024

        private const val SOI = 0xD8
        private const val EOI = 0xD9
        private const val SOS = 0xDA
        private const val TEM = 0x01
        private const val STUFFING = 0x00
        private const val FIRST_RESTART = 0xD0
        private const val LAST_RESTART = 0xD7

        /** Index of the next `FF D8` in `[from, limit)`, or -1. */
        fun indexOfStartOfImage(data: ByteArray, from: Int, limit: Int): Int {
            var i = from.coerceAtLeast(0)
            while (i < limit - 1) {
                if (data.at(i) == 0xFF && data.at(i + 1) == SOI) return i
                i++
            }
            return -1
        }

        /**
         * Given a start-of-image at [start], returns the index one past the frame's
         * end-of-image, or [NEED_MORE_DATA] / [MALFORMED].
         *
         * Walks the JPEG's segments rather than hunting for byte pairs, which is what
         * makes a nested thumbnail a single step over rather than a false frame boundary.
         */
        fun endOfFrame(data: ByteArray, start: Int, limit: Int): Int {
            if (start + 2 > limit) return NEED_MORE_DATA
            if (data.at(start) != 0xFF || data.at(start + 1) != SOI) return MALFORMED

            var i = start + 2
            while (true) {
                if (i + 1 >= limit) return NEED_MORE_DATA

                if (data.at(i) != 0xFF) return MALFORMED
                // A run of FF bytes is legal padding before a marker.
                while (i + 1 < limit && data.at(i + 1) == 0xFF) i++
                if (i + 1 >= limit) return NEED_MORE_DATA

                when (val marker = data.at(i + 1)) {
                    EOI -> return i + 2
                    SOI, TEM, in FIRST_RESTART..LAST_RESTART -> i += 2
                    else -> {
                        // Every other marker carries a two-byte length that covers itself.
                        if (i + 4 > limit) return NEED_MORE_DATA
                        val segment = (data.at(i + 2) shl 8) or data.at(i + 3)
                        if (segment < 2) return MALFORMED
                        val next = i + 2 + segment
                        if (next > limit) return NEED_MORE_DATA
                        if (marker != SOS) {
                            i = next
                        } else {
                            // Compressed data follows the scan header and holds no lengths,
                            // so it has to be scanned — but only FF bytes matter.
                            val resumed = endOfScanData(data, next, limit)
                            if (resumed == NEED_MORE_DATA) return NEED_MORE_DATA
                            i = resumed
                        }
                    }
                }
            }
        }

        /**
         * Scans entropy-coded data from [from], returning the index of the `FF` that ends
         * it, or [NEED_MORE_DATA].
         *
         * A literal `FF` in compressed data is written `FF 00`, and restart markers are
         * sprinkled through it by some encoders; neither ends the scan.
         */
        private fun endOfScanData(data: ByteArray, from: Int, limit: Int): Int {
            var i = from
            while (i < limit) {
                if (data.at(i) != 0xFF) {
                    i++
                    continue
                }
                if (i + 1 >= limit) return NEED_MORE_DATA
                when (val next = data.at(i + 1)) {
                    STUFFING -> i += 2
                    0xFF -> i++
                    in FIRST_RESTART..LAST_RESTART -> i += 2
                    else -> return i
                }
            }
            return NEED_MORE_DATA
        }

        private fun ByteArray.at(index: Int): Int = this[index].toInt() and 0xFF
    }
}
