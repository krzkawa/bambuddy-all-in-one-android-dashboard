package io.github.krzkawa.bambuddyaio.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One poller for the whole app.
 *
 * Bambuddy pushes over a WebSocket, but polling its REST status is kinder to a
 * 2016 phone and survives the flaky wifi such phones have: a dropped request is
 * just one stale frame instead of a socket to re-authenticate. The server's read
 * limit is 100/min, and one printer at the default 4s interval uses about 15.
 */
object Repo {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set

    private val _printers = MutableStateFlow<List<JSONObject>>(emptyList())
    val printers: StateFlow<List<JSONObject>> = _printers.asStateFlow()

    private val _statuses = MutableStateFlow<Map<Int, JSONObject>>(emptyMap())
    val statuses: StateFlow<Map<Int, JSONObject>> = _statuses.asStateFlow()

    /** Last connection problem, or null while things are healthy. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    private val _selected = MutableStateFlow(-1)
    val selected: StateFlow<Int> = _selected.asStateFlow()

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = Prefs(ctx)
            api = Api(prefs)
            _selected.value = prefs.lastPrinterId
        }
    }

    fun select(printerId: Int) {
        _selected.value = printerId
        prefs.lastPrinterId = printerId
    }

    fun selectedStatus(): JSONObject? = _statuses.value[_selected.value]

    fun printerName(id: Int): String =
        _printers.value.firstOrNull { it.optInt("id") == id }?.optString("name").orEmpty()
            .ifBlank { _statuses.value[id]?.optString("name").orEmpty() }
            .ifBlank { "Printer $id" }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                pollOnce()
                delay(prefs.pollSeconds * 1000L)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** Refreshes immediately; safe to call from the UI thread. */
    fun refresh() {
        scope.launch { pollOnce() }
    }

    suspend fun refreshBlocking() = withContext(Dispatchers.IO) { pollOnce() }

    private fun pollOnce() {
        if (!prefs.configured) return
        try {
            val list = api.printers()
            val printers = ArrayList<JSONObject>(list.length())
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { printers.add(it) }
            _printers.value = printers

            if (_selected.value < 0 && printers.isNotEmpty()) {
                select(printers[0].optInt("id"))
            }

            val next = HashMap<Int, JSONObject>(_statuses.value)
            for (p in printers) {
                val id = p.optInt("id", -1)
                if (id < 0) continue
                try {
                    next[id] = api.printerStatus(id)
                } catch (e: ApiError) {
                    // One printer being unreachable must not blank the others.
                    next.remove(id)
                }
            }
            _statuses.value = next
            _error.value = null
            _updatedAt.value = System.currentTimeMillis()
        } catch (e: ApiError) {
            _error.value = e.message
        } catch (e: Exception) {
            _error.value = e.message ?: "Something went wrong"
        }
    }

    /** Runs a control call off the main thread and hands back a result message. */
    fun action(label: String, block: () -> Unit, done: (String) -> Unit) {
        scope.launch {
            val message = try {
                block()
                refreshSoon()
                "$label sent"
            } catch (e: ApiError) {
                "$label failed: ${e.message}"
            } catch (e: Exception) {
                "$label failed: ${e.message ?: "unknown error"}"
            }
            withContext(Dispatchers.Main) { done(message) }
        }
    }

    /** The printer needs a moment to report a command back over MQTT. */
    private suspend fun refreshSoon() {
        delay(700)
        pollOnce()
    }
}
