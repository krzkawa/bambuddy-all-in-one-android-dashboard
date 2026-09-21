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
        content.addView(header(ctx, "AMS"))
        picker = Ui.col(ctx)
        content.addView(picker, wide(ctx))
        body = Ui.col(ctx)
        content.addView(body, wide(ctx))

        observe(Repo.statuses) { render() }
        observe(Repo.selected) { signature = ""; render() }
        render()
    }

    private fun wide(ctx: Context) =
        Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

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
            body.addView(Ui.dim(ctx, "Pick a printer that the server can reach."))
            return
        }
        if (units.isEmpty() && external.isEmpty()) {
            body.addView(Ui.dim(ctx, "This printer is not reporting an AMS."))
            return
        }

        val loadedTray = status.int("tray_now") ?: 255
        for (unit in units) {
            body.addView(unitCard(ctx, id, status, unit, loadedTray))
            body.addView(Ui.space(ctx, 8))
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
        top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 12, 1))
        unit.int("humidity")?.let {
            val chip = Ui.body(ctx, "Humidity $it% · ${humidityWord(it)}")
            chip.setTextColor(humidityColor(ctx, it))
            top.addView(chip)
        }
        unit.dbl("temp")?.let {
            top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 10, 1))
            top.addView(Ui.dim(ctx, "${it.toInt()}°C"))
        }
        top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
        card.addView(top, wide(ctx))

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

        card.addView(Ui.space(ctx, 8))
        for (tray in unit.objects("tray")) {
            card.addView(slotRow(ctx, printerId, amsId, tray, loadedTray))
            card.addView(Ui.space(ctx, 6))
        }

        card.addView(dryingControls(ctx, printerId, status, amsId, running), wide(ctx))
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
        card.addView(Ui.space(ctx, 8))
        for (tray in trays) {
            val trayId = (tray.optInt("id", 254) - 254).coerceIn(0, 1)
            card.addView(slotRow(ctx, printerId, 255, tray, loadedTray, forcedTrayId = trayId))
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
        val slot = Assign.Slot(amsId, trayId, "", null)
        val isLoaded = loadedTray == slot.globalTrayId || tray.int("state") == TRAY_LOADED

        val row = Ui.row(ctx)
        row.background = Ui.rounded(
            Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.card_alt), 8, ctx,
            if (isLoaded) Ui.accent(ctx) else Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.stroke)
        )
        val p = Ui.dp(ctx, 8)
        row.setPadding(p, p, p, p)

        row.addView(Ui.swatch(ctx, tray.str("tray_color"), 26))
        row.addView(Ui.space(ctx, 1), Ui.lp(ctx, 10, 1))

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
        info.addView(Ui.body(ctx, if (amsId == 255) name else "Slot ${trayId + 1} · $name"))
        val bits = ArrayList<String>()
        tray.optInt("remain", -1).takeIf { it in 0..100 }?.let { bits.add("$it%") }
        tray.dbl("k")?.takeIf { it > 0 }?.let { bits.add("k ${String.format("%.3f", it)}") }
        when {
            isLoaded || trayState == TRAY_LOADED -> bits.add("loaded")
            trayState == TRAY_PRESENT -> bits.add("in the slot")
            trayState == TRAY_EMPTY -> bits.add("empty")
        }
        if (tray.str("tray_uuid") != null) bits.add("RFID")
        info.addView(Ui.tiny(ctx, bits.joinToString(" · ").ifBlank { "No filament reported" }))
        row.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(Ui.button(ctx, "Assign") { chooseSpoolFor(ctx, printerId, slot) })
        row.addView(Ui.space(ctx, 1), Ui.lp(ctx, 6, 1))
        if (isLoaded) {
            row.addView(Ui.button(ctx, "Unload") {
                command("Unload") { Repo.api.amsUnload(printerId, slot.globalTrayId) }
            })
        } else {
            row.addView(Ui.button(ctx, "Load") {
                command("Load") { Repo.api.amsLoad(printerId, slot.globalTrayId) }
            })
        }
        if (amsId != 255) {
            row.addView(Ui.space(ctx, 1), Ui.lp(ctx, 6, 1))
            row.addView(Ui.button(ctx, "Re-read") {
                command("RFID re-read") { Repo.api.refreshSlotRfid(printerId, amsId, trayId) }
            })
        }
        return row
    }

    /** Slot-first assignment: pick the slot on screen, then choose the spool. */
    private fun chooseSpoolFor(ctx: Context, printerId: Int, slot: Assign.Slot) {
        toast("Loading spools…")
        background({ Repo.api.spools() }) { result ->
            val spools = result.getOrNull().objects()
            if (spools.isEmpty()) {
                toast(result.exceptionOrNull()?.message ?: "No spools in your inventory yet")
                return@background
            }
            val labels = spools.map { "${Assign.spoolName(it)}  —  ${Assign.spoolRemaining(it)}" }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle("Assign which spool?")
                .setItems(labels) { _, which ->
                    Assign.send(spools[which].optInt("id"), printerId, slot) { toast(it) }
                }
                .setNegativeButton("Cancel", null)
                .show()
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
