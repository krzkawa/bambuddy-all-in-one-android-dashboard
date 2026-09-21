package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.bool
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/** Every AMS unit on the selected printer: what is loaded, how damp it is, drying. */
class AmsFragment : BaseFragment() {

    private lateinit var picker: LinearLayout
    private lateinit var body: LinearLayout
    private var signature = ""

    override fun build(ctx: Context) {
        picker = Ui.col(ctx)
        content.addView(picker, Ui.wide(ctx))
        body = Ui.col(ctx)
        content.addView(body, Ui.wide(ctx))

        observe(Repo.statuses) { render() }
        observe(Repo.selected) { signature = ""; render() }
        render()
    }

    private fun render() {
        val ctx = context ?: return
        buildPrinterPicker(ctx, picker) { signature = ""; render() }

        val id = Repo.selected.value
        val status = Repo.statuses.value[id]
        val units = status?.objects("ams").orEmpty()
        val external = status?.objects("vt_tray").orEmpty()

        val next = buildString {
            append(id)
            append(status?.int("tray_now"))
            append(status?.bool("supports_drying")).append(status?.str("state"))
            for (u in units) {
                append(u.optInt("id")).append(u.int("humidity")).append(dryStatusOf(status, u))
                    .append(dryMinutesOf(status, u))
                for (t in u.objects("tray")) {
                    append(t.str("tray_type")).append(t.str("tray_color")).append(t.optInt("remain"))
                        .append(t.int("state")).append(t.bool("exists"))
                }
            }
            for (t in external) append(t.str("tray_type")).append(t.optInt("remain"))
        }
        if (next == signature && body.childCount > 0) return
        signature = next

        body.removeAllViews()

        if (status == null) {
            body.addView(empty(ctx, "The server cannot reach this printer."))
            return
        }
        if (units.isEmpty() && external.isEmpty()) {
            body.addView(empty(ctx, "This printer is not reporting an AMS."))
            return
        }

        val loadedTray = status.int("tray_now") ?: 255
        for (unit in units) {
            body.addView(unitCard(ctx, id, status, unit, loadedTray))
            body.addView(Ui.space(ctx, Ui.M))
        }
        if (external.isNotEmpty()) {
            body.addView(externalCard(ctx, id, external, loadedTray))
        }
    }

    private fun unitCard(
        ctx: Context,
        printerId: Int,
        status: JSONObject,
        unit: JSONObject,
        loadedTray: Int
    ): LinearLayout {
        val card = Ui.card(ctx)
        val amsId = unit.optInt("id")

        val top = Ui.row(ctx)
        top.addView(Ui.title(ctx, Assign.unitName(amsId)))
        Ui.push(ctx, top)
        unit.dbl("temp")?.let {
            top.addView(Ui.dim(ctx, "${it.toInt()}°C"))
            Ui.gap(ctx, top, Ui.M)
        }
        unit.int("humidity")?.let {
            top.addView(Ui.dot(ctx, humidityColor(ctx, it)))
            Ui.gap(ctx, top, 6)
            val chip = Ui.dim(ctx, "${humidityWord(it)} · $it%")
            chip.setTextColor(humidityColor(ctx, it))
            top.addView(chip)
        }
        card.addView(top, Ui.wide(ctx))

        val dryStatus = dryStatusOf(status, unit)
        val dryMinutes = dryMinutesOf(status, unit)
        // dry_status is the truth: a cycle can be checking, cooling or in error
        // with no minutes left to count, and an error counting down to nothing
        // is the one case where he most needs to be told.
        val running = (dryStatus != null && dryStatus in DRY_CHECKING..DRY_STOPPING) || dryMinutes > 0
        if (running || dryStatus == DRY_ERROR) {
            val bits = ArrayList<String>()
            dryStatusWord(dryStatus)?.let { bits.add(it) }
            if (dryMinutes > 0) bits.add("${Ui.minutes(dryMinutes)} remaining")
            firstInt(status, unit, "dry_target_temp")?.takeIf { it > 0 }?.let { bits.add("${it}°C") }
            firstStr(status, unit, "dry_filament")?.let { bits.add(it) }
            val line = Ui.body(ctx, bits.joinToString(" · ").ifBlank { "Drying" })
            line.setTextColor(if (dryStatus == DRY_ERROR) Ui.bad(ctx) else Ui.dimColor(ctx))
            card.addView(line)
        }

        card.addView(Ui.space(ctx, Ui.M))
        for (tray in unit.objects("tray")) {
            card.addView(slotRow(ctx, printerId, amsId, tray, loadedTray), Ui.wide(ctx))
            card.addView(Ui.space(ctx, 6))
        }

        card.addView(Ui.space(ctx, 2))
        card.addView(dryingControls(ctx, printerId, status, amsId, running), Ui.wide(ctx))
        return card
    }

