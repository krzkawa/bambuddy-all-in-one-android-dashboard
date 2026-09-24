package io.github.krzkawa.bambuddyaio.shots

import org.robolectric.RuntimeEnvironment
import io.github.krzkawa.bambuddyaio.net.Cache
import io.github.krzkawa.bambuddyaio.net.Live
import io.github.krzkawa.bambuddyaio.net.Repo
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the real [Repo] and [Live] against a stand-in Bambuddy: token, socket,
 * a ring turning into one printer's fetch, a burst of rings coalescing, the
 * socket dropping back to polling, and the last status landing on disk.
 *
 * Runs only with -Pshots, like the screenshots, since it needs Robolectric:
 * `./gradlew :app:testDebugUnitTest -Pshots --tests "*LiveCheck*" --rerun-tasks`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveCheck {

    private val server = MockWebServer()
    private val statusCalls = AtomicInteger()
    private val tokenCalls = AtomicInteger()
    @Volatile private var serverSide: WebSocket? = null
    @Volatile private var wsPath: String? = null

    private fun status(progress: Int) =
        """{"id":1,"name":"P1S","connected":true,"state":"RUNNING","progress":$progress}"""

    private fun ring() =
        serverSide!!.send("""{"type":"printer_status","printer_id":1,"data":{"state":"RUNNING"}}""")

    private fun waitFor(what: String, ms: Long = 8_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (check()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @After
    fun tearDown() {
        Repo.stop()
        server.shutdown()
    }

    @Test
    fun theSocketRingsTheRestFetchAndDropsBackToPolling() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v1/auth/ws-token") -> {
                        tokenCalls.incrementAndGet()
                        MockResponse().setBody("""{"token":"t0k"}""")
                    }
                    path.startsWith("/api/v1/ws") -> {
                        wsPath = path
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSide = webSocket
                            }

                            // Answer the app's close, as Bambuddy's server does.
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                webSocket.close(1000, null)
                            }
                        })
                    }
                    path == "/api/v1/printers/" -> MockResponse().setBody("""[{"id":1,"name":"P1S"}]""")
                    path == "/api/v1/printers/1/status" ->
                        MockResponse().setBody(status(statusCalls.incrementAndGet()))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        Repo.init(RuntimeEnvironment.getApplication())
        Repo.prefs.serverUrl = server.url("/").toString()
        Repo.prefs.pollSeconds = 4
        Repo.setLiveUpdates(true)
        Repo.start()

        waitFor("the socket to open") { Repo.live.value == Live.State.LIVE && serverSide != null }
        assertEquals("/api/v1/ws?token=t0k", wsPath)
        waitFor("the first poll") { Repo.statuses.value[1] != null }
        // While live, the full poll is a heartbeat, so staleness is judged on that.
        assertEquals(90_000L, Repo.staleAfterMs())

        // One ring, one fetch of that printer, and the screen's data moves.
        Thread.sleep(1_700)
        val before = statusCalls.get()
        ring()
        waitFor("the ring to fetch") { statusCalls.get() == before + 1 }
        assertEquals(before + 1, Repo.statuses.value[1]!!.optInt("progress"))

        // A printer chattering every 50 ms costs a fetch every 1.5 s, not twenty.
        Thread.sleep(1_700)
        val burstFrom = statusCalls.get()
        repeat(20) {
            ring()
            Thread.sleep(50)
        }
        Thread.sleep(2_500)
        val burst = statusCalls.get() - burstFrom
        assertTrue("20 rings cost $burst fetches", burst in 1..3)

        // The server going away puts the poll back at its own rate.
        serverSide!!.close(1001, "going away")
        waitFor("the drop to be noticed") { Repo.live.value == Live.State.RETRYING }
        assertEquals(12_000L, Repo.staleAfterMs())
        // And it comes back by itself, with a fresh token.
        val tokensBefore = tokenCalls.get()
        waitFor("the reconnect", ms = 6_000) { Repo.live.value == Live.State.LIVE }
        assertTrue(tokenCalls.get() > tokensBefore)

        // Switching it off leaves nothing running.
        Repo.setLiveUpdates(false)
        assertEquals(Live.State.OFF, Repo.live.value)

        // Going off screen writes the last status for the next cold start.
        Repo.stop()
        val ctx = RuntimeEnvironment.getApplication()
        val file = File(ctx.noBackupFilesDir, "last-status.json")
        waitFor("the cache to be written") { file.isFile }
        val saved = Cache(file).read(Repo.prefs.serverUrl)
        assertNotNull(saved)
        assertEquals("P1S", saved!!.printers.single().optString("name"))
        assertEquals("RUNNING", saved.statuses[1]?.optString("state"))
    }
}
