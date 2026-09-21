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
 * just one stale frame instead of a socket to re-authenticate.
 *
 * Because a dropped request is routine on that wifi, a printer's last good
 * status is kept rather than deleted, and the time it was fetched is kept with
 * it so the screen can say plainly that it is no longer live.
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

    /** When the whole poll last came back clean. */
    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    /** When each printer's status was last fetched, so a card can date itself. */
    private val _fetchedAt = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val fetchedAt: StateFlow<Map<Int, Long>> = _fetchedAt.asStateFlow()

    /**
     * True when an account login has run out. Bambuddy's JWT lasts 24 hours and
     * there is no refresh route, so a phone propped against a printer wakes up
     * signed out; this is what turns that into one tap instead of Settings.
     */
    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired.asStateFlow()

    /** The last command's outcome, for a line he can still read a minute later. */
    data class Notice(val message: String, val at: Long, val failed: Boolean)

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    fun notice(message: String, failed: Boolean = false) {
        _notice.value = Notice(message, System.currentTimeMillis(), failed)
    }

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

    /** Data older than three poll intervals has missed enough turns to be doubted. */
    fun staleAfterMs(): Long =
        if (::prefs.isInitialized) prefs.pollSeconds * 3_000L else 12_000L

    /** How old one printer's status is, or null if it was never fetched. */
    fun ageMs(printerId: Int, now: Long = System.currentTimeMillis()): Long? =
        _fetchedAt.value[printerId]?.let { now - it }

    fun isStale(printerId: Int): Boolean {
        val age = ageMs(printerId) ?: return false
        return age > staleAfterMs()
    }

    /** Called once a fresh token is stored, to drop the signed-out state. */
    fun signedIn() {
        _authExpired.value = false
        _error.value = null
        refresh()
    }

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

            val now = System.currentTimeMillis()
            val next = HashMap<Int, JSONObject>(_statuses.value)
            val fetched = HashMap<Int, Long>(_fetchedAt.value)
            val live = HashSet<Int>()
            for (p in printers) {
                val id = p.optInt("id", -1)
                if (id < 0) continue
                live.add(id)
                try {
                    next[id] = api.printerStatus(id)
                    fetched[id] = now
                } catch (e: ApiError) {
                    // One failed request is not a printer disappearing. Keep the
                    // last good status and leave its timestamp where it was, so
                    // the card can dim itself instead of collapsing to nothing.
                    if (e.code == 401) throw e
                }
            }
            // A printer the server has stopped listing really is gone.
            next.keys.retainAll(live)
            fetched.keys.retainAll(live)

            _statuses.value = next
            _fetchedAt.value = fetched
            _error.value = null
            _authExpired.value = false
            _updatedAt.value = now
        } catch (e: ApiError) {
            _error.value = e.message
            // An API key never expires, so only a login can have run out.
            if (e.code == 401 && prefs.token.isNotBlank() && prefs.apiKey.isBlank()) {
                _authExpired.value = true
                _error.value = "Your sign-in has expired"
            }
        } catch (e: Exception) {
            _error.value = e.message ?: "Something went wrong"
        }
    }

    /**
     * Runs a control call off the main thread and hands back a result message.
     *
     * The message also goes to [notice], because the caller's Toast is gone in
     * two seconds and he is usually looking at the printer, not the phone.
     */
    fun action(label: String, block: () -> Unit, done: (String) -> Unit) {
        scope.launch {
            var failed = false
            val message = try {
                block()
                refreshSoon()
                "$label sent"
            } catch (e: ApiError) {
                failed = true
                "$label failed: ${e.message}"
            } catch (e: Exception) {
                failed = true
                "$label failed: ${e.message ?: "unknown error"}"
            }
            notice(message, failed)
            withContext(Dispatchers.Main) { done(message) }
        }
    }

    /** The printer needs a moment to report a command back over MQTT. */
    private suspend fun refreshSoon() {
        delay(700)
        pollOnce()
    }
}
