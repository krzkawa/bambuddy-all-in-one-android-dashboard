package io.github.krzkawa.bambuddyaio.ui

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Why `MjpegView` holds on to its `Call`.
 *
 * The camera worker spends its life blocked in a socket read. When the screen
 * goes away the view sets its running flag to false and interrupts the worker,
 * and for a stream that is still delivering frames that is enough — the flag is
 * seen on the next time round the loop. For a stream that has gone silent it is
 * not: the read never returns, the flag is never looked at, and the thread and
 * its socket stay alive for the life of the process. Every visit to the Camera
 * tab left another pair behind.
 *
 * This pins the two halves of that down against a server that accepts a request
 * and then says nothing at all: `Thread.interrupt()` does not end the read, and
 * cancelling the call does.
 *
 * No Android here on purpose, so it runs as an ordinary unit test.
 */
class StreamCancelTest {

    @Test
    fun cancellingTheCallFreesAWorkerParkedOnASilentStream() {
        val server = ServerSocket(0)
        val serving = Thread { serveHeadersThenGoQuiet(server) }
        serving.isDaemon = true
        serving.start()

        // Deliberately no read timeout: this is about what ends a read that is
        // never going to return on its own. The finite timeout the app now sets
        // on its stream client is the backstop, not the mechanism.
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(
            Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
        )

        val reading = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread {
            try {
                call.execute().use { response ->
                    val stream = response.body!!.byteStream()
                    reading.countDown()
                    stream.read()
                }
            } catch (e: Exception) {
                // Both the cancel and the socket closing land here, as they do
                // in the view: the way out, not a failure.
            } finally {
                finished.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()

        try {
            assertTrue("the worker never got as far as reading", reading.await(10, TimeUnit.SECONDS))
            Thread.sleep(150) // let it settle inside read()

            worker.interrupt()
            assertFalse(
                "interrupting a blocking socket read should not end it — if this " +
                    "ever passes, the reason MjpegView keeps its Call is gone",
                finished.await(400, TimeUnit.MILLISECONDS)
            )

            call.cancel()
            assertTrue(
                "cancelling the call left the worker blocked; this is the thread " +
                    "and socket the Camera tab used to leak on every visit",
                finished.await(10, TimeUnit.SECONDS)
            )
        } finally {
            call.cancel()
            server.close()
            worker.join(TimeUnit.SECONDS.toMillis(5))
        }
    }

    /**
     * Answers one request with response headers and then sends nothing, which is
     * what a printer that drops off mid-stream looks like from the phone.
     */
    private fun serveHeadersThenGoQuiet(server: ServerSocket) {
        try {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                // No Content-Length and no chunking, so the client reads until the
                // connection closes — and it never will.
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray()
                    )
                    flush()
                }
                // Hold the connection open until the test tears the server down.
                Thread.sleep(TimeUnit.SECONDS.toMillis(30))
            }
        } catch (e: Exception) {
            // The test closing the server is the normal end of this thread.
        }
    }
}
