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

/** What is lined up to print next. */
class QueueFragment : BaseFragment() {

    private lateinit var list: LinearLayout

    override fun build(ctx: Context) {
        val head = Ui.row(ctx)
        head.addView(Ui.big(ctx, "Queue"))
        head.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
        head.addView(Ui.button(ctx, "Reload") { load() })
        content.addView(head, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Ui.space(ctx, 10))

        list = Ui.col(ctx)
        content.addView(list, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        load()
    }

    private fun load() {
        val ctx = context ?: return
        list.removeAllViews()
        list.addView(Ui.dim(ctx, "Loading…"))
        background({ Repo.api.queue() }) { result ->
            result.onSuccess { render(it.objects()) }
            result.onFailure {
                list.removeAllViews()
                list.addView(Ui.dim(ctx, it.message ?: "Could not load the queue"))
            }
        }
    }

    private fun render(items: List<JSONObject>) {
        val ctx = context ?: return
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(Ui.dim(ctx, "Nothing queued."))
            return
        }
        for (item in items) {
            list.addView(row(ctx, item))
            list.addView(Ui.space(ctx, 6))
        }
    }

    private fun row(ctx: Context, item: JSONObject): LinearLayout {
        val card = Ui.card(ctx)
        val line = Ui.row(ctx)

        val info = Ui.col(ctx)
        val name = item.str("archive_name") ?: item.str("library_file_name") ?: "Queued print"
        info.addView(Ui.body(ctx, name))
        val bits = ArrayList<String>()
        item.str("status")?.let { bits.add(it) }
        item.str("printer_name")?.let { bits.add(it) }
        item.int("print_time_seconds")?.takeIf { it > 0 }?.let { bits.add(Ui.minutes(it / 60)) }
        item.dbl("filament_used_grams")?.takeIf { it > 0 }?.let { bits.add("${it.toInt()} g") }
        info.addView(Ui.tiny(ctx, bits.joinToString(" · ")))
        line.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        line.addView(Ui.button(ctx, "Remove") {
            AlertDialog.Builder(ctx)
                .setTitle("Remove from the queue?")
                .setMessage(name)
                .setPositiveButton("Remove") { _, _ ->
                    background({ Repo.api.queueRemove(item.optInt("id")) }) { result ->
                        result.onSuccess { toast("Removed"); load() }
                        result.onFailure { toast(it.message ?: "Could not remove it") }
                    }
                }
                .setNegativeButton("Keep", null)
                .show()
        })
        card.addView(line, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }
}
