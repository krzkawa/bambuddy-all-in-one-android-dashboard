package io.github.krzkawa.bambuddyaio.net

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(val code: Int, message: String) : IOException(message)

/**
 * How long the camera stream will wait for the next byte before giving up.
 *
 * Far longer than any real gap between frames at the frame rates this app asks
 * for, and short enough that a stream which has silently died is noticed while
 * the user is still looking at the screen.
 */
const val STREAM_READ_TIMEOUT_SECONDS = 30L

/**
 * Thin blocking client for a self-hosted Bambuddy server's REST API.
 *
 * Every call must run off the main thread. Auth is whatever the user set up:
 * an API key goes in X-API-Key, an account login's JWT goes in Authorization.
 * Both are accepted by the server, and an install with auth switched off needs
 * neither.
 */
class Api(private val prefs: Prefs) {

    private val json = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Separate client for the camera: an MJPEG stream arrives frame by frame for
     * as long as the screen is open, so the read timeout above would cut it off.
     *
     * It is not unlimited, though, and that matters more than it looks. A thread
     * blocked in a socket read ignores Thread.interrupt(), so a stream that
     * simply stops arriving — the printer dropping off mid-stream — would park
     * its reader forever, holding a thread and a socket for the life of the
     * process. Cancelling the call is what normally frees it; this timeout is
     * the backstop for when nothing cancels it at all.
     */
    val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- plumbing

    private fun base(): HttpUrl {
        val root = prefs.serverUrl
        if (root.isBlank()) throw ApiError(0, "No server address set")
        val url = (if (root.startsWith("http")) root else "http://$root").toHttpUrlOrNull()
            ?: throw ApiError(0, "Server address is not a valid URL")
        return url
    }

    /** Builds /api/v1/<path> with query parameters appended. */
    fun url(path: String, vararg query: Pair<String, Any?>): HttpUrl {
        val b = base().newBuilder()
        b.addPathSegments("api/v1")
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        // Several list routes are declared as "/" on the server; without the
        // trailing segment FastAPI answers with a redirect on every poll.
        if (path.endsWith("/")) b.addPathSegment("")
        for ((k, v) in query) if (v != null) b.addQueryParameter(k, v.toString())
        return b.build()
    }

    fun authHeaders(b: Request.Builder): Request.Builder {
        if (prefs.apiKey.isNotBlank()) b.header("X-API-Key", prefs.apiKey)
        if (prefs.token.isNotBlank()) b.header("Authorization", "Bearer ${prefs.token}")
        b.header("Accept", "application/json")
        return b
    }

