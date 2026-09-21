package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Choosing a spool out of the inventory.
 *
 * A plain `setItems` list of forty near-identical labels is unusable on a small
 * phone, so this is the Spools screen's search box in a dialog: type a word or
 * two, tap the spool.
 */
object SpoolPicker {

    fun show(ctx: Context, title: String, spools: List<JSONObject>, onPick: (JSONObject) -> Unit) {
        val root = Ui.col(ctx)
        val pad = Ui.dp(ctx, 12)
        root.setPadding(pad, pad, pad, 0)

        val search = Ui.input(ctx, "Search by material, colour or brand")
        root.addView(
            search,
            Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(Ui.space(ctx, 8))

        val rows = Ui.col(ctx)
        val scroller = Ui.scroll(ctx, rows)
        // Sized against the screen rather than a fixed dp: this phone spends its
        // life in landscape, where a dialog has very little height to give.
        val maxHeight = (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt()
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight))

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(root)
            .setNegativeButton("Cancel", null)
            .create()

        fun render(query: String) {
            rows.removeAllViews()
            val shown = spools.filter { Spools.matches(it, query) }
            if (shown.isEmpty()) {
                rows.addView(Ui.dim(ctx, "Nothing matches that."))
                return
            }
            for (spool in shown) {
                rows.addView(row(ctx, spool) {
                    dialog.dismiss()
                    onPick(spool)
                })
                rows.addView(Ui.space(ctx, 6))
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        render("")
        dialog.show()
    }

    private fun row(ctx: Context, spool: JSONObject, onClick: () -> Unit): LinearLayout {
        val card = Ui.card(ctx)
        val line = Ui.row(ctx)
        line.addView(Ui.swatch(ctx, spool.optString("rgba"), 22))
        line.addView(Ui.space(ctx, 1), Ui.lp(ctx, 10, 1))

        val info = Ui.col(ctx)
        info.addView(Ui.body(ctx, Assign.spoolName(spool)))
        info.addView(Ui.tiny(ctx, Spools.summary(spool)))
        line.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(
            line,
            Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        card.isClickable = true
        card.setOnClickListener { onClick() }
        return card
    }
}

/**
 * Loads the inventory, then offers it through [SpoolPicker].
 *
 * An extension rather than a method on BaseFragment so the two screens that
 * choose a spool — the AMS slots and a fresh scan — each stay a single call.
 */
fun Fragment.pickSpool(title: String, onPick: (JSONObject) -> Unit) {
    val ctx = context ?: return
    Ui.toast(ctx, "Loading spools…")
    viewLifecycleOwner.lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { Repo.api.spools() } }
        if (!isAdded) return@launch
        val spools = result.getOrNull().objects()
        if (spools.isEmpty()) {
            Ui.toast(ctx, result.exceptionOrNull()?.message ?: "No spools in your inventory yet")
            return@launch
        }
        SpoolPicker.show(ctx, title, spools, onPick)
    }
}
