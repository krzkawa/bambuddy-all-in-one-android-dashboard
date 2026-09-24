package io.github.krzkawa.bambuddyaio.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.R

/** A flat progress bar. Two weighted views, so it costs nothing to redraw. */
class Bar(ctx: Context, heightDp: Int = 5) {

    private val fill = View(ctx)
    private val rest = View(ctx)
    private var current = -1f
    private var animator: ValueAnimator? = null

    val view: LinearLayout = Ui.row(ctx).apply {
        background = Ui.rounded(Ui.color(ctx, R.color.card_alt), heightDp / 2, ctx)
        layoutParams = Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, heightDp)
        fill.background = Ui.rounded(Ui.accent(ctx), heightDp / 2, ctx)
        addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
        addView(rest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    fun set(fraction: Double, color: Int? = null) {
        val target = fraction.coerceIn(0.0, 1.0).toFloat()
        if (color != null) {
            fill.background = Ui.rounded(color, 3, view.context)
        }
        animator?.cancel()
        // The first value a bar is given is where the print already is, so it
        // is placed rather than travelled to. After that a poll moves it by a
        // per cent or so, and sliding says "this is still going" in a way a
        // number stepping never does.
        if (current < 0f || Math.abs(target - current) > 0.25f) {
            apply(target)
            return
        }
        animator = ValueAnimator.ofFloat(current, target).apply {
            duration = 400
            addUpdateListener { apply(it.animatedValue as Float) }
            start()
        }
    }

    private fun apply(f: Float) {
        current = f
        (fill.layoutParams as LinearLayout.LayoutParams).weight = f
        (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - f
        fill.requestLayout()
        rest.requestLayout()
    }
}
