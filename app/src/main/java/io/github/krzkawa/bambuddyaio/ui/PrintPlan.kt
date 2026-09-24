package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything that decides what a print from the phone will actually do, kept
 * free of views so it can be tested: which files can be printed at all, which
 * AMS tray feeds which filament, and the queue item that gets sent.
 *
 * The tray matching follows Bambuddy's own print dialog
 * (`frontend/src/hooks/useFilamentMapping.ts`) step for step, because the
 * phone must not promise a spool the web page would not have picked.
 */
object PrintPlan {

    // ---------------------------------------------------------------- files

    /**
     * Whether a library file holds G-code a printer can run.
     *
     * A plain `.3mf` is a project that still needs slicing, and an STL is a
     * shape; queueing either one fails at upload. The server calls a sliced
     * 3MF `gcode.3mf`, and the name is checked too because an external folder
     * scan can leave the type as plain `3mf`.
     */
    fun isSliced(file: JSONObject): Boolean {
        val type = file.str("file_type")?.lowercase()
        if (type == "gcode" || type == "gcode.3mf") return true
        val name = file.str("filename")?.lowercase().orEmpty()
        return name.endsWith(".gcode") || name.endsWith(".gcode.3mf")
    }

    /** What to call a file: the name the slicer gave it, else the file name without its extension. */
    fun fileName(file: JSONObject): String =
        file.str("print_name") ?: stripExtension(file.str("filename") ?: "File")

    fun stripExtension(name: String): String =
        name.removeSuffix(".gcode.3mf").removeSuffix(".3mf").removeSuffix(".gcode")

    /** One folder of the tree the server sends, flattened with its depth. */
    data class Folder(val id: Int, val name: String, val parentId: Int?, val fileCount: Int)

    fun folders(tree: JSONArray?): List<Folder> {
        val out = ArrayList<Folder>()
        fun walk(nodes: List<JSONObject>) {
            for (node in nodes) {
                val id = node.int("id") ?: continue
                out.add(Folder(id, node.str("name") ?: "Folder", node.int("parent_id"), node.optInt("file_count")))
                walk(node.objects("children"))
            }
        }
        walk(tree.objects())
        return out
    }

    /** The folders to show inside [parentId] — the top level when it is null. */
    fun childrenOf(all: List<Folder>, parentId: Int?): List<Folder> =
        all.filter { it.parentId == parentId }.sortedBy { it.name.lowercase() }

    /** The chain from the top level down to [folderId], for the path line. */
    fun pathTo(all: List<Folder>, folderId: Int?): List<Folder> {
        val byId = all.associateBy { it.id }
        val chain = ArrayList<Folder>()
        var cursor = folderId?.let { byId[it] }
        // A loop in the tree would be a server bug, but it must not hang the phone.
        while (cursor != null && chain.size < 32) {
            chain.add(0, cursor)
            cursor = cursor.parentId?.let { byId[it] }
        }
        return chain
    }

    /** Printable files first, then newest first: the file he just sliced is the one he wants. */
    fun sortFiles(files: List<JSONObject>): List<JSONObject> =
        files.sortedWith(
            compareBy<JSONObject> { if (isSliced(it)) 0 else 1 }
                .thenByDescending { it.str("fs_modified_at") ?: it.str("created_at") ?: "" }
        )

    fun matches(file: JSONObject, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = listOfNotNull(file.str("print_name"), file.str("filename"), file.str("sliced_for_model"))
            .joinToString(" ").lowercase()
        return words.all { hay.contains(it) }
    }

    // ---------------------------------------------------------------- plates

    data class Plate(
        val index: Int,
        val name: String?,
        val seconds: Int?,
        val grams: Double?,
        val hasThumbnail: Boolean
    ) {
        val label: String get() = name?.let { "Plate $index · $it" } ?: "Plate $index"
    }

