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
            .put("firmware_version", "01.08.02.00")
            .put("print_options", JSONObject()
                .put("spaghetti_detector", true).put("first_layer_inspector", true)
                .put("halt_print_sensitivity", "medium").put("auto_recovery_step_loss", true))
            .put("ams", ams)
    }

    private fun idle(): JSONObject = JSONObject()
        .put("state", "FINISH").put("connected", true).put("progress", 100.0)
        .put("awaiting_plate_clear", true)
        .put("subtask_name", "calibration_cube.3mf")
        .put("temperatures", JSONObject().put("nozzle", 41.0).put("bed", 28.0))
        .put("firmware_version", "01.07.00.00")
        .put("print_options", JSONObject().put("spaghetti_detector", true)
            .put("buildplate_marker_detector", true))

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
                        val body = when {
                            request.contains("queue") -> queue.toString()
                            else -> machineExtras(request) ?: "[]"
                        }
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

    /** Plugs, maintenance counters and firmware, as the Printers and Control screens ask for them. */
    private fun machineExtras(request: String): String? = when {
        request.contains("smart-plugs/by-printer/1") ->
            JSONObject().put("id", 7).put("name", "Shelf plug").put("plug_type", "tasmota").toString()
        request.contains("smart-plugs/by-printer/2") ->
            JSONObject().put("id", 8).put("name", "Desk plug").put("plug_type", "tasmota").toString()
        request.contains("smart-plugs/7/status") ->
            JSONObject().put("state", "ON").put("reachable", true)
                .put("energy", JSONObject().put("power", 142.0).put("today", 0.84)).toString()
        request.contains("smart-plugs/8/status") ->
            JSONObject().put("state", "ON").put("reachable", true)
                .put("energy", JSONObject().put("power", 9.0)).toString()
        request.contains("maintenance/overview") -> JSONArray()
            .put(JSONObject().put("printer_id", 1).put("maintenance_items", JSONArray()
                .put(JSONObject().put("id", 31).put("maintenance_type_name", "Lubricate rods")
                    .put("enabled", true).put("is_due", true).put("is_warning", false)
                    .put("interval_type", "hours").put("hours_until_due", -12.0)
                    .put("last_performed_at", "2026-08-01T10:00:00"))
                .put(JSONObject().put("id", 32).put("maintenance_type_name", "Clean nozzle")
                    .put("enabled", true).put("is_due", true).put("is_warning", false)
                    .put("interval_type", "hours").put("hours_until_due", -3.0)
                    .put("last_performed_at", "2026-08-01T10:00:00"))))
            .toString()
        request.contains("firmware/updates") -> JSONObject().put("updates", JSONArray()
            .put(JSONObject().put("printer_id", 2).put("current_version", "01.07.00.00")
                .put("latest_version", "01.08.01.00").put("update_available", true)))
            .toString()
        else -> null
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

        // Settings runs past one screen; the phone's own cards are further down.
        activity.showTab(9)
        shadowOf(Looper.getMainLooper()).idle()
        val scroller = activity.findViewById<android.view.ViewGroup>(io.github.krzkawa.bambuddyaio.R.id.content_frame)
            .getChildAt(0) as android.widget.ScrollView
        listOf(1, 2, 3).forEach { page ->
            capture(activity.window.decorView, File(out, "settings-$page.png")) {
                scroller.scrollTo(0, page * 600)
            }
        }

        // What a finished print looks like when it lands on the status strip.
        activity.showTab(0)
        io.github.krzkawa.bambuddyaio.appliance.Alerts.announce(
            activity,
            io.github.krzkawa.bambuddyaio.appliance.Events.between(
                2, "P1S", status(), idle().put("state", "FINISH").put("subtask_name", "benchy.gcode.3mf")
            )
        )
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        capture(activity.window.decorView, File(out, "alert.png"))
        // Control for an idle printer, where the Move card is offered: locked,
        // then unlocked, each drawn at full length so nothing is cut off.
        Repo.select(2)
        listOf(false, true).forEach { open ->
            val move = Class.forName("io.github.krzkawa.bambuddyaio.ui.Move")
            move.getDeclaredField("unlockedFor").apply { isAccessible = true }.setInt(null, if (open) 2 else -1)
            move.getDeclaredField("unlockedUntil").apply { isAccessible = true }
                .setLong(null, if (open) System.currentTimeMillis() + 600_000L else 0L)
            activity.showTab(1)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(400)
            Repo.stop()
            shadowOf(Looper.getMainLooper()).idle()
            captureFull(activity.window.decorView, File(out, if (open) "control-idle-unlocked.png" else "control-idle.png"))
        }
        Repo.select(1)
        activity.showTab(1)
        shadowOf(Looper.getMainLooper()).idle()
        captureFull(activity.window.decorView, File(out, "control-full.png"))
    }

    /** The screen's own scroller drawn at its whole height, for screens longer than the phone. */
    private fun captureFull(root: View, file: File) {
        capture(root, File(file.parentFile, "tmp.png"))
        File(file.parentFile, "tmp.png").delete()
        val scrollers = ArrayList<android.widget.ScrollView>()
        fun walk(v: View) {
            if (v is android.widget.ScrollView) scrollers.add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        val scroller = scrollers.maxByOrNull { it.width } ?: return
        val child = scroller.getChildAt(0) ?: return
        child.measure(
            View.MeasureSpec.makeMeasureSpec(scroller.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        val bitmap = Bitmap.createBitmap(child.measuredWidth, child.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        scroller.background?.let { it.setBounds(0, 0, bitmap.width, bitmap.height); it.draw(canvas) }
        child.draw(canvas)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun capture(view: View, file: File, afterLayout: () -> Unit = {}) {
        val w = view.resources.displayMetrics.widthPixels
        val h = view.resources.displayMetrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        afterLayout()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
