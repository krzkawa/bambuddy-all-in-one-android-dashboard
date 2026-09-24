package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.krzkawa.bambuddyaio.R

/**
 * The whole visual language of the app, in one place.
 *
 * Screens are built in code rather than XML: it keeps the layout of one screen
 * readable in one place, and avoids inflating a stack of nested layouts on a
 * phone with very little to spare.
 *
 * Three rules everything here follows, because this screen is read from arm's
 * length across a workshop rather than held in a hand:
 *
 *  * Values are large and their labels are small — never the other way round.
 *  * Colour means state. Green, amber and red say what the printer is doing;
 *    anything you can press is a light neutral, so a green never has to be
 *    read twice.
 *  * Separation comes from space, then from a hairline, and only then from a
 *    border. A screen of boxes inside boxes has no hierarchy left to give.
 */
object Ui {

    // Spacing steps. Every gap in the app is one of these.
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24

    private val MEDIUM: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val REGULAR: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }

    fun color(ctx: Context, id: Int): Int = ContextCompat.getColor(ctx, id)

    fun bg(ctx: Context) = color(ctx, R.color.bg)
    fun cardColor(ctx: Context) = color(ctx, R.color.card)
    fun insetColor(ctx: Context) = color(ctx, R.color.card_alt)
    fun strokeColor(ctx: Context) = color(ctx, R.color.stroke)
    fun textColor(ctx: Context) = color(ctx, R.color.text)
    fun dimColor(ctx: Context) = color(ctx, R.color.text_dim)
    fun faintColor(ctx: Context) = color(ctx, R.color.text_faint)
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

    /**
     * A background that visibly reacts to a finger.
     *
     * There is no ripple on anything built here, and a button that does not
     * move under a tap feels broken on a phone this slow to answer.
     */
    fun pressable(ctx: Context, resting: Drawable, pressedFill: Int, radiusDp: Int): Drawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(pressedFill, radiusDp, ctx))
        states.addState(intArrayOf(), resting)
        return states
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

    /** A panel. No border: it sits on the page by being a shade lighter than it. */
    fun card(ctx: Context): LinearLayout {
        val l = col(ctx)
        l.background = rounded(cardColor(ctx), 12, ctx)
        val p = dp(ctx, M)
        l.setPadding(p, p, p, p)
        return l
    }

    /** A row inside a card: quieter than a card, and never bordered. */
    fun inset(ctx: Context): LinearLayout {
        val l = row(ctx)
        l.background = rounded(insetColor(ctx), 10, ctx)
        val px = dp(ctx, 10)
        val py = dp(ctx, S)
        l.setPadding(px, py, px, py)
        return l
    }

    /** A hairline. Cheaper on the eye than another border, and always enough. */
    fun divider(ctx: Context, insetDp: Int = 0): View {
        val v = View(ctx)
        v.setBackgroundColor(color(ctx, R.color.stroke_soft))
        val lp = lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, 1)
        lp.setMargins(dp(ctx, insetDp), 0, dp(ctx, insetDp), 0)
        v.layoutParams = lp
        return v
    }

    fun scroll(ctx: Context, child: View): ScrollView {
        val s = ScrollView(ctx)
        s.isFillViewport = true
        // A screen that ends flush with the bottom edge looks like the end of
        // the screen. Fading the last few dp is the only hint he gets that
        // there is more under his thumb, and it costs nothing to draw.
        s.isVerticalFadingEdgeEnabled = true
        s.setFadingEdgeLength(dp(ctx, XL))
        s.isVerticalScrollBarEnabled = false
        s.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        s.addView(
            child,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return s
    }

    // ------------------------------------------------------------- the grid

    /**
     * How many list columns this screen is wide enough for.
     *
     * The phone is landscape and never rotates, so this is really a constant —
     * but it is read from the window rather than assumed, so the app still
     * looks right on a small screen, and on a tablet if one ever turns up.
     */
    fun listColumns(ctx: Context, minColumnDp: Int = 240): Int {
        val available = ctx.resources.configuration.screenWidthDp - RAIL_AND_GUTTERS_DP
        return (available / minColumnDp).coerceIn(1, 3)
    }

    /** The rail plus both page gutters, taken off the width before dividing it. */
    private const val RAIL_AND_GUTTERS_DP = 106 + M * 2

    /**
     * Lays a list out in columns rather than down one long strip.
     *
     * A row of this app is one line of text and a button. Down a 640 dp-wide
     * landscape screen that is a column of text with a hand's width of nothing
     * beside it, and six rows in view. Two columns is the same row twice over
     * and twelve in view, which is the difference between reading the list and
     * scrolling it.
     */
    fun grid(ctx: Context, items: List<View>, columns: Int, gapDp: Int = 6): LinearLayout {
        val grid = col(ctx)
        if (columns <= 1) {
            items.forEachIndexed { index, item ->
                if (index > 0) grid.addView(space(ctx, gapDp))
                grid.addView(item, wide(ctx))
            }
            return grid
        }
        items.chunked(columns).forEachIndexed { index, chunk ->
            if (index > 0) grid.addView(space(ctx, gapDp))
            val line = row(ctx)
            line.gravity = Gravity.TOP
            chunk.forEachIndexed { at, item ->
                if (at > 0) gap(ctx, line, gapDp)
                line.addView(item, lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            // The short last row keeps its columns the width of the ones above
            // it, rather than stretching one card across the page.
            repeat(columns - chunk.size) {
                gap(ctx, line, gapDp)
                line.addView(space(ctx, 1), lp(ctx, 0, 1, 1f))
            }
            grid.addView(line, wide(ctx))
        }
        return grid
    }

    /**
     * A row of controls that shares out the width it is given.
     *
     * Four keys sized by their own labels either overflow a narrow card or
     * leave it ragged. Sharing the row equally means they always fit, and it
     * makes the smallest of them a bigger target than it was.
     */
    fun keys(ctx: Context, buttons: List<View>, gapDp: Int = XS): LinearLayout {
        val line = row(ctx)
        buttons.forEachIndexed { index, b ->
            if (index > 0) gap(ctx, line, gapDp)
            b.setPadding(dp(ctx, S), b.paddingTop, dp(ctx, S), b.paddingBottom)
            line.addView(b, lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return line
    }

    /**
     * Two stacks of panels side by side.
     *
     * The same argument as [grid], for screens made of sections rather than
     * rows: Control is four panels that each used a third of the width, so
     * three of them were under the fold.
     */
    fun sideBySide(
        ctx: Context,
        left: List<View>,
        right: List<View>,
        gapDp: Int = M,
        leftWeight: Float = 1f,
        rightWeight: Float = 1f
    ): LinearLayout {
        val line = row(ctx)
        line.gravity = Gravity.TOP
        fun stack(views: List<View>): LinearLayout {
            val c = col(ctx)
            views.forEachIndexed { index, v ->
                if (index > 0) c.addView(space(ctx, gapDp))
                c.addView(v, wide(ctx))
            }
            return c
        }
        line.addView(stack(left), lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, leftWeight))
        gap(ctx, line, gapDp)
        line.addView(stack(right), lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, rightWeight))
        return line
    }

    /**
     * Deals a list of panels into two columns of roughly equal height.
     *
     * Alternating them puts every other panel on the right, which is wrong
     * when the first one is four times the height of the second. This keeps a
     * running total and always adds to the shorter side.
     */
    fun dealt(
        ctx: Context,
        sections: List<Pair<View, Int>>,
        gapDp: Int = M,
        leftShare: Float = 1f,
        rightShare: Float = 1f
    ): LinearLayout {
        val left = ArrayList<View>()
        val right = ArrayList<View>()
        var leftHeight = 0
        var rightHeight = 0
        for ((view, weight) in sections) {
            if (leftHeight <= rightHeight) {
                left.add(view); leftHeight += weight
            } else {
                right.add(view); rightHeight += weight
            }
        }
        return sideBySide(ctx, left, right, gapDp, leftShare, rightShare)
    }

    // ------------------------------------------------------------ the states

    /**
     * What a screen says when it has nothing to show: waiting, empty, or
     * broken.
     *
     * Every screen used to word these itself, so a failed load was a toast on
     * one screen, a red line on another and a silent empty list on a third.
     * One shape for all three, and only the broken one offers a button —
     * pressing Try again on an empty inventory does nothing twice.
     */
    fun notice(
        ctx: Context,
        message: String,
        detail: String? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ): LinearLayout {
        val box = col(ctx)
        box.gravity = Gravity.CENTER_HORIZONTAL
        val p = dp(ctx, XL)
        box.setPadding(dp(ctx, L), p, dp(ctx, L), p)
        val head = text(ctx, message, 15f, dimColor(ctx), MEDIUM)
        head.gravity = Gravity.CENTER
        box.addView(head)
        if (detail != null) {
            val sub = text(ctx, detail, 13f, faintColor(ctx))
            sub.gravity = Gravity.CENTER
            sub.setPadding(0, dp(ctx, XS), 0, 0)
            box.addView(sub)
        }
        if (actionLabel != null && onAction != null) {
            box.addView(space(ctx, M))
            box.addView(
                button(ctx, actionLabel, onClick = onAction),
                lp(ctx, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        return box
    }

    /**
     * Waiting, said without a word.
     *
     * "Loading…" on every screen every ten seconds is a screen that reads as
     * perpetually broken. Three dots breathing say the same thing and stop
     * being read after the first time.
     */
    fun waiting(ctx: Context): LinearLayout {
        val box = col(ctx)
        box.gravity = Gravity.CENTER_HORIZONTAL
        val p = dp(ctx, XL)
        box.setPadding(0, p, 0, p)
        val dots = row(ctx)
        repeat(3) { index ->
            if (index > 0) gap(ctx, dots, S)
            val d = dot(ctx, faintColor(ctx), 6)
            // A view animation rather than a ViewPropertyAnimator loop: this one
            // runs in the draw pass and asks the main looper for nothing, so a
            // screen left waiting is not a message posted every 400 ms for as
            // long as the server stays quiet.
            val pulse = AlphaAnimation(0.25f, 1f)
            pulse.duration = 480
            pulse.startOffset = index * 160L
            pulse.repeatCount = Animation.INFINITE
            pulse.repeatMode = Animation.REVERSE
            d.startAnimation(pulse)
            dots.addView(d)
        }
        box.addView(dots, lp(ctx, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    /**
     * A short fade as a screen's contents arrive.
     *
     * Everything here is rebuilt rather than updated, so a reload is a page
     * blinking out and another one appearing in its place. A tenth of a second
     * of fade turns that into the same page changing, which is what it is.
     */
    fun arrive(view: View) {
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(120).start()
    }

    /** Layout params for a child of a LinearLayout, in dp (or MATCH/WRAP constants). */
    fun lp(ctx: Context, w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams {
        fun size(v: Int) = when (v) {
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT -> v
            else -> dp(ctx, v)
        }
        return LinearLayout.LayoutParams(size(w), size(h), weight)
    }

    /** Full width, own height — what almost every child of a screen wants. */
    fun wide(ctx: Context): LinearLayout.LayoutParams =
        lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

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

    /** Horizontal gap inside a row. */
    fun gap(ctx: Context, row: LinearLayout, widthDp: Int = S) {
        row.addView(space(ctx, 1), lp(ctx, widthDp, 1))
    }

    /** Pushes whatever comes next in a row over to the right. */
    fun push(ctx: Context, row: LinearLayout) {
        row.addView(space(ctx, 1), lp(ctx, 0, 1, 1f))
    }

    // ----------------------------------------------------------------- text

    private fun text(
        ctx: Context,
        s: CharSequence,
        sizeSp: Float,
        colour: Int,
        face: Typeface = REGULAR
    ): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(colour)
        t.typeface = face
        return t
    }

    /** The one number on a screen that can be read across the room. */
    fun display(ctx: Context, s: CharSequence, colour: Int? = null): TextView {
        val t = text(ctx, s, 30f, colour ?: textColor(ctx), MEDIUM)
        // Large text set at its default tracking always looks a size too loose.
        t.letterSpacing = -0.02f
        t.includeFontPadding = false
        return t
    }

    fun big(ctx: Context, s: CharSequence) = text(ctx, s, 19f, textColor(ctx), MEDIUM)
    fun title(ctx: Context, s: CharSequence) = text(ctx, s, 15f, textColor(ctx), MEDIUM)
    fun body(ctx: Context, s: CharSequence) = text(ctx, s, 14f, textColor(ctx))
    fun dim(ctx: Context, s: CharSequence) = text(ctx, s, 13f, dimColor(ctx))
    fun tiny(ctx: Context, s: CharSequence) = text(ctx, s, 11f, faintColor(ctx))

    /**
     * A section label inside a card.
     *
     * Set in sentence case: a screen of tracked-out capitals reads like a
     * spreadsheet, and there are a dozen of these in view at once.
     */
    fun heading(ctx: Context, s: String): TextView {
        val t = text(ctx, s, 12f, dimColor(ctx), MEDIUM)
        t.setPadding(0, 0, 0, dp(ctx, XS))
        return t
    }

    /**
     * A reading and what it is: the value first and large, its name under it.
     *
     * The shape most of this dashboard is made of, and the reason it can be
     * read from a step back — the eye lands on 214° rather than on "Nozzle".
     */
    fun stat(ctx: Context, label: String, value: CharSequence, valueColor: Int? = null): LinearLayout {
        val c = col(ctx)
        val v = text(ctx, value, 17f, valueColor ?: textColor(ctx), MEDIUM)
        v.includeFontPadding = false
        c.addView(v)
        val name = text(ctx, label, 11f, faintColor(ctx))
        name.setPadding(0, dp(ctx, 2), 0, 0)
        c.addView(name)
        c.tag = v
        return c
    }

    /** Updates a stat built above without rebuilding it. */
    fun setStat(container: LinearLayout, value: CharSequence, valueColor: Int? = null) {
        val v = container.tag as? TextView ?: return
        v.text = value
        if (valueColor != null) v.setTextColor(valueColor)
    }

    /** A filled dot. State, said in the smallest mark that can say it. */
    fun dot(ctx: Context, colour: Int, sizeDp: Int = 7): View {
        val v = View(ctx)
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(colour)
        v.background = d
        v.layoutParams = lp(ctx, sizeDp, sizeDp)
        return v
    }

    // --------------------------------------------------------------- inputs

    /**
     * A button.
     *
     * Primary is a light fill with ink on it — one per group, and it needs no
     * colour to be the obvious one. Everything else is a hairline outline.
     */
    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        val t = text(
            ctx, label, 13f,
            if (primary) color(ctx, R.color.accent_ink) else textColor(ctx),
            MEDIUM
        )
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        val resting =
            if (primary) rounded(accent(ctx), 9, ctx)
            else rounded(insetColor(ctx), 9, ctx, strokeColor(ctx))
        t.background = pressable(
            ctx, resting,
            if (primary) color(ctx, R.color.text_dim) else color(ctx, R.color.pressed), 9
        )
        val px = dp(ctx, M)
        val py = dp(ctx, 9)
        t.setPadding(px, py, px, py)
        t.minHeight = dp(ctx, 36)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /** A button with no box around it, for the second-order things. */
    fun quiet(ctx: Context, label: String, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 13f, dimColor(ctx), MEDIUM)
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.background = pressable(
            ctx, rounded(Color.TRANSPARENT, 9, ctx), color(ctx, R.color.pressed), 9
        )
        val px = dp(ctx, 10)
        val py = dp(ctx, S)
        t.setPadding(px, py, px, py)
        // Same height as a real button: a word he has to aim at is worse than
        // one in a box, not better, and these sit right beside Start and Stop.
        t.minHeight = dp(ctx, 36)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /**
     * One choice out of a few, laid out as a single strip.
     *
     * Used wherever the old screens had a row of identical pills, where every
     * option shouted as loudly as the one that was actually picked.
     */
    fun segmented(
        ctx: Context,
        options: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ): LinearLayout {
        val strip = row(ctx)
        strip.background = rounded(insetColor(ctx), 10, ctx, strokeColor(ctx))
        val p = dp(ctx, 3)
        strip.setPadding(p, p, p, p)
        options.forEachIndexed { index, label ->
            val on = index == selected
            val seg = text(
                ctx, label, 13f,
                if (on) color(ctx, R.color.accent_ink) else dimColor(ctx),
                MEDIUM
            )
            seg.gravity = Gravity.CENTER
            seg.maxLines = 1
            seg.background =
                if (on) rounded(accent(ctx), 8, ctx)
                else pressable(ctx, rounded(Color.TRANSPARENT, 8, ctx), color(ctx, R.color.pressed), 8)
            val px = dp(ctx, M)
            val py = dp(ctx, 7)
            seg.setPadding(px, py, px, py)
            seg.isClickable = true
            seg.setOnClickListener { onPick(index) }
            strip.addView(seg)
        }
        return strip
    }

    /**
     * Wraps a strip that may be wider than the space it is given.
     *
     * Four speed names do not fit across half a screen, and a strip that
     * reflows to fit is a strip whose segments move under his finger. It
     * scrolls instead, which is what the printer picker has always done.
     */
    fun strip(ctx: Context, child: View): HorizontalScrollView {
        val s = HorizontalScrollView(ctx)
        s.isHorizontalScrollBarEnabled = false
        s.addView(child)
        return s
    }

    fun input(ctx: Context, hint: String, value: String = ""): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.setTextColor(textColor(ctx))
        e.setHintTextColor(faintColor(ctx))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        e.typeface = REGULAR
        e.background = rounded(insetColor(ctx), 9, ctx, strokeColor(ctx))
        val px = dp(ctx, M)
        val py = dp(ctx, 10)
        e.setPadding(px, py, px, py)
        e.maxLines = 1
        return e
    }

    /** Filament colour chip. Accepts RRGGBB or RRGGBBAA, with or without a #. */
    fun swatch(ctx: Context, rgba: String?, sizeDp: Int = 22): View {
        val v = View(ctx)
        v.layoutParams = lp(ctx, sizeDp, sizeDp)
        // Tags exist whose alpha byte is 00. Honouring that draws nothing at
        // all, and an invisible chip tells him less than the wrong colour would.
        val fill = parseColor(rgba)?.or(0xFF000000.toInt()) ?: insetColor(ctx)
        // A ring rather than a border, so a black spool still reads as a spool.
        v.background = rounded(fill, sizeDp / 2, ctx, strokeColor(ctx))
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

    /** "1h 24m" from a minute count; an em dash when there is nothing to say. */
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
        return if (goal > 0) "$now → $goal°" else now
    }

    fun percent(v: Double?): String = if (v == null) "—" else "${v.toInt()}%"

    /** The firmware's shouted state names, said the way a person would. */
    fun stateWord(state: String?): String = when (state) {
        "RUNNING" -> "Printing"
        "PAUSE" -> "Paused"
        "FINISH" -> "Finished"
        "FAILED" -> "Failed"
        "PREPARE" -> "Preparing"
        "SLICING" -> "Slicing"
        "IDLE", null -> "Idle"
        else -> state.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** Colour for an HMS severity: 1 fatal, 2 serious, 3 common, 4 info. */
    fun severity(ctx: Context, level: Int?): Int = when (level) {
        Hms.COMMON -> warn(ctx)
        Hms.INFO -> dimColor(ctx)
        // Fatal, serious, and anything unrecognised: assume it matters.
        else -> bad(ctx)
    }
}