    fun plates(response: JSONObject?): List<Plate> =
        response?.objects("plates").orEmpty().mapNotNull { p ->
            val index = p.int("index") ?: return@mapNotNull null
            Plate(
                index = index,
                name = p.str("name"),
                seconds = p.dbl("print_time_seconds")?.toInt(),
                grams = p.dbl("filament_used_grams"),
                hasThumbnail = p.optBoolean("has_thumbnail")
            )
        }.sortedBy { it.index }

    // ------------------------------------------------------------- filaments

    /** One filament the plate uses, numbered the way the 3MF numbers it. */
    data class Need(
        val slotId: Int,
        val type: String,
        val color: String?,
        val grams: Double,
        val trayInfoIdx: String?
    )

    fun needs(response: JSONObject?): List<Need> =
        response?.objects("filaments").orEmpty().mapNotNull { f ->
            val slot = f.int("slot_id") ?: return@mapNotNull null
            if (slot <= 0) return@mapNotNull null
            Need(
                slotId = slot,
                type = f.str("type") ?: "",
                color = f.str("color"),
                grams = f.dbl("used_grams") ?: 0.0,
                trayInfoIdx = f.str("tray_info_idx")
            )
        }.sortedBy { it.slotId }

    /** A spool the printer has loaded right now. */
    data class Tray(
        val globalId: Int,
        val label: String,
        val type: String,
        val color: String?,
        val trayInfoIdx: String?,
        val remain: Int
    )

    /**
     * The trays a status reports as holding filament.
     *
     * A tray with no type is skipped as the web page skips it: the printer
     * cannot tell anyone what is in it, so it cannot be matched to anything.
     */
    fun trays(status: JSONObject?): List<Tray> {
        if (status == null) return emptyList()
        val out = ArrayList<Tray>()
        for (unit in status.objects("ams")) {
            val amsId = unit.optInt("id")
            for (tray in unit.objects("tray")) {
                val type = tray.str("tray_type") ?: continue
                val trayId = tray.optInt("id")
                val slot = Assign.Slot(amsId, trayId, Assign.slotName(amsId, trayId), null)
                out.add(Tray(slot.globalTrayId, slot.label, type, tray.str("tray_color"),
                    tray.str("tray_info_idx"), tray.optInt("remain", -1)))
            }
        }
        val externals = status.objects("vt_tray")
        for (vt in externals) {
            val type = vt.str("tray_type") ?: continue
            val id = vt.optInt("id", 254)
            val label = when {
                externals.size < 2 -> "External spool"
                id == 254 -> "External left"
                else -> "External right"
            }
            out.add(Tray(id, label, type, vt.str("tray_color"), vt.str("tray_info_idx"), vt.optInt("remain", -1)))
        }
        return out
    }

    /** How well the tray chosen for a filament fits it. */
    enum class Fit { MATCH, TYPE_ONLY, NONE }

    data class Pick(val need: Need, val tray: Tray?, val fit: Fit, val manual: Boolean)

    /**
     * Chooses a tray for each filament, the way Bambuddy's print dialog does.
     *
     * In order: a tray whose `tray_info_idx` is the only one of its kind, then
     * an exact colour of a compatible type, then the nearest similar colour,
     * then any tray of the type. A tray is used once. [manual] holds his own
     * choices, slot id to global tray id, and those are honoured first.
     */
    fun match(needs: List<Need>, trays: List<Tray>, manual: Map<Int, Int> = emptyMap()): List<Pick> {
        val used = HashSet<Int>(manual.values)
        return needs.map { need ->
            manual[need.slotId]?.let { chosen ->
                val tray = trays.firstOrNull { it.globalId == chosen }
                if (tray != null) return@map Pick(need, tray, fitOf(need, tray), manual = true)
            }
            val free = trays.filter { it.globalId !in used }
            val tray = autoPick(need, free)
            if (tray != null) used.add(tray.globalId)
            Pick(need, tray, if (tray == null) Fit.NONE else fitOf(need, tray), manual = false)
        }
    }

    private fun autoPick(need: Need, free: List<Tray>): Tray? {
        val idx = need.trayInfoIdx.orEmpty()
        if (idx.isNotEmpty()) {
            val sameIdx = free.filter { it.trayInfoIdx == idx }
            if (sameIdx.size == 1) return sameIdx[0]
            if (sameIdx.size > 1) best(need, sameIdx)?.let { return it }
        }
        return best(need, free)
    }

