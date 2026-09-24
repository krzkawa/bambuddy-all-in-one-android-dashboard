package io.github.krzkawa.bambuddyaio.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Looks for a Bambuddy server on the phone's own network, so setup is a tap
 * rather than an IP address typed on a phone keyboard.
 *
 * It knocks on every address in the phone's /24 and asks anything that opens
 * `GET /api/v1/auth/status`, the one route Bambuddy answers without a login.
 * Only a reply with Bambuddy's own `auth_enabled` field counts, so a router's
 * admin page on port 80 is not mistaken for it. Bambuddy's own port, 8000, is
 * tried across the network first; 80 and 8080, for a server behind a proxy,
 * only if nothing answered there.
 */
object Finder {

    data class Found(
        /** Ready for the address box, e.g. "http://192.168.1.50:8000". */
        val url: String,
        val authEnabled: Boolean,
        /** Nobody has finished Bambuddy's first-run setup in a browser yet. */
        val needsSetup: Boolean
    )

    /** Bambuddy's default first, then what a reverse proxy usually listens on. */
    val PORT_ROUNDS = listOf(listOf(8000), listOf(80, 8080))

    private const val PARALLEL = 48
    /** Long enough for a phone's wifi waking from power saving; a real host answers far sooner. */
    private const val KNOCK_MS = 600

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    /**
     * The phone's address on the local network, or null when it is not on one.
     * Mobile data and VPN interfaces are skipped: a server is never there.
     */
    fun localAddress(): Inet4Address? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            return null
        }
        val usable = candidates.filter { nic ->
            try {
                nic.isUp && !nic.isLoopback && SKIPPED.none { nic.name.startsWith(it) }
            } catch (e: Exception) {
                false
            }
        }
        // Wifi first: on this phone it is the only network that matters.
        val ordered = usable.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        for (nic in ordered) {
            for (address in nic.inetAddresses.toList()) {
                if (address is Inet4Address && address.isSiteLocalAddress) return address
            }
        }
        return null
    }

    private val SKIPPED = listOf("rmnet", "ccmni", "pdp", "tun", "ppp", "p2p", "dummy")

    /** The other addresses in [self]'s /24, nearest first. */
    fun neighbours(self: Inet4Address): List<String> {
        val octets = self.address.map { it.toInt() and 0xff }
        val prefix = "${octets[0]}.${octets[1]}.${octets[2]}."
        val own = octets[3]
        return (1..254).filter { it != own }.sortedBy { Math.abs(it - own) }.map { prefix + it }
    }

    /** The address box's form of [host]:[port]; port 80 needs no number. */
    fun urlFor(host: String, port: Int): String =
        if (port == 80) "http://$host" else "http://$host:$port"

    /** Whether an `/auth/status` reply is Bambuddy's, and what it says if so. */
    fun parse(url: String, body: String): Found? = try {
        val json = JSONObject(body)
        if (!json.has("auth_enabled")) {
            null
        } else {
            Found(url, json.optBoolean("auth_enabled", true), json.optBoolean("requires_setup", false))
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Scans [hosts] and hands each server to [onFound] as it answers, on
     * whatever thread found it. Returns everything found, in address order.
     * Stops after the first round of ports that turns anything up.
     */
    suspend fun scan(hosts: List<String>, onFound: (Found) -> Unit): List<Found> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        val found = Collections.synchronizedList(ArrayList<Found>())
        for (ports in PORT_ROUNDS) {
            hosts.flatMap { host ->
                ports.map { port ->
                    launch(Dispatchers.IO) {
                        gate.withPermit {
                            probe(host, port)?.let {
                                found.add(it)
                                onFound(it)
                            }
                        }
                    }
                }
            }.joinAll()
            if (found.isNotEmpty()) break
        }
        synchronized(found) { found.sortedBy { hostOrder(it.url) } }
    }

    private fun hostOrder(url: String): Long {
        val host = url.removePrefix("http://").substringBefore(':')
        val last = host.substringAfterLast('.').toLongOrNull() ?: 0L
        val port = url.substringAfterLast(':', "80").toLongOrNull() ?: 80L
        return last * 100_000 + port
    }

    /** A bare TCP knock first, because almost every address has nothing there at all. */
    private fun probe(host: String, port: Int): Found? {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), KNOCK_MS) }
        } catch (e: Exception) {
            return null
        }
        val url = urlFor(host, port)
        return try {
            val request = Request.Builder()
                .url("$url/api/v1/auth/status")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else parse(url, response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            null
        }
    }
}
