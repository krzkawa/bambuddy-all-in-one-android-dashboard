package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.R

/** A flat progress bar. Two weighted views, so it costs nothing to redraw. */
class Bar(ctx: Context, heightDp: Int = 6) {

    private val fill = View(ctx)
    private val rest = View(ctx)

    val view: LinearLayout = Ui.row(ctx).apply {
        background = Ui.rounded(Ui.color(ctx, R.color.card_alt), heightDp / 2, ctx)
        layoutParams = Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, heightDp)
        fill.background = Ui.rounded(Ui.accent(ctx), heightDp / 2, ctx)
        addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
        addView(rest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    fun set(fraction: Double, color: Int? = null) {
        val f = fraction.coerceIn(0.0, 1.0).toFloat()
        (fill.layoutParams as LinearLayout.LayoutParams).weight = f
        (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - f
        if (color != null) {
            fill.background = Ui.rounded(color, 3, view.context)
        }
        fill.requestLayout()
        rest.requestLayout()
    }
}