    /**
     * The drying row, or the reason there is not one.
     *
     * The server states what this printer can actually do — a P1 can only start
     * a cycle from its own screen, and some cannot dry while printing — so a
     * button that was always going to fail is replaced by the reason.
     */
    private fun dryingControls(
        ctx: Context,
        printerId: Int,
        status: JSONObject,
        amsId: Int,
        running: Boolean
    ): LinearLayout {
        val row = Ui.row(ctx)
        if (running) {
            row.addView(Ui.button(ctx, "Stop drying") {
                command("Stop drying") { Repo.api.stopDrying(printerId, amsId) }
            })
            return row
        }

        // An older server that says nothing gets the benefit of the doubt.
        val supported = if (status.has("supports_drying")) status.bool("supports_drying") else true
        val screenOnly = status.bool("drying_screen_only")
        val whilePrinting =
            if (status.has("supports_drying_while_printing")) status.bool("supports_drying_while_printing") else true
        val printing = status.str("state") == "RUNNING"
        val reason = status.str("dry_sf_reason")

        val blocked = when {
            !supported -> reason ?: "This printer cannot be told to dry from here."
            screenOnly -> reason ?: "Start drying from the printer's own screen."
            printing && !whilePrinting -> reason ?: "This printer cannot dry while it is printing."
            else -> null
        }
        if (blocked != null) {
            row.addView(Ui.dim(ctx, blocked))
        } else {
            row.addView(Ui.button(ctx, "Dry") { askDrying(ctx, printerId, amsId) })
        }
        return row
    }

