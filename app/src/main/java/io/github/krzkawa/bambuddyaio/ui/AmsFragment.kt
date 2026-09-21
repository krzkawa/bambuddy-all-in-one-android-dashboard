package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.net.Repo
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
            for (u in units) {
                append(u.optInt("id")).append(u.int("humidity")).append(u.int("dry_status"))
                    .append(u.int("dry_time"))
                for (t in u.objects("tray")) {
                    append(t.str("tray_type")).append(t.str("tray_color")).append(t.optInt("remain"))
                        .append(t.int("state"))
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
            body.addView(unitCard(ctx, id, unit, loadedTray))
            body.addView(Ui.space(ctx, 8))
        }
        if (external.isNotEmpty()) {
            body.addView(externalCard(ctx, id, external, loadedTray))
        }
    }

    private fun unitCard(ctx: Context, printerId: Int, unit: JSONObject, loadedTray: Int): LinearLayout {
        val card = Ui.card(ctx)
        val amsId = unit.optInt("id")

        val top = Ui.row(ctx)
        top.addView(Ui.title(ctx, "AMS ${amsId + 1}"))
        top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 12, 1))
        unit.int("humidity")?.let {
            val chip = Ui.body(ctx, "Humidity $it%")
            chip.setTextColor(humidityColor(ctx, it))
            top.addView(chip)
        }
        unit.dbl("temp")?.let {
            top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 10, 1))
            top.addView(Ui.dim(ctx, "${it.toInt()}°C"))
        }
        top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
        card.addView(top, wide(ctx))

        val drying = (unit.int("dry_time") ?: 0) > 0
        if (drying) {
            card.addView(Ui.dim(ctx, "Drying · ${Ui.minutes(unit.int("dry_time"))} remaining"))
        }

        card.addView(Ui.space(ctx, 8))
        for (tray in unit.objects("tray")) {
            card.addView(slotRow(ctx, printerId, amsId, tray, loadedTray))
            card.addView(Ui.space(ctx, 6))
        }

        val actions = Ui.row(ctx)
        if (drying) {
            actions.addView(Ui.button(ctx, "Stop drying") {
                command("Stop drying") { Repo.api.stopDrying(printerId, amsId) }
            })
        } else {
            actions.addView(Ui.button(ctx, "Dry") { askDrying(ctx, printerId, amsId) })
        }
        card.addView(actions, wide(ctx))
        return card
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
        val isLoaded = loadedTray == slot.globalTrayId

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
        val name = tray.str("tray_sub_brands") ?: tray.str("tray_type") ?: "Empty"
        info.addView(Ui.body(ctx, if (amsId == 255) name else "Slot ${trayId + 1} · $name"))
        val bits = ArrayList<String>()
        tray.optInt("remain", -1).takeIf { it in 0..100 }?.let { bits.add("$it%") }
        tray.dbl("k")?.takeIf { it > 0 }?.let { bits.add("k ${String.format("%.3f", it)}") }
        if (isLoaded) bits.add("loaded")
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

    private fun askDrying(ctx: Context, printerId: Int, amsId: Int) {
        val options = arrayOf("45° for 6h (PLA, PETG)", "55° for 8h (ABS, ASA)", "65° for 8h (PA, PC)")
        val settings = listOf(45 to 6, 55 to 8, 65 to 8)
        AlertDialog.Builder(ctx)
            .setTitle("Dry AMS ${amsId + 1}")
            .setItems(options) { _, which ->
                val (temp, hours) = settings[which]
                command("Drying") { Repo.api.startDrying(printerId, amsId, temp, hours) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun humidityColor(ctx: Context, humidity: Int): Int = when {
        humidity <= 20 -> Ui.good(ctx)
        humidity <= 40 -> Ui.warn(ctx)
        else -> Ui.bad(ctx)
    }
}
