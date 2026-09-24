package io.github.krzkawa.bambuddyaio.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import androidx.fragment.app.Fragment
import io.github.krzkawa.bambuddyaio.R
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.ui.ArchiveFragment
import io.github.krzkawa.bambuddyaio.ui.LibraryFragment
import io.github.krzkawa.bambuddyaio.ui.MainActivity
import io.github.krzkawa.bambuddyaio.ui.PrintSheetFragment
import io.github.krzkawa.bambuddyaio.ui.PrintFlow
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/**
 * The print-from-the-phone screens, drawn the same way [Shots] draws the tabs:
 * the queue with its editing controls, the file library, the "Print on…"
 * sheet with a printer chosen, History, and one old print.
 *
 * Kept apart from [Shots] so the two can change without stepping on each
 * other; the fake server here answers the library and archive routes too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrintShots {

    @Suppress("UNCHECKED_CAST")
    private fun seed(field: String, value: Any?) {
        val f = Repo::class.java.getDeclaredField(field)
        f.isAccessible = true
        (f.get(Repo) as MutableStateFlow<Any?>).value = value
    }

    private fun tray(id: Int, type: String?, colour: String?, remain: Int, idx: String? = null) =
        JSONObject().put("id", id).put("tray_type", type ?: JSONObject.NULL)
            .put("tray_color", colour ?: JSONObject.NULL).put("remain", remain)
            .put("tray_info_idx", idx ?: JSONObject.NULL)

    private fun idle(): JSONObject = JSONObject()
        .put("state", "IDLE").put("connected", true)
        .put("temperatures", JSONObject().put("nozzle", 31.0).put("bed", 27.0))
        .put("ams", JSONArray().put(JSONObject().put("id", 0).put("tray", JSONArray()
            .put(tray(0, "PLA", "F5F5F5FF", 82, "GFA00"))
            .put(tray(1, "PETG", "1E88E5FF", 41))
            .put(tray(2, "PLA", "E53935FF", 7))
            .put(tray(3, null, null, -1)))))

    private fun busy(): JSONObject = JSONObject(idle().toString()).put("state", "RUNNING").put("progress", 40.0)

    private val soon = Instant.now().plusSeconds(5 * 3600).toString()

    private fun routes(path: String): Pair<String, ByteArray> {
        fun json(o: Any) = "application/json" to o.toString().toByteArray()
        return when {
            path.contains("/thumbnail") || path.contains("plate-thumbnail") || path.contains("/photos/") ->
                "image/png" to picture(path.hashCode())
            path.contains("camera/stream-token") -> json(JSONObject().put("token", "t"))
            path.startsWith("/api/v1/queue") -> json(JSONArray()
                .put(JSONObject().put("id", 11).put("status", "printing").put("position", 0)
                    .put("archive_name", "bracket_v3_plate").put("printer_id", 1).put("printer_name", "X1 Carbon"))
                .put(JSONObject().put("id", 12).put("status", "pending").put("position", 1)
                    .put("archive_name", "hinge_left").put("printer_id", 1).put("printer_name", "X1 Carbon")
                    .put("print_time_seconds", 4920).put("filament_used_grams", 38.0))
                .put(JSONObject().put("id", 13).put("status", "pending").put("position", 2)
                    .put("archive_name", "cable_clip").put("printer_id", 1).put("printer_name", "X1 Carbon")
                    .put("batch_id", 4).put("batch_name", "cable_clip ×3").put("print_time_seconds", 900))
                .put(JSONObject().put("id", 14).put("status", "pending").put("position", 3)
                    .put("archive_name", "cable_clip").put("printer_id", 1).put("batch_id", 4))
                .put(JSONObject().put("id", 15).put("status", "pending").put("position", 4)
                    .put("archive_name", "vase_spiral").put("printer_id", 1).put("printer_name", "X1 Carbon")
                    .put("scheduled_time", soon).put("manual_start", false))
                .put(JSONObject().put("id", 16).put("status", "pending").put("position", 1)
                    .put("library_file_name", "desk_organiser").put("printer_id", 2).put("printer_name", "P1S")
                    .put("manual_start", true)))
            path.startsWith("/api/v1/library/folders") -> json(JSONArray()
                .put(JSONObject().put("id", 1).put("name", "Workshop").put("parent_id", JSONObject.NULL)
                    .put("file_count", 12).put("children", JSONArray()
                        .put(JSONObject().put("id", 4).put("name", "Brackets").put("parent_id", 1).put("file_count", 5))))
                .put(JSONObject().put("id", 2).put("name", "Gifts").put("parent_id", JSONObject.NULL).put("file_count", 3))
                .put(JSONObject().put("id", 3).put("name", "Spare parts").put("parent_id", JSONObject.NULL).put("file_count", 7)))
            path.contains("/plates") -> json(JSONObject().put("is_multi_plate", true).put("has_gcode", true).put("plates", JSONArray()
                .put(JSONObject().put("index", 1).put("name", "Hinges").put("has_thumbnail", true)
                    .put("print_time_seconds", 4920).put("filament_used_grams", 38.2))
                .put(JSONObject().put("index", 2).put("name", "Pins").put("has_thumbnail", true)
                    .put("print_time_seconds", 1260).put("filament_used_grams", 9.0))))
            path.contains("/filament-requirements") -> json(JSONObject().put("filaments", JSONArray()
                .put(JSONObject().put("slot_id", 1).put("type", "PLA").put("color", "#FFFFFF").put("used_grams", 31.0).put("tray_info_idx", "GFA00"))
                .put(JSONObject().put("slot_id", 2).put("type", "PETG").put("color", "#2080E0").put("used_grams", 7.2))))
            path.startsWith("/api/v1/library/files") -> json(JSONArray()
                .put(file(21, "hinge_set.gcode.3mf", "Hinge set", 4920, 38.2, "X1C", 3))
                .put(file(22, "wall_hook.gcode.3mf", "Wall hook", 2400, 14.0, "X1C", 0))
                .put(file(23, "phone_stand.gcode.3mf", null, 7300, 61.5, "P1S", 1))
                .put(file(24, "lamp_shade.3mf", null, null, null, null, 0).put("file_type", "3mf"))
                .put(file(25, "gear_box_lid.gcode.3mf", "Gear box lid with a long name", 11000, 90.0, "X1C", 0)))
            path.matches(Regex("/api/v1/archives/\\d+/?")) -> json(archive(31, "Hinge set", "completed")
                .put("photos", JSONArray().put("a1.jpg").put("b2.jpg"))
                .put("layer_height", 0.2).put("total_layers", 184).put("bed_type", "Textured PEI Plate")
                .put("run_count", 3).put("sliced_for_model", "X1C").put("plate_id", 1))
            path.startsWith("/api/v1/archives") -> json(JSONArray()
                .put(archive(31, "Hinge set", "completed"))
                .put(archive(32, "Phone stand", "failed").put("failure_reason", "Spaghetti detected"))
                .put(archive(33, "Wall hook", "completed")))
            else -> json(JSONArray())
        }
    }

    private fun file(id: Int, filename: String, name: String?, secs: Int?, grams: Double?, model: String?, printed: Int) =
        JSONObject().put("id", id).put("filename", filename).put("file_type", "gcode.3mf")
            .put("print_name", name ?: JSONObject.NULL).put("print_time_seconds", secs ?: JSONObject.NULL)
            .put("filament_used_grams", grams ?: JSONObject.NULL).put("sliced_for_model", model ?: JSONObject.NULL)
            .put("print_count", printed).put("thumbnail_path", "t.png").put("created_at", "2026-09-2${id % 10}T10:00:00")

    private fun archive(id: Int, name: String, status: String) =
        JSONObject().put("id", id).put("print_name", name).put("status", status).put("printer_id", 1)
            .put("filament_type", "PLA").put("filament_color", "#F5F5F5").put("filament_used_grams", 38.2)
            .put("actual_time_seconds", 5100).put("completed_at", "2026-09-23T18:40:00Z").put("thumbnail_path", "t.png")

    /** A stand-in plate picture: a soft shape on a transparent ground, like the real ones. */
    private fun picture(seed: Int): ByteArray {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val hues = intArrayOf(0xFFB0BEC5.toInt(), 0xFF90CAF9.toInt(), 0xFFFFCC80.toInt(), 0xFFA5D6A7.toInt())
        paint.color = hues[Math.abs(seed) % hues.size]
        c.drawRoundRect(24f, 40f, 104f, 100f, 14f, 14f, paint)
        paint.color = Color.argb(160, 255, 255, 255)
        c.drawCircle(64f, 44f, 18f, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun fakeServer(): Int {
        val socket = java.net.ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        Thread {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    Thread {
                        client.use {
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty()
                            while (true) {
                                val header = reader.readLine()
                                if (header.isNullOrBlank()) break
                            }
                            val path = request.split(" ").getOrNull(1).orEmpty().substringBefore('?')
                            val (type, bytes) = routes(path)
                            it.getOutputStream().apply {
                                write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    }.apply { isDaemon = true }.start()
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

        seed("_printers", listOf(
            JSONObject().put("id", 1).put("name", "X1 Carbon").put("model", "X1C"),
            JSONObject().put("id", 2).put("name", "P1S").put("model", "P1S")
        ))
        seed("_statuses", mapOf(1 to idle(), 2 to busy()))
        seed("_fetchedAt", mapOf(1 to System.currentTimeMillis(), 2 to System.currentTimeMillis()))
        seed("_updatedAt", System.currentTimeMillis())
        seed("_error", null)
        Repo.select(1)

        val out = File(System.getProperty("shots.dir") ?: "build/shots")
        out.mkdirs()

        fun settle() {
            repeat(4) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(350)
            }
            Repo.stop()
            seed("_error", null)
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun show(fragment: Fragment) {
            activity.supportFragmentManager.beginTransaction()
                .replace(R.id.content_frame, fragment).commitNow()
            settle()
        }

        activity.showTab(6)
        settle()
        capture(activity.window.decorView, File(out, "print-1-queue.png"))

        show(LibraryFragment.of(null))
        capture(activity.window.decorView, File(out, "print-2-library.png"))

        show(LibraryFragment.of(4))
        capture(activity.window.decorView, File(out, "print-3-library-folder.png"))

        val sheet = PrintSheetFragment.forFile(21, "Hinge set", PrintFlow.toLibrary(null), "X1C")
        show(sheet)
        capture(activity.window.decorView, File(out, "print-4-sheet-no-printer.png"))

        // Choose a printer the way a tap on the strip would.
        val field = PrintSheetFragment::class.java.getDeclaredField("printerId")
        field.isAccessible = true
        field.set(sheet, 1)
        val render = PrintSheetFragment::class.java.getDeclaredMethod("render")
        render.isAccessible = true
        render.invoke(sheet)
        settle()
        capture(activity.window.decorView, File(out, "print-5-sheet.png"))
        scrolled(activity.window.decorView, File(out, "print-6-sheet-bottom.png"))

        field.set(sheet, 2)
        render.invoke(sheet)
        settle()
        capture(activity.window.decorView, File(out, "print-7-sheet-busy-other-model.png"))

        activity.showTab(7)
        settle()
        capture(activity.window.decorView, File(out, "print-8-history.png"))

        show(ArchiveFragment.of(31))
        capture(activity.window.decorView, File(out, "print-9-archive.png"))
    }

    /** The same screen scrolled to its end, for a sheet taller than the phone. */
    private fun scrolled(root: View, file: File) {
        val frame = root.findViewById<android.view.ViewGroup>(R.id.content_frame)
        val scroller = findScroll(frame)
        scroller?.scrollTo(0, 10_000)
        capture(root, file)
        scroller?.scrollTo(0, 0)
    }

    private fun findScroll(v: View?): android.widget.ScrollView? {
        if (v is android.widget.ScrollView) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        }
        return null
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