    private fun call(request: Request): String {
        val response: Response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError(0, e.message ?: "Cannot reach the server")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ApiError(it.code, detail(it.code, text))
            return text
        }
    }

    /** FastAPI puts the useful part in "detail"; fall back to the status line. */
    private fun detail(code: Int, body: String): String {
        val fallback = when (code) {
            401 -> "Not authorised — check the API key or sign in again"
            403 -> "This key is not allowed to do that"
            404 -> "Not found"
            429 -> "Too many requests, slow down"
            else -> "Server returned $code"
        }
        if (body.isBlank()) return fallback
        return try {
            val d = JSONObject(body).opt("detail")
            when (d) {
                null -> fallback
                is JSONArray -> d.optJSONObject(0)?.optString("msg").takeIf { !it.isNullOrBlank() } ?: fallback
                else -> d.toString()
            }
        } catch (e: Exception) {
            fallback
        }
    }

    private fun req(url: HttpUrl) = authHeaders(Request.Builder().url(url))

    fun getObject(path: String, vararg q: Pair<String, Any?>): JSONObject =
        JSONObject(call(req(url(path, *q)).get().build()))

    fun getArray(path: String, vararg q: Pair<String, Any?>): JSONArray =
        JSONArray(call(req(url(path, *q)).get().build()))

    fun getRaw(path: String, vararg q: Pair<String, Any?>): String =
        call(req(url(path, *q)).get().build())

    private fun body(payload: JSONObject?): RequestBody =
        (payload?.toString() ?: "").toRequestBody(json)

    fun post(path: String, payload: JSONObject? = null, vararg q: Pair<String, Any?>): String =
        call(req(url(path, *q)).post(body(payload)).build())

    fun postObject(path: String, payload: JSONObject? = null, vararg q: Pair<String, Any?>): JSONObject =
        JSONObject(post(path, payload, *q).ifBlank { "{}" })

    fun patchObject(path: String, payload: JSONObject, vararg q: Pair<String, Any?>): JSONObject =
        JSONObject(call(req(url(path, *q)).patch(body(payload)).build()).ifBlank { "{}" })

    fun delete(path: String, vararg q: Pair<String, Any?>): String =
        call(req(url(path, *q)).delete().build())

    // ------------------------------------------------------------------- auth

    /** Public endpoint: says whether this install asks for a login at all. */
    fun authStatus(): JSONObject = getObject("auth/status")

    /**
     * Account login. Returns the JWT, or throws when the server wants a second
     * factor, which this app cannot collect yet.
     */
    fun login(username: String, password: String): String {
        val payload = JSONObject().put("username", username).put("password", password)
        val res = postObject("auth/login", payload)
        if (res.optBoolean("requires_2fa")) {
            throw ApiError(401, "This account uses two-factor login. Use an API key instead.")
        }
        val token = res.optString("access_token")
        if (token.isBlank()) throw ApiError(401, "Server did not return a token")
        return token
    }

    fun me(): JSONObject = getObject("auth/me")

    // --------------------------------------------------------------- printers

    fun printers(): JSONArray = getArray("printers/")

    fun printerStatus(id: Int): JSONObject = getObject("printers/$id/status")

    fun refreshStatus(id: Int) { post("printers/$id/refresh-status") }

    fun pause(id: Int) { post("printers/$id/print/pause") }
    fun resume(id: Int) { post("printers/$id/print/resume") }
    fun stop(id: Int) { post("printers/$id/print/stop") }
    fun clearPlate(id: Int) { post("printers/$id/clear-plate") }
    fun clearHms(id: Int) { post("printers/$id/hms/clear") }
    fun homeAxes(id: Int) { post("printers/$id/home-axes", null, "axes" to "all") }

    fun setSpeed(id: Int, mode: Int) { post("printers/$id/print-speed", null, "mode" to mode) }
    fun setNozzleTemp(id: Int, target: Int, nozzle: Int = 0) {
        post("printers/$id/temperature/nozzle", null, "target" to target, "nozzle" to nozzle)
    }
    fun setBedTemp(id: Int, target: Int) {
        post("printers/$id/temperature/bed", null, "target" to target)
    }
    fun setChamberTemp(id: Int, target: Int) {
        post("printers/$id/temperature/chamber", null, "target" to target)
    }
    fun setFan(id: Int, fan: String, speed: Int) {
        post("printers/$id/fan-speed", null, "fan" to fan, "speed" to speed)
    }
    fun setLight(id: Int, on: Boolean) {
        post("printers/$id/chamber-light", null, "on" to on)
    }

    fun amsLoad(id: Int, trayId: Int) { post("printers/$id/ams/load", null, "tray_id" to trayId) }
    fun amsUnload(id: Int, trayId: Int?) { post("printers/$id/ams/unload", null, "tray_id" to trayId) }
    fun refreshSlotRfid(id: Int, amsId: Int, slotId: Int) {
        post("printers/$id/ams/$amsId/slot/$slotId/refresh")
    }
    fun startDrying(id: Int, amsId: Int, temp: Int, hours: Int, filament: String = "") {
        post("printers/$id/drying/start", null,
            "ams_id" to amsId, "temp" to temp, "duration" to hours, "filament" to filament)
    }
    fun stopDrying(id: Int, amsId: Int) {
        post("printers/$id/drying/stop", null, "ams_id" to amsId)
    }

    // -------------------------------------------------------------- inventory

    fun spools(includeArchived: Boolean = false): JSONArray =
        getArray("inventory/spools", "include_archived" to includeArchived)

    /** Null when the tag is not linked to any spool yet. */
    /**
     * Finds the spool a scanned tag belongs to, or null when there is none.
     *
     * Archived spools are included by default: the server leaves them out unless asked,
     * so rescanning a spool that was archived would otherwise report "not in your
     * inventory" and invite a duplicate.
     */
    fun spoolByTag(trayUuid: String?, tagUid: String?, includeArchived: Boolean = true): JSONObject? = try {
        getObject("inventory/spools/by-tag",
            "tray_uuid" to trayUuid?.ifBlank { null },
            "tag_uid" to tagUid?.ifBlank { null },
            "include_archived" to includeArchived)
    } catch (e: ApiError) {
        if (e.code == 404) null else throw e
    }

    fun createSpool(payload: JSONObject): JSONObject = postObject("inventory/spools", payload)

    fun linkTag(spoolId: Int, tagUid: String?, trayUuid: String?): JSONObject {
        val payload = JSONObject()
        if (!tagUid.isNullOrBlank()) payload.put("tag_uid", tagUid)
        if (!trayUuid.isNullOrBlank()) payload.put("tray_uuid", trayUuid)
        return patchObject("inventory/spools/$spoolId/link-tag", payload)
    }

    /** Puts a spool in a slot. Stays on Bambuddy; it also configures the tray over MQTT. */
    fun assign(spoolId: Int, printerId: Int, amsId: Int, trayId: Int): JSONObject {
        val payload = JSONObject()
            .put("spool_id", spoolId)
            .put("printer_id", printerId)
            .put("ams_id", amsId)
            .put("tray_id", trayId)
        return postObject("inventory/assignments", payload)
    }

    fun assignments(): JSONArray = getArray("inventory/assignments")

    /**
     * Edits a spool. Only send the fields that actually changed: the server
     * applies exactly the keys present, and setting `weight_used` also sets
     * `weight_locked` (`inventory.py:1394`), which stops the AMS syncing this
     * spool's weight. Sending an unchanged weight would lock it by accident.
     */
    fun updateSpool(spoolId: Int, payload: JSONObject): JSONObject =
        patchObject("inventory/spools/$spoolId", payload)

    /** Soft delete: sets archived_at, so the spool leaves the default listing. */
    fun archiveSpool(spoolId: Int): JSONObject = postObject("inventory/spools/$spoolId/archive")

    fun restoreSpool(spoolId: Int): JSONObject = postObject("inventory/spools/$spoolId/restore")

    /**
     * Zeroes the "total consumed" counter only. Remaining weight is deliberately
     * left alone — the server moves a baseline rather than touching `weight_used`
     * (`inventory.py:1465`). Do not label this as emptying or refilling a spool.
     */
    fun resetConsumedCounter(spoolId: Int): JSONObject =
        postObject("inventory/spools/$spoolId/reset-consumed-counter")

    /** Print runs that drew from this spool, newest first. */
    fun spoolUsage(spoolId: Int, limit: Int = 25): JSONArray =
        getArray("inventory/spools/$spoolId/usage", "limit" to limit)

    /** Storage locations, each with the number of spools in it. */
    fun locations(): JSONArray = getArray("inventory/locations")

    fun unassign(printerId: Int, amsId: Int, trayId: Int) {
        delete("inventory/assignments/$printerId/$amsId/$trayId")
    }

    // ------------------------------------------------------- queue / archives

    fun queue(status: String? = null): JSONArray = getArray("queue/", "status" to status)
    fun queueRemove(itemId: Int) { delete("queue/$itemId") }

    /** The slim listing: one row per print run, which is all the history screen shows. */
    fun archives(limit: Int = 40): JSONArray = getArray("archives/slim", "limit" to limit)

    fun statistics(): JSONObject = getObject("archives/stats")

    fun systemInfo(): JSONObject = getObject("system/info")

    // ----------------------------------------------------------------- camera

    /**
     * The stream and snapshot routes take a token in the query string rather
     * than a header, because the server built them for browser <img> tags.
     * Tokens last an hour.
     */
    fun cameraToken(): String = postObject("printers/camera/stream-token").optString("token")

    fun cameraStreamUrl(printerId: Int, fps: Int, token: String?): HttpUrl =
        url("printers/$printerId/camera/stream", "fps" to fps, "token" to token?.ifBlank { null })

    fun cameraSnapshotUrl(printerId: Int, token: String?): HttpUrl =
        url("printers/$printerId/camera/snapshot", "token" to token?.ifBlank { null })

    fun cameraStop(printerId: Int) { post("printers/$printerId/camera/stop") }

    /**
     * Tells the server the stream is finished, without blocking the caller.
     *
     * The moment to say this is as the camera screen goes away, and by then
     * there is no lifecycle left to hang a blocking call on and nothing useful
     * to do with the answer. So it goes out on OkHttp's own dispatcher and its
     * outcome is dropped: either the server hears it, or the stream it is still
     * holding open times out by itself.
     */
    fun cameraStopAsync(printerId: Int) {
        val request = req(url("printers/$printerId/camera/stop")).post(body(null)).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }
}
