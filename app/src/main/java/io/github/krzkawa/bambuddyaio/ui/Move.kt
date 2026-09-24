package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo
import org.json.JSONObject

/**
 * Moving the machine by hand: the toolhead, the bed, the filament, homing, and
 * the printer's own calibration runs.
 *
 * Every button here moves metal, and the phone sits next to the printer where
 * a sleeve can brush it. So the card opens locked, unlocks for a minute at a
 * time, and is not offered at all while a print is on the plate.
 */
object Move {

    /** Step sizes offered, in mm. The same three the web interface offers. */
    val STEPS = listOf(1.0, 10.0, 50.0)

    /** How long the pad stays live after the last tap. */
    const val UNLOCK_MS = 60_000L

    /** Kept across rebuilds of the Control screen, so a poll does not relock it. */
    private var unlockedUntil = 0L
    private var unlockedFor = -1
    private var stepIndex = 0

    fun unlocked(printerId: Int, now: Long = System.currentTimeMillis()): Boolean =
        unlockedFor == printerId && now < unlockedUntil

    fun step(): Double = STEPS[stepIndex.coerceIn(0, STEPS.lastIndex)]

    /**
     * Whether Z moves the toolhead (A1 family) rather than the bed. The server
     * already flips the sign for these; this only decides what the buttons
     * say, so that "Up" goes up. The list is Bambuddy's own `A1_MODELS`.
     */
    fun headMovesOnZ(model: String?): Boolean =
        model?.trim()?.uppercase() in setOf("A1", "A1 MINI", "A1-MINI", "A1MINI", "N1", "N2S")

    /**
     * The signed gap change for an Up or Down press. Negative closes the gap.
     * On a bed-on-Z printer the bed going up closes it; on an A1 the head
     * going up opens it.
     */
    fun gapFor(up: Boolean, headMoves: Boolean, step: Double): Double =
        if (up == headMoves) step else -step

    /** The calibrations the server can run, and which printers each makes sense on. */
    data class Calibration(val key: String, val label: String)

    fun calibrations(model: String?, nozzles: Int): List<Calibration> {
        val m = model?.trim()?.uppercase().orEmpty()
        val list = arrayListOf(
            Calibration("bed_leveling", "Bed levelling"),
            Calibration("vibration", "Vibration compensation"),
            Calibration("motor_noise", "Motor noise cancellation")
        )
        if (nozzles > 1) list.add(Calibration("nozzle_offset", "Nozzle offset"))
        if (m.startsWith("H2")) list.add(Calibration("high_temp_heatbed", "High-temperature bed"))
        return list
    }

    // ------------------------------------------------------------------- views