    private fun best(need: Need, candidates: List<Tray>): Tray? {
        val typed = candidates.filter { sameType(it.type, need.type) }
        typed.firstOrNull { hex(it.color) != null && hex(it.color) == hex(need.color) }?.let { return it }
        typed.filter { similar(it.color, need.color) }
            .minByOrNull { distance(it.color, need.color) }
            ?.let { return it }
        return typed.firstOrNull()
    }

    private fun fitOf(need: Need, tray: Tray): Fit = when {
        !sameType(tray.type, need.type) -> Fit.NONE
        colourFits(tray.color, need.color) -> Fit.MATCH
        else -> Fit.TYPE_ONLY
    }

    /** Types the firmware treats as one; the same group Bambuddy keeps. */
    private val TYPE_GROUPS = listOf(setOf("PA-CF", "PA12-CF", "PAHT-CF"))

    fun sameType(a: String?, b: String?): Boolean {
        fun canon(t: String?): String {
            val up = t?.trim()?.uppercase().orEmpty()
            return TYPE_GROUPS.firstOrNull { up in it }?.first() ?: up
        }
        return canon(a) == canon(b)
    }

    /** A filament with no colour asked for is happy with any colour. */
    fun colourFits(loaded: String?, required: String?): Boolean {
        val want = hex(required) ?: return true
        return hex(loaded) == want || similar(loaded, required)
    }

    /** RRGGBB in lower case, from `#RRGGBB`, `RRGGBBAA` or anything in between. */
    fun hex(colour: String?): String? {
        val s = colour?.trim()?.removePrefix("#")?.lowercase() ?: return null
        if (s.length < 6 || !s.substring(0, 6).all { it in "0123456789abcdef" }) return null
        return s.substring(0, 6)
    }

