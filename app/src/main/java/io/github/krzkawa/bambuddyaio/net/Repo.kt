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
import java.io.File

/**
 * One poller for the whole app.
 *
 * Bambuddy pushes over a WebSocket, but polling its REST status is kinder to a
 * 2016 phone and survives the flaky wifi such phones have: a dropped request is
 * just one stale frame instead of a socket to re-authenticate.
 *
 * Because a dropped request is routine on that wifi, a printer's last good
 * status is kept rather than deleted, and the time it was fetched is kept with
 * it so the screen can say plainly that it is no longer live. The same goes for
 * a cold start: the last statuses are kept on disk by [Cache] and shown, dated,
 * until the server answers again.
 *
 * With live updates switched on, [Live] rings whenever the server says a
 * printer changed, that printer alone is fetched, and the full poll drops to a
 * heartbeat for as long as the socket stays up.
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

    /**
     * True while what is on screen was read back from disk at startup and the
     * server has not answered a full poll since. Anything that reacts to a
     * status *changing* should wait for this to go false, or a print that
     * finished while the app was closed reads as finishing now.
     */
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    /** Where the live socket is, when live updates are on. */
    private val _live = MutableStateFlow(Live.State.OFF)
    val live: StateFlow<Live.State> = _live.asStateFlow()

    private var cache: Cache? = null
    private var socket: Live? = null
    @Volatile private var savedAt = 0L

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = Prefs(ctx)
            api = Api(prefs)
            _selected.value = prefs.lastPrinterId
            socket = Live(api, scope, _live) { ring(it) }
            cache = Cache(File(ctx.applicationContext.noBackupFilesDir, CACHE_FILE))
            restore()
        }
    }

    fun select(printerId: Int) {
        _selected.value = printerId
        prefs.lastPrinterId = printerId
    }

    fun selectedStatus(): JSONObject? = _statuses.value[_selected.value]

    /** Data older than three poll intervals has missed enough turns to be doubted. */
    fun staleAfterMs(): Long =
        if (::prefs.isInitialized) intervalMs() * 3 else 12_000L

    /**
     * How long the poll waits between turns. While the socket is up it only
     * needs to be a heartbeat: the socket brings the changes, and a printer
     * that has not changed has nothing new to fetch.
     */
    private fun intervalMs(): Long {
        val poll = prefs.pollSeconds * 1000L
        return if (_live.value == Live.State.LIVE) maxOf(poll, HEARTBEAT_MS) else poll
    }

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
                // Counted out in short steps rather than one long delay, so the
                // moment the socket drops the poll is back at its own rate
                // instead of finishing out a heartbeat's wait first.
                val from = System.currentTimeMillis()
                while (isActive && System.currentTimeMillis() - from < intervalMs()) delay(TICK_MS)
            }
        }
        if (prefs.liveUpdates) socket?.start()
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.stop()
        // Going off screen is the last reliable moment before a power cut.
        scope.launch { save() }
    }

    /** Switches live updates on or off, taking effect at once if the app is running. */
    fun setLiveUpdates(on: Boolean) {
        prefs.liveUpdates = on
        if (on && loop?.isActive == true) socket?.start() else socket?.stop()
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
            _restored.value = false
            saveSoon()
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

    // --------------------------------------------------------------- live

    /** Printers with a fetch under way, and those rung again while it ran. */
    private val ringing = HashMap<Int, Job>()
    private val rungAgain = HashSet<Int>()

    /**
     * The socket says [printerId] changed. A printing printer can say so every
     * second, so fetches are coalesced: one at a time per printer, no closer
     * together than [RING_GAP_MS], and a ring that lands mid-fetch earns
     * exactly one more fetch after it.
     */
    private fun ring(printerId: Int) {
        synchronized(ringing) {
            if (ringing.containsKey(printerId)) {
                rungAgain.add(printerId)
                return
            }
            ringing[printerId] = scope.launch {
                do {
                    val last = _fetchedAt.value[printerId] ?: 0L
                    val wait = RING_GAP_MS - (System.currentTimeMillis() - last)
                    if (wait > 0) delay(wait)
                    pollPrinter(printerId)
                } while (synchronized(ringing) {
                        if (rungAgain.remove(printerId)) true else {
                            ringing.remove(printerId)
                            false
                        }
                    })
            }
        }
    }

    private fun pollPrinter(printerId: Int) {
        if (!prefs.configured) return
        // A printer this app has not heard of yet: the list needs fetching too.
        if (_printers.value.none { it.optInt("id", -1) == printerId }) {
            pollOnce()
            return
        }
        try {
            val status = api.printerStatus(printerId)
            val now = System.currentTimeMillis()
            _statuses.value = HashMap(_statuses.value).apply { put(printerId, status) }
            _fetchedAt.value = HashMap(_fetchedAt.value).apply { put(printerId, now) }
            saveSoon()
        } catch (e: Exception) {
            // The heartbeat poll is what reports a connection problem.
        }
    }

    // -------------------------------------------------------------- cache

    private fun restore() {
        val snapshot = cache?.read(prefs.serverUrl) ?: return
        if (snapshot.printers.isEmpty()) return
        _printers.value = snapshot.printers
        _statuses.value = snapshot.statuses
        _fetchedAt.value = snapshot.fetchedAt
        _updatedAt.value = snapshot.updatedAt
        _restored.value = true
    }

    /** Every good status is worth keeping, but not worth a flash write every few seconds. */
    private fun saveSoon() {
        if (System.currentTimeMillis() - savedAt >= SAVE_EVERY_MS) save()
    }

    private fun save() {
        val cache = cache ?: return
        // Nothing fresh to keep: leave the older snapshot alone.
        if (_restored.value || _updatedAt.value <= 0L || _printers.value.isEmpty()) return
        savedAt = System.currentTimeMillis()
        cache.write(
            Cache.Snapshot(
                server = prefs.serverUrl,
                printers = _printers.value,
                statuses = _statuses.value,
                fetchedAt = _fetchedAt.value,
                updatedAt = _updatedAt.value
            )
        )
    }

    private const val CACHE_FILE = "last-status.json"
    private const val SAVE_EVERY_MS = 60_000L
    private const val HEARTBEAT_MS = 30_000L
    private const val TICK_MS = 500L
    private const val RING_GAP_MS = 1_500L

    /** The printer needs a moment to report a command back over MQTT. */
    private suspend fun refreshSoon() {
        delay(700)
        pollOnce()
    }
}