    /**
     * The card, drawn into [holder] and redrawn in place as it locks, unlocks
     * or changes step. [busy] hides the pad: nothing here belongs mid-print.
     */
    fun fill(holder: LinearLayout, printerId: Int, model: String?, status: JSONObject?) {
        val ctx = holder.context
        holder.removeAllViews()
        holder.removeCallbacks(relock)
        val card = Ui.card(ctx)
        holder.addView(card, Ui.wide(ctx))
        val nozzles = status?.optJSONArray("nozzles")?.length() ?: 1
        val redraw = { fill(holder, printerId, model, status) }

        val top = Ui.row(ctx)
        top.addView(Ui.heading(ctx, "Move"))
        Ui.push(ctx, top)
        if (unlocked(printerId)) {
            top.addView(Ui.segmented(ctx, STEPS.map { "${it.toInt()} mm" }, stepIndex) { index ->
                stepIndex = index
                touch(printerId)
                redraw()
            })
            Ui.gap(ctx, top, Ui.S)
            top.addView(Ui.quiet(ctx, "Lock") {
                unlockedUntil = 0L
                redraw()
            })
        } else {
            top.addView(Ui.button(ctx, "Home") { confirmHome(ctx, printerId) })
            Ui.gap(ctx, top, Ui.S)
            top.addView(Ui.button(ctx, "Calibrate") { chooseCalibration(ctx, printerId, model, nozzles) })
            Ui.gap(ctx, top, Ui.S)
            top.addView(Ui.button(ctx, "Unlock moves") {
                touch(printerId)
                redraw()
            })
        }
        card.addView(top, Ui.wide(ctx))

        if (!unlocked(printerId)) return

        card.addView(Ui.space(ctx, Ui.S))
        val pad = Ui.row(ctx)
        pad.gravity = Gravity.CENTER_VERTICAL

        // X and Y as a cross, with nothing in the middle: homing lives on the
        // locked card, behind its own question. The spacers are sized by hand:
        // Ui.space fills its width, which in a column that wraps its content
        // means the whole card.
        val xy = Ui.col(ctx)
        xy.addView(padRow(ctx, null, jog(ctx, printerId, "↑", "Y +") { Repo.api.xyJog(printerId, 0.0, step()) }, null))
        xy.addView(View(ctx), Ui.lp(ctx, 1, Ui.XS))
        xy.addView(padRow(ctx,
            jog(ctx, printerId, "←", "X −") { Repo.api.xyJog(printerId, -step(), 0.0) },
            label(ctx, "XY"),
            jog(ctx, printerId, "→", "X +") { Repo.api.xyJog(printerId, step(), 0.0) }))
        xy.addView(View(ctx), Ui.lp(ctx, 1, Ui.XS))
        xy.addView(padRow(ctx, null, jog(ctx, printerId, "↓", "Y −") { Repo.api.xyJog(printerId, 0.0, -step()) }, null))
        pad.addView(xy)
        Ui.gap(ctx, pad, Ui.XL)

        val head = headMovesOnZ(model)
        val z = Ui.col(ctx)
        z.gravity = Gravity.CENTER_HORIZONTAL
        z.addView(jog(ctx, printerId, "↑", if (head) "Head up" else "Bed up") {
            Repo.api.bedJog(printerId, gapFor(true, head, step()))
        })
        z.addView(label(ctx, if (head) "Head" else "Bed"))
        z.addView(jog(ctx, printerId, "↓", if (head) "Head down" else "Bed down") {
            Repo.api.bedJog(printerId, gapFor(false, head, step()))
        })
        pad.addView(z)
        Ui.gap(ctx, pad, Ui.XL)

        val e = Ui.col(ctx)
        e.gravity = Gravity.CENTER_HORIZONTAL
        e.addView(jog(ctx, printerId, "Retract", "Retract", wide = true) { Repo.api.extruderJog(printerId, -step()) })
        e.addView(label(ctx, "Filament"))
        e.addView(jog(ctx, printerId, "Extrude", "Extrude", wide = true) { Repo.api.extruderJog(printerId, step()) })
        pad.addView(e)
        Ui.gap(ctx, pad, Ui.XL)

        val warning = Ui.tiny(ctx,
            "Moves go straight to the printer, and it may not stop at its travel limits. " +
                "Start small. The nozzle has to be hot to extrude.")
        warning.setTextColor(Ui.warn(ctx))
        pad.addView(warning, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(pad, Ui.wide(ctx))

        // Relock by itself once he walks away.
        relockTarget = { fill(holder, printerId, model, status) }
        holder.postDelayed(relock, (unlockedUntil - System.currentTimeMillis()).coerceAtLeast(0L) + 50L)
    }

    private var relockTarget: (() -> Unit)? = null
    private val relock = Runnable { relockTarget?.invoke() }

    private fun touch(printerId: Int) {
        unlockedFor = printerId
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
    }

    private fun padRow(ctx: Context, left: View?, middle: View?, right: View?): LinearLayout {
        val row = Ui.row(ctx)
        row.layoutParams = Ui.lp(ctx, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        listOf(left, middle, right).forEachIndexed { index, v ->
            if (index > 0) Ui.gap(ctx, row, Ui.XS)
            row.addView(v ?: View(ctx), Ui.lp(ctx, KEY, KEY))
        }
        return row
    }

    private fun label(ctx: Context, text: String): View {
        val t = Ui.tiny(ctx, text)
        t.gravity = Gravity.CENTER
        t.setPadding(0, Ui.dp(ctx, Ui.XS), 0, Ui.dp(ctx, Ui.XS))
        // A column's default is to fill its width, which in a row that wraps
        // its content means taking the whole card.
        t.layoutParams = Ui.lp(ctx, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return t
    }

    /** One jog key. Each press sends one move of the current step and keeps the pad awake. */
    private fun jog(
        ctx: Context,
        printerId: Int,
        face: String,
        what: String,
        wide: Boolean = false,
        send: () -> Unit
    ): View {
        val key = Ui.button(ctx, face) {
            touch(printerId)
            val app = ctx.applicationContext
            val label = "$what ${fmt(step())} mm"
            Repo.action(label, send) { message ->
                // Every press succeeding is the normal case and the pad shows
                // it moving; only a refusal is worth a toast.
                if (Repo.notice.value?.failed == true) Ui.toast(app, message)
            }
        }
        key.contentDescription = what
        if (!wide) {
            key.setPadding(0, key.paddingTop, 0, key.paddingBottom)
            key.layoutParams = Ui.lp(ctx, KEY, KEY)
        } else {
            key.layoutParams = Ui.lp(ctx, ViewGroup.LayoutParams.WRAP_CONTENT, KEY)
        }
        return key
    }

    private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else v.toString()

    fun confirmHome(ctx: Context, printerId: Int) {
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Home the axes?")
            .setMessage("The printer runs its full homing sequence: head to the corner, then the bed. Keep the plate clear.")
            .setPositiveButton("Home") { _, _ ->
                val app = ctx.applicationContext
                Repo.action("Home", { Repo.api.homeAxes(printerId) }) { Ui.toast(app, it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Picks the runs, then asks once more: a calibration takes the printer
     * out of service for a good few minutes and cannot be cut short from here.
     */
    fun chooseCalibration(ctx: Context, printerId: Int, model: String?, nozzles: Int) {
        val options = calibrations(model, nozzles)
        val picked = BooleanArray(options.size)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Calibrate ${Repo.printerName(printerId)}")
            .setMultiChoiceItems(options.map { it.label }.toTypedArray(), picked) { _, which, on -> picked[which] = on }
            .setPositiveButton("Next") { _, _ ->
                val chosen = options.filterIndexed { i, _ -> picked[i] }
                if (chosen.isEmpty()) {
                    Ui.toast(ctx, "Pick at least one")
                } else {
                    confirmCalibration(ctx, printerId, chosen)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmCalibration(ctx: Context, printerId: Int, chosen: List<Calibration>) {
        val keys = chosen.map { it.key }.toSet()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Start calibrating?")
            .setMessage(
                chosen.joinToString(", ") { it.label } +
                    ".\n\nThe printer will be busy for several minutes and cannot print until it finishes. Keep the plate empty."
            )
            .setPositiveButton("Start") { _, _ ->
                val app = ctx.applicationContext
                Repo.action("Calibration", {
                    Repo.api.calibrate(
                        printerId,
                        bedLeveling = "bed_leveling" in keys,
                        vibration = "vibration" in keys,
                        motorNoise = "motor_noise" in keys,
                        nozzleOffset = "nozzle_offset" in keys,
                        highTempBed = "high_temp_heatbed" in keys
                    )
                }) { Ui.toast(app, it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A jog key's side, in dp: big enough for a thumb, small enough for a cross of five. */
    private const val KEY = 44
}
