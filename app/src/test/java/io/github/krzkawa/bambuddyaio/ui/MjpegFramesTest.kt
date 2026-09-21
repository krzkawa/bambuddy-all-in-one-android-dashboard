package io.github.krzkawa.bambuddyaio.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MjpegFramesTest {

    private val frames = MjpegFrames()

    @Test
    fun `a whole frame in one read comes back byte for byte`() {
        val frame = Jpeg.frame()

        val out = frames.append(frame)

        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
        assertEquals(0, frames.buffered)
    }

    @Test
    fun `two frames in one read come back in order`() {
        val first = Jpeg.frame(scanData = byteArrayOf(1, 1, 1))
        val second = Jpeg.frame(scanData = byteArrayOf(2, 2, 2))

        val out = frames.append(first + second)

        assertEquals(2, out.size)
        assertArrayEquals(first, out[0])
        assertArrayEquals(second, out[1])
    }

    @Test
    fun `a frame split across three reads is held until its last byte`() {
        val frame = Jpeg.frame(scanData = ByteArray(300) { it.toByte() })
        val a = frame.copyOfRange(0, 40)
        val b = frame.copyOfRange(40, 200)
        val c = frame.copyOfRange(200, frame.size)

        assertEquals(0, frames.append(a).size)
        assertEquals(0, frames.append(b).size)
        val out = frames.append(c)

        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun `a frame arriving one byte at a time is reassembled`() {
        val frame = Jpeg.frame()
        var delivered: ByteArray? = null

        for (b in frame) {
            frames.append(byteArrayOf(b)).firstOrNull()?.let { delivered = it }
        }

        assertArrayEquals(frame, delivered)
    }

    /**
     * The case a naive `FF D8` … `FF D9` scan gets wrong: the thumbnail inside the EXIF
     * segment has its own end-of-image, and cutting there hands the decoder half a frame.
     */
    @Test
    fun `an embedded thumbnail does not cut the frame short`() {
        val frame = Jpeg.frameWithThumbnail()
        val nestedEoi = Jpeg.indexOf(frame, Jpeg.EOI)

        // Guard the fixture: there really is an end-of-image before the frame's own.
        assertTrue(nestedEoi > 0)
        assertTrue(nestedEoi + 2 < frame.size)

        val out = frames.append(frame)

        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun `a literal FF in the compressed data is not read as a marker`() {
        val frame = Jpeg.frame(scanData = Jpeg.stuffedScanData())

        val out = frames.append(frame)

        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun `restart markers in the compressed data do not end the frame`() {
        val frame = Jpeg.frame(scanData = Jpeg.scanDataWithRestarts())

        val out = frames.append(frame)

        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun `padding FF bytes before a marker are tolerated`() {
        val frame = Jpeg.frame()
        val padded = frame.copyOfRange(0, frame.size - 2) +
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + Jpeg.EOI

        val out = frames.append(padded)

        assertEquals(1, out.size)
        assertArrayEquals(padded, out[0])
    }

    @Test
    fun `a truncated frame at the end of the stream yields nothing and is not invented`() {
        val frame = Jpeg.frame()
        val truncated = frame.copyOfRange(0, frame.size - 5)

        val out = frames.append(truncated)

        assertEquals(0, out.size)
        assertEquals(truncated.size, frames.buffered)
    }

    @Test
    fun `junk before a frame is dropped`() {
        val frame = Jpeg.frame()

        val out = frames.append("HTTP/1.1 200 OK\r\n\r\n".toByteArray() + frame)

        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun `multipart boundaries between frames are dropped`() {
        val first = Jpeg.frame(scanData = byteArrayOf(1))
        val second = Jpeg.frame(scanData = byteArrayOf(2))
        val boundary = "\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()

        val out = frames.append(boundary + first + boundary + second)

        assertEquals(2, out.size)
        assertArrayEquals(first, out[0])
        assertArrayEquals(second, out[1])
    }

    @Test
    fun `a start-of-image split across two reads is still found`() {
        val frame = Jpeg.frame()

        assertEquals(0, frames.append(byteArrayOf(0x00, 0xFF.toByte())).size)
        val out = frames.append(byteArrayOf(0xD8.toByte()) + frame.copyOfRange(2, frame.size))

        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun `a bogus start-of-image resyncs to the next real frame`() {
        val frame = Jpeg.frame()
        // A start-of-image followed by a segment claiming an impossible length.
        val bogus = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x01)

        val out = frames.append(bogus + frame)

        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun `only part of a read is taken when a count is given`() {
        val frame = Jpeg.frame()
        val chunk = frame + ByteArray(500) { 0x7F }

        val out = frames.append(chunk, frame.size)

        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
        assertEquals(0, frames.buffered)
    }

    @Test
    fun `a stream that never frames is discarded rather than filling the heap`() {
        val small = MjpegFrames(maxBufferedBytes = 4096)

        // Junk with no start-of-image in it at all is dropped as it arrives.
        repeat(8) { assertEquals(0, small.append(ByteArray(1024) { 0x41 }).size) }
        assertEquals(0, small.buffered)

        // Junk that opens with a start-of-image cannot be dropped until the cap is hit.
        small.append(Jpeg.SOI)
        repeat(8) { small.append(ByteArray(1024) { 0x41 }) }
        assertTrue(small.buffered <= 4096)
    }

    @Test
    fun `reset forgets a partial frame`() {
        val frame = Jpeg.frame()
        frames.append(frame.copyOfRange(0, 20))
        assertEquals(20, frames.buffered)

        frames.reset()

        assertEquals(0, frames.buffered)
        // The rest of the old frame is junk now, and the next whole frame still arrives.
        val out = frames.append(frame.copyOfRange(20, frame.size) + frame)
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun `a slow frame is distinguishable from a stream that is not MJPEG`() {
        val frame = Jpeg.frame(scanData = ByteArray(400) { it.toByte() })

        // Half a real frame: bytes are held, but they are going somewhere.
        frames.append(frame.copyOfRange(0, 100))
        assertTrue(frames.awaitingFrame)
        assertEquals(100, frames.buffered)

        frames.reset()

        // Bytes that are not a frame at all are not held, and are counted as thrown away.
        frames.append(ByteArray(100) { 0x41 })
        assertEquals(false, frames.awaitingFrame)
        assertEquals(0, frames.buffered)
        assertTrue(frames.discardedBytes >= 100)
    }

    @Test
    fun `bytes that are part of a frame are not counted as discarded`() {
        val frame = Jpeg.frame()
        val boundary = "\r\n--frame\r\n".toByteArray()

        frames.append(boundary + frame)

        assertEquals(boundary.size.toLong(), frames.discardedBytes)
    }

    @Test
    fun `the splitter keeps working across many frames`() {
        val sent = (1..50).map { Jpeg.frame(scanData = ByteArray(it * 7) { i -> i.toByte() }) }
        val stream = sent.reduce { a, b -> a + b }
        val received = mutableListOf<ByteArray>()

        var offset = 0
        while (offset < stream.size) {
            val take = minOf(97, stream.size - offset)
            received += frames.append(stream.copyOfRange(offset, offset + take))
            offset += take
        }

        assertEquals(sent.size, received.size)
        for (i in sent.indices) assertArrayEquals(sent[i], received[i])
        assertEquals(0, frames.buffered)
        // Back-to-back frames means nothing between them to throw away.
        assertEquals(0L, frames.discardedBytes)
    }
}
