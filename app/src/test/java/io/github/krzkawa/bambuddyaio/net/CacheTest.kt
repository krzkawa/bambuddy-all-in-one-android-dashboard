package io.github.krzkawa.bambuddyaio.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val server = "http://192.168.1.50:8000"
    private val now = 1_800_000_000_000L

    private fun snapshot(updatedAt: Long = now - 60_000L) = Cache.Snapshot(
        server = server,
        printers = listOf(JSONObject().put("id", 3).put("name", "X1C")),
        statuses = mapOf(
            3 to JSONObject()
                .put("state", "RUNNING")
                .put("progress", 41)
                .put("temperatures", JSONObject().put("nozzle", 219.5))
        ),
        fetchedAt = mapOf(3 to updatedAt - 2_000L),
        updatedAt = updatedAt
    )

    @Test
    fun aSnapshotComesBackAsItWasWritten() {
        val cache = Cache(dir.root.resolve("last-status.json"))
        cache.write(snapshot())

        val back = cache.read(server, now)
        assertNotNull(back)
        back!!
        assertEquals("X1C", back.printers.single().optString("name"))
        assertEquals("RUNNING", back.statuses[3]?.optString("state"))
        assertEquals(219.5, back.statuses[3]!!.getJSONObject("temperatures").getDouble("nozzle"), 0.0)
        // The fetch time is what dates the card, so it must survive exactly.
        assertEquals(now - 62_000L, back.fetchedAt[3])
        assertEquals(now - 60_000L, back.updatedAt)
    }

    @Test
    fun anotherServersSnapshotIsNeverShown() {
        val cache = Cache(dir.root.resolve("last-status.json"))
        cache.write(snapshot())
        assertNull(cache.read("http://10.0.0.9:8000", now))
    }

    @Test
    fun aSnapshotOlderThanAWeekIsDropped() {
        val cache = Cache(dir.root.resolve("last-status.json"))
        cache.write(snapshot(updatedAt = now - Cache.MAX_AGE_MS - 1))
        assertNull(cache.read(server, now))
    }

    @Test
    fun aMissingOrTornFileReadsAsNothing() {
        val file = dir.root.resolve("last-status.json")
        assertNull(Cache(file).read(server, now))
        file.writeText("{\"version\":1,\"server\":\"$server\",\"printers\":[{\"id\"")
        assertNull(Cache(file).read(server, now))
    }

    @Test
    fun aFormatThisBuildDidNotWriteIsIgnored() {
        val text = Cache.encode(snapshot()).replace("\"version\":1", "\"version\":2")
        assertNull(Cache.decode(text))
    }

    @Test
    fun writingIntoAMissingDirectoryCreatesIt() {
        val cache = Cache(dir.root.resolve("nested/deeper/last-status.json"))
        cache.write(snapshot())
        assertNotNull(cache.read(server, now))
    }

    @Test
    fun noCredentialEverGoesIntoTheFile() {
        val text = Cache.encode(snapshot())
        for (word in listOf("apiKey", "token", "password", "X-API-Key")) {
            assert(!text.contains(word, ignoreCase = true)) { "cache mentions $word" }
        }
    }
}
