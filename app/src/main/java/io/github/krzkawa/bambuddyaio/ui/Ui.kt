package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.krzkawa.bambuddyaio.R

/**
 * Small hand-rolled widget kit.
 *
 * The screens are built in code rather than XML: it keeps the whole layout of
 * one screen readable in one place, and it avoids inflating a stack of nested
 * layouts on a phone with very little to spare.
 */
object Ui {

    fun color(ctx: Context, id: Int): Int = ContextCompat.getColor(ctx, id)

    fun bg(ctx: Context) = color(ctx, R.color.bg)
    fun cardColor(ctx: Context) = color(ctx, R.color.card)
    fun textColor(ctx: Context) = color(ctx, R.color.text)
    fun dimColor(ctx: Context) = color(ctx, R.color.text_dim)
    fun accent(ctx: Context) = color(ctx, R.color.accent)
    fun good(ctx: Context) = color(ctx, R.color.good)
    fun warn(ctx: Context) = color(ctx, R.color.warn)
    fun bad(ctx: Context) = color(ctx, R.color.bad)

    fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics
        ).toInt()

    fun rounded(fill: Int, radiusDp: Int, ctx: Context, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(fill)
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(ctx, 1), strokeColor)
        return d
    }

    // ------------------------------------------------------------ containers

    fun col(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun row(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun card(ctx: Context): LinearLayout {
        val l = col(ctx)
        l.background = rounded(cardColor(ctx), 10, ctx, color(ctx, R.color.stroke))
        val p = dp(ctx, 10)
        l.setPadding(p, p, p, p)
        return l
    }

    fun scroll(ctx: Context, child: View): ScrollView {
        val s = ScrollView(ctx)
        s.isFillViewport = true
        s.addView(
            child,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return s
    }

    /** Layout params for a child of a LinearLayout, in dp (or MATCH/WRAP constants). */
    fun lp(ctx: Context, w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams {
        fun size(v: Int) = when (v) {
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT -> v
            else -> dp(ctx, v)
        }
        return LinearLayout.LayoutParams(size(w), size(h), weight)
    }

    fun LinearLayout.LayoutParams.margins(ctx: Context, l: Int, t: Int, r: Int, b: Int):
        LinearLayout.LayoutParams {
        setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b))
        return this
    }

    fun space(ctx: Context, heightDp: Int): View {
        val v = View(ctx)
        v.layoutParams = lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, heightDp)
        return v
    }

    // ----------------------------------------------------------------- text

    private fun text(ctx: Context, s: CharSequence, sizeSp: Float, colour: Int, bold: Boolean): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(colour)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    fun big(ctx: Context, s: CharSequence) = text(ctx, s, 21f, textColor(ctx), true)
    fun title(ctx: Context, s: CharSequence) = text(ctx, s, 15f, textColor(ctx), true)
    fun body(ctx: Context, s: CharSequence) = text(ctx, s, 13f, textColor(ctx), false)
    fun dim(ctx: Context, s: CharSequence) = text(ctx, s, 12f, dimColor(ctx), false)
    fun tiny(ctx: Context, s: CharSequence) = text(ctx, s, 10f, dimColor(ctx), false)

    /** Small all-caps section heading. */
    fun heading(ctx: Context, s: String): TextView {
        val t = text(ctx, s.uppercase(), 10f, dimColor(ctx), true)
        t.letterSpacing = 0.08f
        return t
    }

    /** A label above a value, the shape most of this dashboard is made of. */
    fun stat(ctx: Context, label: String, value: CharSequence, valueColor: Int? = null): LinearLayout {
        val c = col(ctx)
        c.addView(heading(ctx, label))
        val v = text(ctx, value, 15f, valueColor ?: textColor(ctx), true)
        c.addView(v)
        c.tag = v
        return c
    }

    /** Updates a stat built above without rebuilding it. */
    fun setStat(container: LinearLayout, value: CharSequence, valueColor: Int? = null) {
        val v = container.tag as? TextView ?: return
        v.text = value
        if (valueColor != null) v.setTextColor(valueColor)
    }

    // --------------------------------------------------------------- inputs

    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 13f, if (primary) Color.WHITE else textColor(ctx), true)
        t.gravity = Gravity.CENTER
        t.background = rounded(
            if (primary) accent(ctx) else color(ctx, R.color.card_alt),
            8, ctx, color(ctx, R.color.stroke)
        )
        val px = dp(ctx, 12)
        val py = dp(ctx, 9)
        t.setPadding(px, py, px, py)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun input(ctx: Context, hint: String, value: String = ""): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.setTextColor(textColor(ctx))
        e.setHintTextColor(dimColor(ctx))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        e.background = rounded(color(ctx, R.color.card_alt), 8, ctx, color(ctx, R.color.stroke))
        val p = dp(ctx, 10)
        e.setPadding(p, p, p, p)
        e.maxLines = 1
        return e
    }

    /** Filament colour chip. Accepts RRGGBB or RRGGBBAA, with or without a #. */
    fun swatch(ctx: Context, rgba: String?, sizeDp: Int = 22): View {
        val v = View(ctx)
        v.layoutParams = lp(ctx, sizeDp, sizeDp)
        v.background = rounded(parseColor(rgba) ?: color(ctx, R.color.card_alt), 4, ctx, color(ctx, R.color.stroke))
        return v
    }

    fun parseColor(rgba: String?): Int? {
        val s = rgba?.trim()?.removePrefix("#") ?: return null
        return try {
            when (s.length) {
                6 -> Color.parseColor("#$s")
                8 -> {
                    // Tags store RRGGBBAA; Android wants AARRGGBB.
                    val rgb = s.substring(0, 6)
                    val a = s.substring(6, 8)
                    Color.parseColor("#$a$rgb")
                }
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun toast(ctx: Context, message: String) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------- shaping

    /** "1h 24m" from a minute count; blank when there is nothing to say. */
    fun minutes(total: Int?): String {
        if (total == null || total <= 0) return "—"
        val h = total / 60
        val m = total % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun temp(value: Double?, target: Double? = null): String {
        if (value == null) return "—"
        val now = "${value.toInt()}°"
        val goal = target?.toInt() ?: 0
        return if (goal > 0) "$now / $goal°" else now
    }

    fun percent(v: Double?): String = if (v == null) "—" else "${v.toInt()}%"
}
