package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.net.ApiError
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
        screenAction("Reload") { load() }
        list = Ui.col(ctx)
        content.addView(list, Ui.wide(ctx))
        load()
    }

    private fun load() {
        val ctx = context ?: return
        list.removeAllViews()
        list.addView(waiting(ctx))
        background({ Repo.api.queue() }) { result ->
            result.onSuccess { render(it.objects()) }
            result.onFailure { error ->
                list.removeAllViews()
                list.addView(failed(ctx, error, "the queue") { load() })
            }
        }
    }

    private fun render(items: List<JSONObject>) {
        val ctx = context ?: return
        list.removeAllViews()
        // Finished prints live on the History screen. This one answers "what is
        // the printer doing and what is it doing next", so a week of completed
        // jobs pushing that off the top is just noise.
        val shown = Queue.upcoming(items)
        if (shown.isEmpty()) {
            list.addView(
                empty(ctx, "Nothing waiting.", "Prints you have already run are on the History screen.")
            )
            return
        }

        val here = Ui.col(ctx)
        // Whatever is on the printer right now keeps the full width and sits
        // alone at the top: it is the one line on this screen he is actually
        // looking for, and a queue of eight should not hide it in a grid.
        val active = shown.filter { Queue.isActive(it) }
        val queued = shown.filterNot { Queue.isActive(it) }
        for (item in active) {
            here.addView(row(ctx, item, position = null), Ui.wide(ctx))
            here.addView(Ui.space(ctx, 6))
        }
        if (queued.isNotEmpty()) {
            if (active.isNotEmpty()) {
                here.addView(Ui.space(ctx, Ui.S))
                here.addView(Ui.heading(ctx, if (queued.size == 1) "Next" else "Waiting"))
            }
            // Two columns, but each row carries its place in the line, so
            // reading across rather than down never loses the order.
            val cards = queued.mapIndexed { index, item ->
                row(ctx, item, position = index + 1) as android.view.View
            }
            here.addView(Ui.grid(ctx, cards, columns(ctx)), Ui.wide(ctx))
        }
        list.addView(here, Ui.wide(ctx))
        Ui.arrive(here)
    }

    private fun row(ctx: Context, item: JSONObject, position: Int?): LinearLayout {
        val card = Ui.inset(ctx)
        card.background = Ui.rounded(Ui.cardColor(ctx), 10, ctx)
        // On half a screen the name shares its line with nothing: a file name
        // cut down to "hinge…4.3mf" to make room for two buttons is a row he
        // cannot read, and the point of the row is knowing what is in it.
        val narrow = position != null && columns(ctx) > 1
        val line = Ui.row(ctx)

        // A dot ahead of the name says which one is on the printer right now;
        // everything else is numbered by where it stands in the line.
        val active = Queue.isActive(item)
        if (position == null) {
            line.addView(Ui.dot(ctx, if (active) Ui.good(ctx) else Ui.faintColor(ctx)))
            Ui.gap(ctx, line, 10)
        } else {
            val place = Ui.tiny(ctx, position.toString())
            place.gravity = android.view.Gravity.CENTER
            line.addView(place, Ui.lp(ctx, 14, ViewGroup.LayoutParams.WRAP_CONTENT))
            Ui.gap(ctx, line, Ui.S)
        }

        val info = Ui.col(ctx)
        val name = Queue.itemName(item)
        val title = if (active) Ui.title(ctx, name) else Ui.body(ctx, name)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        info.addView(title)
        val bits = ArrayList<String>()
        item.str("status")?.let { bits.add(Ui.stateWord(it)) }
        item.str("printer_name")?.let { bits.add(it) }
        item.int("print_time_seconds")?.takeIf { it > 0 }?.let { bits.add(Ui.minutes(it / 60)) }
        item.dbl("filament_used_grams")?.takeIf { it > 0 }?.let { bits.add("${it.toInt()} g") }
        val meta = Ui.tiny(ctx, bits.joinToString(" · "))
        meta.maxLines = 1
        meta.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(meta)
        // The reason it is sitting there is the one thing on the row worth
        // colouring: it is usually the same check that will refuse a Start.
        Queue.waitingReason(item)?.let {
            val why = Ui.tiny(ctx, it)
            why.setTextColor(Ui.warn(ctx))
            why.maxLines = 1
            why.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(why)
        }
        line.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Where the row is narrow the buttons go under it instead of beside it,
        // on their own line with the meta.
        val buttons = if (narrow) Ui.row(ctx) else line
        if (narrow) Ui.push(ctx, buttons)

        buttons.addView(Ui.quiet(ctx, "Remove") {
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

        // Only a pending item can be started; the route answers 400 for
        // anything else, so an item already printing gets no button at all
        // rather than one that always fails.
        if (Queue.canStart(item)) {
            Ui.gap(ctx, buttons, Ui.XS)
            buttons.addView(Ui.button(ctx, "Start", primary = true) { confirmStart(ctx, item, name) })
        }
        // Ui.inset is a row, so a second line has to go inside a column of its
        // own rather than beside the first one.
        if (narrow) {
            val stack = Ui.col(ctx)
            stack.addView(line, Ui.wide(ctx))
            stack.addView(Ui.space(ctx, Ui.XS))
            stack.addView(buttons, Ui.wide(ctx))
            card.addView(stack, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            card.addView(line, Ui.wide(ctx))
        }
        return card
    }

    /** A print is a machine moving in another room, so it is asked for twice. */
    private fun confirmStart(ctx: Context, item: JSONObject, name: String) {
        val printer = item.str("printer_name")
            ?: item.int("printer_id")?.let { Repo.printerName(it) }
        AlertDialog.Builder(ctx)
            .setTitle("Start this print?")
            .setMessage(if (printer == null) name else "$name\n\nOn $printer")
            .setPositiveButton("Start") { _, _ -> start(ctx, item, name, skipFilamentCheck = false) }
            .setNegativeButton("Not yet", null)
            .show()
    }

    /**
     * Asks the server to start the item, and turns its one refusal worth
     * arguing with into a question.
     *
     * A 409 carrying a filament deficit means the assigned spools cannot cover
     * the job as far as the server can tell. That is often a spool it has the
     * wrong weight for, so it is his call, not a dead end: the same call with
     * the check skipped goes through, and the server remembers the decision so
     * its own scheduler does not re-block the item a moment later.
     */
    private fun start(ctx: Context, item: JSONObject, name: String, skipFilamentCheck: Boolean) {
        val id = item.optInt("id")
        background({ Repo.api.queueStart(id, skipFilamentCheck) }) { result ->
            result.onSuccess {
                Repo.notice("Started $name")
                toast("Starting $name")
                Repo.refresh()
                load()
            }
            result.onFailure { failure ->
                val shortfalls = (failure as? ApiError)?.let { Queue.shortfalls(it) }
                if (shortfalls == null) {
                    toast(failure.message ?: "Could not start it")
                } else {
                    askAboutFilament(ctx, item, name, shortfalls)
                }
            }
        }
    }

    private fun askAboutFilament(
        ctx: Context,
        item: JSONObject,
        name: String,
        shortfalls: List<Queue.Shortfall>
    ) {
        AlertDialog.Builder(ctx)
            .setTitle("Not enough filament")
            .setMessage(Queue.shortfallMessage(shortfalls) + "\n\nPrint it anyway?")
            .setPositiveButton("Print anyway") { _, _ ->
                start(ctx, item, name, skipFilamentCheck = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
