package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/** The filament inventory, and a way to put any of it in a slot. */
class SpoolsFragment : BaseFragment() {

    private lateinit var list: LinearLayout
    private var spools: List<JSONObject> = emptyList()
    private var filter = ""

    override fun build(ctx: Context) {
        content.addView(header(ctx, "Spools"))

        val top = Ui.row(ctx)
        val search = Ui.input(ctx, "Search by material, colour or brand")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString()?.trim()?.lowercase().orEmpty()
                render()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        top.addView(search, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 8, 1))
        top.addView(Ui.button(ctx, "Reload") { load() })
        content.addView(top, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Ui.space(ctx, 10))

        list = Ui.col(ctx)
        content.addView(list, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        load()
    }

    private fun load() {
        list.removeAllViews()
        context?.let { list.addView(Ui.dim(it, "Loading…")) }
        background({ Repo.api.spools() }) { result ->
            result.onSuccess { spools = it.objects() }
            result.onFailure {
                spools = emptyList()
                toast(it.message ?: "Could not load your spools")
            }
            render()
        }
    }

    private fun render() {
        val ctx = context ?: return
        list.removeAllViews()

        val shown = spools.filter { spool ->
            filter.isBlank() || Assign.spoolName(spool).lowercase().contains(filter)
        }

        if (shown.isEmpty()) {
            list.addView(Ui.dim(ctx, if (spools.isEmpty()) "No spools yet." else "Nothing matches that."))
            return
        }

        list.addView(Ui.dim(ctx, "${shown.size} of ${spools.size} spools"))
        list.addView(Ui.space(ctx, 8))

        for (spool in shown) {
            list.addView(row(ctx, spool))
            list.addView(Ui.space(ctx, 6))
        }
    }

    private fun row(ctx: Context, spool: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        val line = Ui.row(ctx)
        line.addView(Ui.swatch(ctx, spool.str("rgba"), 26))
        line.addView(Ui.space(ctx, 1), Ui.lp(ctx, 10, 1))

        val info = Ui.col(ctx)
        info.addView(Ui.body(ctx, Assign.spoolName(spool)))
        val bits = ArrayList<String>()
        bits.add(Assign.spoolRemaining(spool))
        if (spool.str("tray_uuid") != null || spool.str("tag_uid") != null) bits.add("tagged")
        spool.str("storage_location")?.let { bits.add(it) }
        info.addView(Ui.tiny(ctx, bits.joinToString(" · ")))
        line.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        line.addView(Ui.button(ctx, "Assign") {
            val printerId = Repo.selected.value
            if (printerId < 0) {
                toast("Choose a printer on the Printers screen first")
            } else {
                Assign.pickSlot(ctx, printerId) { slot ->
                    Assign.send(spool.optInt("id"), printerId, slot) { toast(it) }
                }
            }
        })
        card.addView(line, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }
}