    private fun externalCard(
        ctx: Context,
        printerId: Int,
        trays: List<JSONObject>,
        loadedTray: Int
    ): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.title(ctx, "External spool"))
        card.addView(Ui.space(ctx, Ui.M))
        for (tray in trays) {
            val trayId = (tray.optInt("id", 254) - 254).coerceIn(0, 1)
            card.addView(
                slotRow(ctx, printerId, 255, tray, loadedTray, forcedTrayId = trayId),
                Ui.wide(ctx)
            )
            card.addView(Ui.space(ctx, 6))
        }
        return card
    }

    private fun slotRow(
        ctx: Context,
        printerId: Int,
        amsId: Int,
        tray: JSONObject,
        loadedTray: Int,
        forcedTrayId: Int? = null
    ): LinearLayout {
        val trayId = forcedTrayId ?: tray.optInt("id")
        val slot = Assign.Slot(amsId, trayId, Assign.slotName(amsId, trayId), null)
        val isLoaded = loadedTray == slot.globalTrayId || tray.int("state") == TRAY_LOADED

        val row = Ui.inset(ctx)
        if (isLoaded) {
            // The loaded slot is the one fact this screen exists to show, so it
            // is the only row that gets an outline.
            row.background = Ui.rounded(Ui.insetColor(ctx), 10, ctx, Ui.accent(ctx))
            val p = Ui.dp(ctx, 10)
            row.setPadding(p, Ui.dp(ctx, Ui.S), p, Ui.dp(ctx, Ui.S))
        }

        row.addView(Ui.swatch(ctx, tray.str("tray_color"), 24))
        Ui.gap(ctx, row, 10)

        val info = Ui.col(ctx)
        // The firmware's own "a spool is physically here" bit. A slot with no
        // type name is not necessarily empty: a spool without an RFID tag has
        // no name to report, and calling that "Empty" sends him to the wrong AMS.
        val trayState = tray.int("state")
        val present = when {
            trayState != null -> trayState >= TRAY_PRESENT
            tray.has("exists") -> tray.bool("exists")
            else -> tray.str("tray_type") != null
        }
        val name = tray.str("tray_sub_brands")
            ?: tray.str("tray_type")
            ?: if (present) "Untagged spool" else "Empty"
        val heading = Ui.row(ctx)
        if (amsId != 255) {
            val number = Ui.tiny(ctx, "${trayId + 1}")
            number.setTextColor(Ui.faintColor(ctx))
            heading.addView(number, Ui.lp(ctx, 12, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val title = Ui.body(ctx, name)
        if (!present) title.setTextColor(Ui.faintColor(ctx))
        heading.addView(title)
        info.addView(heading, Ui.wide(ctx))

        val bits = ArrayList<String>()
        val remain = tray.optInt("remain", -1).takeIf { it in 0..100 }
        remain?.let { bits.add("$it%") }
        tray.dbl("k")?.takeIf { it > 0 }?.let { bits.add("k ${String.format("%.3f", it)}") }
        when {
            isLoaded || trayState == TRAY_LOADED -> bits.add("loaded")
            trayState == TRAY_PRESENT -> bits.add("in the slot")
            trayState == TRAY_EMPTY -> bits.add("empty")
        }
        if (tray.str("tray_uuid") != null) bits.add("RFID")
        val detail = Ui.tiny(ctx, bits.joinToString(" · ").ifBlank { "Nothing reported" })
        // A spool about to run out is the one thing on this row worth colouring.
        if (remain != null && remain <= LOW_FILAMENT) detail.setTextColor(Ui.warn(ctx))
        detail.setPadding(if (amsId == 255) 0 else Ui.dp(ctx, 12), 0, 0, 0)
        info.addView(detail, Ui.wide(ctx))
        row.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (isLoaded) {
            row.addView(Ui.quiet(ctx, "Unload") {
                command("Unload") { Repo.api.amsUnload(printerId, slot.globalTrayId) }
            })
        } else {
            row.addView(Ui.quiet(ctx, "Load") {
                command("Load") { Repo.api.amsLoad(printerId, slot.globalTrayId) }
            })
        }
        if (amsId != 255) {
            row.addView(Ui.quiet(ctx, "Re-read") {
                command("RFID re-read") { Repo.api.refreshSlotRfid(printerId, amsId, trayId) }
            })
        }
        Ui.gap(ctx, row, Ui.XS)
        row.addView(Ui.button(ctx, "Assign") { chooseSpoolFor(printerId, slot) })
        return row
    }

    /** Slot-first assignment: pick the slot on screen, then choose the spool. */
    private fun chooseSpoolFor(printerId: Int, slot: Assign.Slot) {
        pickSpool("Assign to ${slot.label}") { spool ->
            Assign.send(spool.optInt("id"), printerId, slot) { toast(it) }
        }
    }

    private fun dryStatusWord(dryStatus: Int?): String? = when (dryStatus) {
        1 -> "Checking"
        2 -> "Drying"
        3 -> "Cooling"
        4 -> "Stopping"
        DRY_ERROR -> "Drying error"
        else -> null
    }

    private fun askDrying(ctx: Context, printerId: Int, amsId: Int) {
        val options = arrayOf("45° for 6h (PLA, PETG)", "55° for 8h (ABS, ASA)", "65° for 8h (PA, PC)")
        val settings = listOf(45 to 6, 55 to 8, 65 to 8)
        AlertDialog.Builder(ctx)
            .setTitle("Dry ${Assign.unitName(amsId)}")
            .setItems(options) { _, which ->
                val (temp, hours) = settings[which]
                command("Drying") { Repo.api.startDrying(printerId, amsId, temp, hours) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Bambuddy's own web UI calls 40 and below good and 60 and below fair, and
     * both thresholds are configurable on the server. Anything stricter paints
     * a perfectly normal AMS orange and sends him hunting for a problem.
     */
    private fun humidityColor(ctx: Context, humidity: Int): Int = when {
        humidity <= HUMIDITY_GOOD -> Ui.good(ctx)
        humidity <= HUMIDITY_FAIR -> Ui.warn(ctx)
        else -> Ui.bad(ctx)
    }

    private fun humidityWord(humidity: Int): String = when {
        humidity <= HUMIDITY_GOOD -> "Good"
        humidity <= HUMIDITY_FAIR -> "Fair"
        else -> "Damp"
    }

    // Drying state can be reported on the unit or on the printer, depending on
    // the model; take whichever one actually said something.
    private fun dryStatusOf(status: JSONObject?, unit: JSONObject): Int? =
        firstInt(status, unit, "dry_status")

    private fun dryMinutesOf(status: JSONObject?, unit: JSONObject): Int =
        firstInt(status, unit, "dry_time") ?: 0

    private fun firstInt(status: JSONObject?, unit: JSONObject, key: String): Int? =
        unit.int(key) ?: status?.int(key)

    private fun firstStr(status: JSONObject?, unit: JSONObject, key: String): String? =
        unit.str(key) ?: status?.str(key)

    private companion object {
        /** Per cent left at which a spool is worth flagging on the row. */
        const val LOW_FILAMENT = 10

        const val HUMIDITY_GOOD = 40
        const val HUMIDITY_FAIR = 60

        // dry_status: 0 off, 1 checking, 2 drying, 3 cooling, 4 stopping, 5 error.
        const val DRY_CHECKING = 1
        const val DRY_STOPPING = 4
        const val DRY_ERROR = 5

        // tray.state: 9 empty, 10 a spool is in the slot, 11 it is loaded.
        const val TRAY_EMPTY = 9
        const val TRAY_PRESENT = 10
        const val TRAY_LOADED = 11
    }
}