    private fun rgb(colour: String?): IntArray? {
        val h = hex(colour) ?: return null
        return intArrayOf(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
    }

    /** Bambuddy's own test: every channel within 40 of the other. */
    fun similar(a: String?, b: String?): Boolean {
        val x = rgb(a) ?: return false
        val y = rgb(b) ?: return false
        return (0..2).all { Math.abs(x[it] - y[it]) <= 40 }
    }

    private fun distance(a: String?, b: String?): Double {
        val x = rgb(a) ?: return Double.MAX_VALUE
        val y = rgb(b) ?: return Double.MAX_VALUE
        return Math.sqrt((0..2).sumOf { ((x[it] - y[it]) * (x[it] - y[it])).toDouble() })
    }

    /**
     * The array the print command carries: position is the 3MF's slot id less
     * one, the value is a global tray id, and -1 means nothing feeds it. Null
     * when there is nothing to say, which lets the server work it out itself.
     */
    fun amsMapping(picks: List<Pick>): List<Int>? {
        val top = picks.maxOfOrNull { it.need.slotId } ?: return null
        if (top <= 0) return null
        val mapping = MutableList(top) { -1 }
        for (p in picks) mapping[p.need.slotId - 1] = p.tray?.globalId ?: -1
        return mapping
    }

    // ------------------------------------------------------------- printers

    /** What a printer is up to, as far as sending it a print is concerned. */
    enum class Readiness { READY, BUSY, PLATE, OFFLINE, UNKNOWN }

    fun readiness(status: JSONObject?): Readiness {
        if (status == null) return Readiness.UNKNOWN
        if (!status.optBoolean("connected", true)) return Readiness.OFFLINE
        if (status.optBoolean("awaiting_plate_clear")) return Readiness.PLATE
        return when (status.str("state")) {
            "RUNNING", "PAUSE", "PREPARE", "SLICING" -> Readiness.BUSY
            else -> Readiness.READY
        }
    }

    /**
     * Whether a file sliced for one model can go to this printer.
     *
     * G-code carries the machine's own motion limits and bed size, so the
     * server refuses a mismatch when it picks the printer itself. When the
     * phone names the printer nothing checks, which is why the sheet says so.
     * Unknown on either side is not a mismatch.
     */
    fun modelMismatch(slicedFor: String?, printerModel: String?): Boolean {
        val a = normaliseModel(slicedFor) ?: return false
        val b = normaliseModel(printerModel) ?: return false
        return a != b
    }

    fun normaliseModel(model: String?): String? {
        val m = model?.trim()?.uppercase()?.removePrefix("BAMBU LAB")?.trim()?.replace(" ", "")
        return m?.ifEmpty { null }
    }

    // ------------------------------------------------------------ the item

    /** When the print should go. */
    enum class When { NOW, HOLD, LATER }

    /** The three bed levelling settings the printer takes. */
    val TRI_STATES = listOf("auto", "on", "off")

    data class Request(
        val printerId: Int,
        val libraryFileId: Int? = null,
        val archiveId: Int? = null,
        val plateId: Int? = null,
        val mapping: List<Int>? = null,
        val bedLevelling: String = "auto",
        val flowCali: String = "auto",
        val timelapse: Boolean = false,
        val whenToPrint: When = When.NOW,
        /** UTC, and only read when [whenToPrint] is LATER. */
        val at: Instant? = null,
        val skipFilamentCheck: Boolean = false
    )

    /**
     * The body for `POST /queue/`.
     *
     * Every print from the phone is created staged (`manual_start`), even one
     * meant to go now. Starting it is then a separate `/start`, which is the
     * call that re-checks the spools and answers with a shortfall he can
     * overrule — a straight ASAP item would be picked up by the scheduler with
     * nobody there to ask. A print for later is the exception: the scheduler
     * is exactly who should start it.
     */
    fun payload(r: Request): JSONObject {
        val p = JSONObject().put("printer_id", r.printerId)
        r.libraryFileId?.let { p.put("library_file_id", it) }
        r.archiveId?.let { p.put("archive_id", it) }
        r.plateId?.let { p.put("plate_id", it) }
        r.mapping?.let { m -> p.put("ams_mapping", JSONArray().also { a -> m.forEach { a.put(it) } }) }
        p.put("bed_levelling", r.bedLevelling)
        p.put("flow_cali", r.flowCali)
        p.put("timelapse", r.timelapse)
        p.put("manual_start", r.whenToPrint != When.LATER)
        // "Now" means ahead of whatever else is already lined up for it.
        if (r.whenToPrint == When.NOW) p.put("insert_at_top", true)
        if (r.whenToPrint == When.LATER && r.at != null) p.put("scheduled_time", r.at.toString())
        if (r.skipFilamentCheck) p.put("skip_filament_check", true)
        return p
    }

    // ---------------------------------------------------------------- times

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

    /**
     * The next time the clock reads [hour]:[minute], today if that is still
     * ahead and tomorrow if it is not — what anyone means by "at seven".
     */
    fun nextAt(hour: Int, minute: Int, now: LocalDateTime, zone: ZoneId): Instant {
        var at = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at.atZone(zone).toInstant()
    }

    /** "today 19:00", "tomorrow 07:30", or a date further out. */
    fun sayWhen(at: Instant, now: LocalDateTime, zone: ZoneId): String {
        val local = LocalDateTime.ofInstant(at, zone)
        val day: LocalDate = local.toLocalDate()
        val today = now.toLocalDate()
        val clock = local.format(CLOCK)
        return when (day) {
            today -> "today $clock"
            today.plusDays(1) -> "tomorrow $clock"
            else -> "${local.format(DAY)} $clock"
        }
    }

    /** Reads the server's timestamps, which arrive with or without a zone. */
    fun parseInstant(value: String?): Instant? {
        val s = value?.trim()?.ifEmpty { null } ?: return null
        return try {
            Instant.parse(s)
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(s).toInstant()
            } catch (e2: Exception) {
                try {
                    LocalDateTime.parse(s).atZone(java.time.ZoneOffset.UTC).toInstant()
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }
}
