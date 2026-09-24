package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.bool
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/**
 * The printer's own watchfulness: the camera checks that stop a print gone
 * wrong, and the air duct on the models that can switch it.
 */
object PrintChecks {

    /**
     * One camera check. [field] is where the status reports it, [module] what
     * the command calls it, and [sensitivity] the status field whose current
     * value is sent back with a toggle so it is not reset.
     */
    data class Check(val field: String, val module: String, val label: String, val sensitivity: String? = null)

    /**
     * The checks the printer reports from its camera settings. Three newer
     * detectors (pile-up, clumping, air printing) are left out: the status
     * reports them as on by default on printers that do not have them, so a
     * switch for them could not be told from a dead one.
     */
    val CHECKS = listOf(
        Check("spaghetti_detector", "spaghetti_detector", "Spaghetti detection", "halt_print_sensitivity"),
        Check("first_layer_inspector", "first_layer_inspector", "First layer inspection"),
        Check("printing_monitor", "printing_monitor", "AI print monitoring"),
        Check("buildplate_marker_detector", "buildplate_marker_detector", "Build plate check"),
        Check("allow_skip_parts", "allow_skip_parts", "Allow skipping parts"),
        Check("auto_recovery_step_loss", "auto_recovery_step_loss", "Recover lost steps")
    )

    /** Models with a switchable air duct, the same list the web interface uses. */
    fun hasAirduct(model: String?): Boolean {
        val m = model?.trim()?.uppercase().orEmpty()
        return m == "P2S" || m == "X2D" || m.startsWith("H2")
    }

    /** What decides the card's shape, for the Control screen's rebuild signature. */
    fun signature(status: JSONObject?): String {
        val options = status?.optJSONObject("print_options") ?: return ""
        return CHECKS.joinToString("") { if (options.bool(it.field)) "1" else "0" } + status.int("airduct_mode")
    }

    /** Null when the printer reports no camera settings at all. */
    fun card(ctx: Context, printerId: Int, status: JSONObject): LinearLayout? {
        val options = status.optJSONObject("print_options") ?: return null
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, "Print checks"))

        // Two columns: landscape has the width, and six rows stacked would
        // push everything under them off the screen.
        val rows = CHECKS.chunked(2)
        rows.forEachIndexed { index, pair ->
            if (index > 0) card.addView(Ui.divider(ctx))
            val line = Ui.row(ctx)
            line.setPadding(0, Ui.dp(ctx, Ui.XS), 0, Ui.dp(ctx, Ui.XS))
            pair.forEachIndexed { i, check ->
                if (i > 0) Ui.gap(ctx, line, Ui.XL)
                line.addView(toggle(ctx, printerId, options, check), Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (pair.size == 1) {
                Ui.gap(ctx, line, Ui.XL)
                line.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
            }
            card.addView(line, Ui.wide(ctx))
        }
        return card
    }

    private fun toggle(ctx: Context, printerId: Int, options: JSONObject, check: Check): LinearLayout {
        val row = Ui.row(ctx)
        val name = Ui.body(ctx, check.label)
        name.maxLines = 1
        row.addView(name, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val on = options.bool(check.field)
        row.addView(Ui.segmented(ctx, listOf("Off", "On"), if (on) 1 else 0) { index ->
            val want = index == 1
            if (want == on) return@segmented
            val sensitivity = check.sensitivity?.let { options.str(it) }
            val app = ctx.applicationContext
            Repo.action("${check.label} ${if (want) "on" else "off"}", {
                Repo.api.setPrintOption(printerId, check.module, want, sensitivity)
            }) { Ui.toast(app, it) }
        })
        return row
    }

    /** The air duct as one strip: cooling for PLA, heating to hold the chamber warm for ABS and the like. */
    fun airduct(ctx: Context, printerId: Int, status: JSONObject): LinearLayout {
        val row = Ui.row(ctx)
        row.addView(Ui.heading(ctx, "Air duct"))
        Ui.push(ctx, row)
        val heating = status.int("airduct_mode") == 1
        row.addView(Ui.segmented(ctx, listOf("Cooling", "Heating"), if (heating) 1 else 0) { index ->
            val want = index == 1
            if (want == heating) return@segmented
            val app = ctx.applicationContext
            val mode = if (want) "heating" else "cooling"
            Repo.action("Air duct $mode", { Repo.api.setAirductMode(printerId, mode) }) { Ui.toast(app, it) }
        })
        return row
    }
}
