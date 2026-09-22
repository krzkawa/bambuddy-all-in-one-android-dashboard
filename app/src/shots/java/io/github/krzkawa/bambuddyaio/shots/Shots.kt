package io.github.krzkawa.bambuddyaio.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Not a test: a way to look at the screens without a phone in hand.
 *
 * Deliberately not part of the build that runs in CI — it exists so a change to
 * the way the app looks can be checked against the real views, with the real
 * fonts, before it is pushed to the release everyone installs from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Shots {

    @Suppress("UNCHECKED_CAST")
    private fun <T> seed(field: String, value: T) {
        val f = Repo::class.java.getDeclaredField(field)
        f.isAccessible = true
        (f.get(Repo) as MutableStateFlow<T>).value = value
    }

    private fun printer(id: Int, name: String, model: String) =
        JSONObject().put("id", id).put("name", name).put("model", model)

    private fun tray(id: Int, type: String?, colour: String?, remain: Int, state: Int) =
        JSONObject().put("id", id).put("tray_type", type).put("tray_color", colour)
            .put("remain", remain).put("state", state)
            .also { if (type != null) it.put("tray_uuid", "abc$id") }

    private fun status(): JSONObject {
        val trays = JSONArray()
            .put(tray(0, "PLA", "F5F5F5FF", 82, 11))
            .put(tray(1, "PETG", "1E88E5FF", 41, 10))
            .put(tray(2, "PLA", "E53935FF", 7, 10))
            .put(tray(3, null, null, -1, 9))
        val ams = JSONArray().put(
            JSONObject().put("id", 0).put("humidity", 32).put("temp", 27.0).put("tray", trays)
        )
        return JSONObject()
            .put("state", "RUNNING").put("connected", true).put("progress", 64.0)
            .put("layer_num", 118).put("total_layers", 184).put("remaining_time", 97)
            .put("subtask_name", "bracket_v3_plate.gcode.3mf")
            .put("temperatures", JSONObject()
                .put("nozzle", 219.0).put("nozzle_target", 220.0)
                .put("bed", 59.0).put("bed_target", 60.0)
                .put("chamber", 34.0))
            .put("cooling_fan_speed", 100).put("big_fan1_speed", 50).put("big_fan2_speed", 0)
            .put("speed_level", 2).put("chamber_light", true)
            .put("wired_network", true).put("door_open", false).put("sdcard", true)
            .put("tray_now", 0).put("printable_objects_count", 3)
            .put("supports_drying", true)
            .put("ams", ams)
    }

    private fun idle(): JSONObject = JSONObject()
        .put("state", "FINISH").put("connected", true).put("progress", 100.0)
        .put("awaiting_plate_clear", true)
        .put("subtask_name", "calibration_cube.3mf")
        .put("temperatures", JSONObject().put("nozzle", 41.0).put("bed", 28.0))

    /**
     * A stand-in Bambuddy, so the parts of a screen that come from a request
     * rather than from the status poll are in the picture too.
     */
    private fun fakeServer(): Int {
        val queue = JSONArray()
            .put(JSONObject().put("id", 11).put("status", "printing")
                .put("archive_name", "bracket_v3_plate.gcode.3mf").put("printer_id", 1))
            .put(JSONObject().put("id", 12).put("status", "pending")
                .put("archive_name", "hinge_left_x4.3mf").put("printer_id", 1)
                .put("print_time_seconds", 4920).put("filament_used_grams", 38.0))
            .put(JSONObject().put("id", 13).put("status", "pending")
                .put("archive_name", "vase_spiral.3mf").put("printer_id", 1)
                .put("filament_short", true))
            .put(JSONObject().put("id", 14).put("status", "completed")
                .put("archive_name", "old_and_done.3mf").put("printer_id", 1))
        // A bare socket rather than com.sun.net.httpserver, which the android.jar
        // these tests compile against does not carry.
        val socket = java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())
        Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        while (true) {
                            val header = reader.readLine()
                            if (header.isNullOrBlank()) break
                        }
                        val body = if (request.contains("queue")) queue.toString() else "[]"
                        val bytes = body.toByteArray()
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray()
                            )
                            write(bytes)
                            flush()
                        }
                    }
                } catch (e: Exception) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true }.start()
        return socket.localPort
    }

    @Test
    fun shoot() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Repo.init(app)
        Repo.prefs.serverUrl = "http://127.0.0.1:${fakeServer()}"
        Repo.prefs.apiKey = "bb_demo"
        Repo.prefs.fullScreen = true

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        Repo.stop()

        seed("_printers", listOf(printer(1, "X1 Carbon", "X1C"), printer(2, "P1S", "P1S")))
        seed("_statuses", mapOf(1 to status(), 2 to idle()))
        seed("_fetchedAt", mapOf(1 to System.currentTimeMillis(), 2 to System.currentTimeMillis()))
        seed("_updatedAt", System.currentTimeMillis())
        seed("_error", null as String?)
        Repo.select(1)

        val out = File(System.getProperty("shots.dir") ?: "build/shots")
        out.mkdirs()

        listOf(
            0 to "printers", 1 to "control", 3 to "ams",
            4 to "scan", 6 to "queue", 8 to "stats", 9 to "settings"
        ).forEach { (tab, name) ->
            activity.showTab(tab)
            shadowOf(Looper.getMainLooper()).idle()
            // A screen that fetches does so on a real background thread here,
            // so give it a moment to come back before drawing.
            Thread.sleep(400)
            Repo.stop()
            seed("_error", null as String?)
            shadowOf(Looper.getMainLooper()).idle()
            capture(activity.window.decorView, File(out, "$name.png"))
        }
    }

    private fun capture(view: View, file: File) {
        val w = view.resources.displayMetrics.widthPixels
        val h = view.resources.displayMetrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
