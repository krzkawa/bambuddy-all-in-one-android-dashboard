package io.github.krzkawa.bambuddyaio.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last printers and statuses the app saw, kept on disk.
 *
 * A phone propped against a printer is restarted by power cuts and updates,
 * and the server is often the thing that went down with it. Without this a
 * cold start with the server unreachable is an empty screen; with it, it is
 * the last known state, dimmed and dated by the same "Not live" lines a
 * dropped poll already uses, because the fetch times are kept alongside.
 *
 * Only what the server said about the printers goes in here: no address
 * beyond the one it was read from, and none of the credentials, which stay in
 * [Prefs]' encrypted file. The file lives in the app's no-backup directory so
 * it is left out of any backup, as the credentials are.
 */
class Cache(private val file: File) {

    data class Snapshot(
        /** The server this was read from; a snapshot of another server is never shown. */
        val server: String,
        val printers: List<JSONObject>,
        val statuses: Map<Int, JSONObject>,
        val fetchedAt: Map<Int, Long>,
        val updatedAt: Long
    )

    /** The saved snapshot for [server], or null if there is none, it is for another server, or too old. */
    fun read(server: String, now: Long = System.currentTimeMillis()): Snapshot? {
        if (server.isBlank()) return null
        val text = try {
            if (!file.isFile) return null
            file.readText()
        } catch (e: Exception) {
            return null
        }
        val snapshot = decode(text) ?: return null
        if (snapshot.server != server) return null
        if (now - snapshot.updatedAt > MAX_AGE_MS) return null
        return snapshot
    }

    /**
     * Written to a side file and renamed over the old one, so a phone that
     * loses power mid-write keeps the previous snapshot rather than half of one.
     * Synchronized because the poll and the app going off screen can both save.
     */
    @Synchronized
    fun write(snapshot: Snapshot) {
        try {
            val dir = file.parentFile
            if (dir != null && !dir.isDirectory) dir.mkdirs()
            val temp = File(file.path + ".new")
            temp.writeText(encode(snapshot))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            // A cache that cannot be written costs a blank screen on some later
            // cold start. It is never worth an error in front of him now.
        }
    }

    fun clear() {
        try {
            file.delete()
        } catch (e: Exception) {
            // Nothing there is nothing to clear.
        }
    }

    companion object {
        /** Past a week the numbers are history, not a status. */
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

        private const val VERSION = 1

        fun encode(s: Snapshot): String {
            val statuses = JSONObject()
            for ((id, status) in s.statuses) statuses.put(id.toString(), status)
            val fetched = JSONObject()
            for ((id, at) in s.fetchedAt) fetched.put(id.toString(), at)
            return JSONObject()
                .put("version", VERSION)
                .put("server", s.server)
                .put("updated_at", s.updatedAt)
                .put("printers", JSONArray(s.printers))
                .put("statuses", statuses)
                .put("fetched_at", fetched)
                .toString()
        }

        /** Null for anything this build did not write: a newer format, or a torn file. */
        fun decode(text: String): Snapshot? = try {
            val root = JSONObject(text)
            if (root.optInt("version") != VERSION) {
                null
            } else {
                val printers = ArrayList<JSONObject>()
                root.optJSONArray("printers")?.let { list ->
                    for (i in 0 until list.length()) list.optJSONObject(i)?.let { printers.add(it) }
                }
                val statuses = HashMap<Int, JSONObject>()
                root.optJSONObject("statuses")?.let { map ->
                    for (key in map.keys()) {
                        val id = key.toIntOrNull() ?: continue
                        map.optJSONObject(key)?.let { statuses[id] = it }
                    }
                }
                val fetched = HashMap<Int, Long>()
                root.optJSONObject("fetched_at")?.let { map ->
                    for (key in map.keys()) {
                        val id = key.toIntOrNull() ?: continue
                        val at = map.optLong(key, 0L)
                        if (at > 0L) fetched[id] = at
                    }
                }
                Snapshot(
                    server = root.optString("server"),
                    printers = printers,
                    statuses = statuses,
                    fetchedAt = fetched,
                    updatedAt = root.optLong("updated_at", 0L)
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
