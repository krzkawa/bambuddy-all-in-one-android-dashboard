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
        fun spool(id: Int, brand: String, material: String, sub: String?, colour: String, rgba: String,
                  left: Int, location: String? = null) = JSONObject()
            .put("id", id).put("brand", brand).put("material", material).put("subtype", sub)
            .put("color_name", colour).put("rgba", rgba).put("label_weight", 1000)
            .put("weight_used", 1000 - left).put("storage_location", location)
        val spools = JSONArray()
            .put(spool(1, "Bambu", "PLA", "Basic", "Jade White", "F5F5F5FF", 820, "Drybox 1"))
            .put(spool(2, "Polymaker", "PETG", null, "Blue", "1E88E5FF", 410, "Drybox 1"))
            .put(spool(3, "Bambu", "PLA", "Basic", "Red", "E53935FF", 70, "Shelf"))
            .put(spool(4, "eSun", "PLA+", null, "Black", "212121FF", 150))
            .put(spool(5, "Bambu", "ABS", null, "Orange", "FB8C00FF", 960, "Drybox 2"))
        val shopping = JSONArray()
            .put(JSONObject().put("id", 1).put("material", "PLA").put("subtype", "Basic").put("brand", "Bambu")
                .put("color_name", "Red").put("quantity_spools", 2).put("status", "pending")
                .put("added_at", "2026-09-22T08:00:00"))
            .put(JSONObject().put("id", 2).put("material", "PETG").put("brand", "Polymaker")
                .put("color_name", "Blue").put("quantity_spools", 1).put("status", "purchased")
                .put("added_at", "2026-09-18T08:00:00"))
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
                            request.contains("shopping-list") -> shopping.toString()
                            request.contains("inventory/spools") -> spools.toString()
                            request.contains("settings") -> JSONObject().put("low_stock_threshold", 20).toString()
                            request.contains("ams-history") -> amsHistory().toString()
                            request.contains("printer-sensor-history") -> heaterHistory().toString()
                            request.contains("archives/stats") -> stats().toString()
                            request.contains("archives/slim") -> runs().toString()
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

    // ------------------------------------------------ history, for the charts

    /** The server's own timestamp shape: UTC, with no zone written on it. */
    private fun at(minutesAgo: Int): String =
        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(minutesAgo.toLong()).toString()

    /**
     * A day of an AMS drying out and then creeping back up: dry overnight,
     * damp by the afternoon, so all three colours of the line show.
     */
    private fun amsHistory(): JSONObject {
        val data = JSONArray()
        for (i in 288 downTo 0) {
            val minutes = i * 5
            // A gap of an hour, as when the server was restarted.
            if (minutes in 600..660) continue
            val t = (288 - i) / 288.0
            val humidity = when {
                t < 0.2 -> 52 - t * 100
                t < 0.35 -> 32.0
                else -> 32 + (t - 0.35) * 55
            } + Math.sin(i / 6.0) * 1.5
            data.put(JSONObject().put("recorded_at", at(minutes))
                .put("humidity", humidity).put("humidity_raw", humidity)
                .put("temperature", 24 + Math.sin(i / 40.0) * 3))
        }
        return JSONObject().put("printer_id", 1).put("ams_id", 0).put("data", data)
            .put("min_humidity", 22).put("max_humidity", 67)
    }

    /** Nozzle, bed and chamber through a print that started about three hours ago. */
    private fun heaterHistory(): JSONObject {
        fun series(kind: String, value: (Int) -> Double, target: (Int) -> Double): JSONObject {
            val data = JSONArray()
            for (m in 240 downTo 0) {
                data.put(JSONObject().put("recorded_at", at(m)).put("value", value(m)).put("target", target(m)))
            }
            return JSONObject().put("sensor_kind", kind).put("data", data)
        }
        val start = 175
        fun printing(m: Int) = m <= start
        val nozzle = series("nozzle",
            { m -> if (!printing(m)) 38.0 else if (m > start - 4) 38 + (start - m) * 45.0 else 219 + Math.sin(m / 3.0) },
            { m -> if (printing(m)) 220.0 else 0.0 })
        val bed = series("bed",
            { m -> if (!printing(m)) 27.0 else minOf(60.0, 27 + (start - m) * 6.0) - if (m in 60..70) 6 else 0 },
            { m -> if (printing(m)) 60.0 else 0.0 })
        val chamber = series("chamber",
            { m -> if (!printing(m)) 26.0 else minOf(36.0, 26 + (start - m) * 0.2) },
            { _ -> 0.0 })
        return JSONObject().put("printer_id", 1).put("series", JSONArray().put(bed).put(chamber).put(nozzle))
    }

    private fun stats(): JSONObject = JSONObject()
        .put("total_prints", 58).put("successful_prints", 49).put("failed_prints", 5)
        .put("cancelled_prints", 4).put("total_print_time_hours", 212.4)
        .put("total_filament_grams", 4630.0).put("total_cost", 96.4).put("total_energy_kwh", 31.2)
        .put("prints_by_filament_type", JSONObject().put("PLA", 38).put("PETG", 14).put("ABS", 6))
        .put("prints_by_printer", JSONObject().put("1", 41).put("2", 17))

    /** A month of runs, most finished, a few not. */
    private fun runs(): JSONArray {
        val out = JSONArray()
        val materials = listOf("PLA", "PLA", "PETG", "PLA", "ABS", "PETG", "PLA")
        var n = 0
        for (day in 0 until 30) {
            val prints = listOf(2, 0, 3, 1, 4, 2, 0, 1, 3, 2)[day % 10]
            for (p in 0 until prints) {
                n++
                val status = when {
                    n % 11 == 0 -> "failed"
                    n % 13 == 0 -> "cancelled"
                    else -> "completed"
                }
                out.put(JSONObject().put("created_at", at(day * 1440 + p * 180 + 60))
                    .put("status", status).put("filament_type", materials[n % materials.size])
                    .put("filament_used_grams", 30.0 + (n * 37) % 160).put("printer_id", 1 + n % 2))
            }
        }
        return out
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
            if (name == "ams" || name == "stats") {
                // The humidity chart sits under the slots, below the fold, and
                // Stats runs to a second screen.
                scrollToEnd(activity.window.decorView)
                capture(activity.window.decorView, File(out, "$name-scrolled.png"))
            }
        }

        // The heater history, as it opens over Control.
        activity.showTab(1)
        shadowOf(Looper.getMainLooper()).idle()
        val control = activity.supportFragmentManager.fragments
            .filterIsInstance<io.github.krzkawa.bambuddyaio.ui.BaseFragment>().first { it.isVisible }
        io.github.krzkawa.bambuddyaio.ui.HeaterHistory.show(control, 1)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(400)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        captureDialog(activity.window.decorView, dialog.window!!.decorView, File(out, "heater-history.png"))
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

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
        shootSpools(activity, out)
    }


    /** The Spools screen in each of its views, and the sticker sheet over it. */
    private fun shootSpools(activity: MainActivity, out: File) {
        activity.showTab(5)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(600)
        shadowOf(Looper.getMainLooper()).idle()
        val fragment = activity.supportFragmentManager.fragments
            .first { it is io.github.krzkawa.bambuddyaio.ui.SpoolsFragment }
        val viewField = fragment.javaClass.getDeclaredField("view").apply { isAccessible = true }
        val render = fragment.javaClass.getDeclaredMethod("render").apply { isAccessible = true }
        listOf(0 to "spools", 1 to "spools-low", 2 to "spools-to-buy").forEach { (mode, name) ->
            viewField.setInt(fragment, mode)
            render.invoke(fragment)
            shadowOf(Looper.getMainLooper()).idle()
            capture(activity.window.decorView, File(out, "$name.png"))
        }

        val spool = JSONObject().put("id", 4).put("brand", "eSun").put("material", "PLA+")
            .put("color_name", "Black").put("rgba", "212121FF")
        io.github.krzkawa.bambuddyaio.ui.StickerWrite.show(activity, fragment.viewLifecycleOwner, spool)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        capture(dialog.window!!.decorView, File(out, "sticker-sheet.png"))
        io.github.krzkawa.bambuddyaio.ui.StickerWrite.arm(
            io.github.krzkawa.bambuddyaio.ui.StickerWrite.Request(4, "eSun · PLA+ · Black", "{}")
        )
        shadowOf(Looper.getMainLooper()).idle()
        capture(dialog.window!!.decorView, File(out, "sticker-waiting.png"))
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun scrollToEnd(view: View) {
        if (view is android.widget.ScrollView) {
            view.scrollTo(0, view.getChildAt(0).height)
            return
        }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) scrollToEnd(view.getChildAt(i))
    }

    /** The screen behind, dimmed the way Android dims it, with the dialog centred on top. */
    private fun captureDialog(screen: View, dialog: View, file: File) {
        val w = screen.resources.displayMetrics.widthPixels
        val h = screen.resources.displayMetrics.heightPixels
        screen.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        screen.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        screen.draw(canvas)
        canvas.drawColor(0x99000000.toInt())
        val dw = (w * 0.9f).toInt()
        dialog.measure(
            View.MeasureSpec.makeMeasureSpec(dw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
        )
        dialog.layout(0, 0, dw, dialog.measuredHeight)
        canvas.save()
        canvas.translate((w - dw) / 2f, (h - dialog.measuredHeight) / 2f)
        dialog.draw(canvas)
        canvas.restore()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
