package io.github.krzkawa.bambuddyaio.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.random.Random

/**
 * Bambuddy's WebSocket, used as a doorbell rather than as the data.
 *
 * The socket's `printer_status` message is the server's internal state dict,
 * which is not the shape `GET /printers/{id}/status` answers with — the AMS in
 * particular is laid out differently — and every screen is built on the REST
 * shape. So a message only says *which* printer changed, and [Repo] fetches
 * that one printer's status the ordinary way. The screen changes within about
 * a second of the printer, and nothing downstream has to know two formats.
 *
 * Off by default, behind a setting: polling is what this app was built on and
 * it survives flaky wifi on an old phone without any state to lose. When the
 * socket is down for any reason the poll simply runs at its normal rate again,
 * so the worst this can do is nothing.
 */
class Live(
    private val api: Api,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<State>,
    private val onPrinter: (Int) -> Unit
) {

    enum class State {
        /** Switched off, or the app is not on screen. */
        OFF,
        CONNECTING,
        /** The socket is open and answering pings. */
        LIVE,
        /** Dropped; waiting before the next attempt, with polling covering the gap. */
        RETRYING
    }

    private val lock = Any()
    private var running = false
    /** Bumped on every start and stop, so callbacks from an old socket are ignored. */
    private var generation = 0
    private var socket: WebSocket? = null
    private var pending: Job? = null
    private var attempt = 0

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            generation++
            attempt = 0
            connect(generation, 0L)
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            generation++
            pending?.cancel()
            pending = null
            socket?.close(NORMAL_CLOSE, null)
            socket = null
            state.value = State.OFF
        }
    }

    private fun current(gen: Int) = running && gen == generation

    /** Must be called holding [lock]. */
    private fun connect(gen: Int, waitMs: Long) {
        pending = scope.launch {
            if (waitMs > 0) {
                state.value = State.RETRYING
                delay(waitMs)
            }
            synchronized(lock) {
                if (!current(gen)) return@launch
                state.value = State.CONNECTING
            }
            // Minted on every attempt: it lasts an hour, costs one small
            // request, and a socket refused with 4401 needs a fresh one anyway.
            val token = try {
                api.wsToken()
            } catch (e: ApiError) {
                // A Bambuddy from before the route existed took any socket.
                if (e.code == 404) null else return@launch dropped(gen)
            } catch (e: Exception) {
                return@launch dropped(gen)
            }
            synchronized(lock) {
                if (!current(gen)) return@launch
                socket = api.openLive(token, listener(gen))
            }
        }
    }

    private fun listener(gen: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!current(gen)) return
                attempt = 0
                state.value = State.LIVE
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!synchronized(lock) { current(gen) }) return
            printerIdOf(text)?.let(onPrinter)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(gen)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(gen)
    }

    private fun dropped(gen: Int) {
        synchronized(lock) {
            if (!current(gen)) return
            socket = null
            attempt++
            connect(gen, backoffMs(attempt, Random.nextDouble()))
        }
    }

    companion object {
        private const val NORMAL_CLOSE = 1000

        /** The messages that mean one printer's status has moved. */
        private val RINGS = setOf("printer_status", "print_start", "print_complete")

        /**
         * The printer a message is about, or null for anything that is not a
         * change of printer status (pongs, upload progress, inventory events).
         */
        fun printerIdOf(text: String): Int? = try {
            val message = JSONObject(text)
            if (message.optString("type") !in RINGS) {
                null
            } else {
                message.optInt("printer_id", -1).takeIf { it >= 0 }
            }
        } catch (e: Exception) {
            null
        }

        /**
         * 2, 4, 8, 16, 32 seconds, then once a minute, each stretched by up to
         * a fifth at random so a whole shelf of phones does not reconnect in
         * step after the server restarts. [jitter] is in 0..1.
         */
        fun backoffMs(attempt: Int, jitter: Double = 0.0): Long {
            val base = if (attempt >= 6) 60_000L else 1_000L shl attempt.coerceAtLeast(1)
            return base + (base * 0.2 * jitter.coerceIn(0.0, 1.0)).toLong()
        }
    }
}
