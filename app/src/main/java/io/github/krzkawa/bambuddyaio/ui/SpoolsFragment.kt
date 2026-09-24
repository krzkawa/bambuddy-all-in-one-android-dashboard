package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/** The filament inventory: what he has, what is left of it, and where it lives. */
class SpoolsFragment : BaseFragment() {

    private lateinit var list: LinearLayout
    private lateinit var archivedToggle: TextView
    private var spools: List<JSONObject> = emptyList()
    private var filter = ""
    private var showArchived = false
    private var loadError: Throwable? = null

    override fun build(ctx: Context) {
        screenAction("Reload") { load() }

        val top = Ui.row(ctx)
        val search = Ui.input(ctx, "Search by material, colour or brand")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString().orEmpty()
                render()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        top.addView(search, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 8, 1))
        archivedToggle = Ui.button(ctx, archivedLabel(), primary = showArchived) {
            showArchived = !showArchived
            archivedToggle.text = archivedLabel()
            load()
        }
        top.addView(archivedToggle)
        content.addView(top, Ui.wide(ctx))
        content.addView(Ui.space(ctx, Ui.M))

        list = Ui.col(ctx)
        content.addView(list, Ui.wide(ctx))
        load()
    }

    private fun archivedLabel() = if (showArchived) "Hide archived" else "Archived"

    private fun load() {
        list.removeAllViews()
        context?.let { list.addView(waiting(it)) }
        loadError = null
        val includeArchived = showArchived
        background({ Repo.api.spools(includeArchived) }) { result ->
            result.onSuccess { spools = it.objects() }
            result.onFailure {
                spools = emptyList()
                // A failure used to be a toast over an empty list, which is
                // gone in three seconds and leaves a screen that looks like an
                // empty inventory rather than a server that did not answer.
                loadError = it
            }
            render()
        }
    }

    private fun render() {
        val ctx = context ?: return
        list.removeAllViews()

        loadError?.let {
            list.addView(failed(ctx, it, "your spools") { load() })
            return
        }

        val shown = spools.filter { Spools.matches(it, filter) }

        if (shown.isEmpty()) {
            list.addView(
                if (spools.isEmpty()) empty(ctx, "No spools yet.", "Scan a spool tag to add one.")
                else empty(ctx, "Nothing matches that.", "Try part of a material, colour or brand.")
            )
            return
        }

        val count = Ui.tiny(ctx, if (shown.size == spools.size) "${spools.size} spools"
            else "${shown.size} of ${spools.size} spools")
        list.addView(count)
        list.addView(Ui.space(ctx, Ui.S))

        // Two columns of spools rather than one: an inventory is the screen
        // with the most rows in the app and the least on each of them.
        val grid = Ui.grid(ctx, shown.map { row(ctx, it) as View }, columns(ctx))
        list.addView(grid, Ui.wide(ctx))
        Ui.arrive(grid)
    }

    private fun row(ctx: Context, spool: JSONObject): LinearLayout {
        // A spool is a list row, not a panel: forty panels down a screen is
        // forty boxes and no list.
        val card = Ui.inset(ctx)
        card.background = Ui.pressable(
            ctx, Ui.rounded(Ui.cardColor(ctx), 10, ctx), Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.pressed), 10
        )
        val line = Ui.row(ctx)
        line.addView(Ui.swatch(ctx, spool.str("rgba"), 24))
        Ui.gap(ctx, line, Ui.M)

        val info = Ui.col(ctx)
        val narrow = columns(ctx) > 1
        val name = Ui.body(
            ctx, if (narrow) Assign.shortSpoolName(spool) else Assign.spoolName(spool)
        )
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(name)
        val brand = spool.str("brand").takeIf { narrow }
        val summary = Ui.tiny(
            ctx, listOfNotNull(brand, Spools.summary(spool)).joinToString(" · ")
        )
        summary.maxLines = 1
        summary.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(summary)
        line.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (Spools.isArchived(spool)) {
            line.addView(Ui.quiet(ctx, "Restore") { restore(spool) })
        } else {
            line.addView(Ui.button(ctx, "Assign") { assign(ctx, spool) })
        }
        card.addView(line, Ui.wide(ctx))
        // Tapping the card opens the spool. A separate edit button would cost
        // width this screen has none of, and the card is a far bigger target on
        // a phone this old; the button inside it still takes its own taps.
        card.isClickable = true
        card.setOnClickListener { openSpool(ctx, spool) }
        return card
    }

    private fun assign(ctx: Context, spool: JSONObject) {
        val printerId = Repo.selected.value
        if (printerId < 0) {
            toast("Choose a printer on the Printers screen first")
            return
        }
        Assign.pickSlot(ctx, printerId) { slot ->
            Assign.send(spool.optInt("id"), printerId, slot) { toast(it) }
        }
    }

    // ------------------------------------------------------------ one spool

    /** Everything you can do to a single spool, in one dialog. */
    private fun openSpool(ctx: Context, spool: JSONObject) {
        val id = spool.optInt("id")
        var chosenLocation: String? = spool.str("storage_location")
        // Declared up here because the buttons built below all need to close it,
        // and the dialog itself cannot exist until its content does.
        var dialog: AlertDialog? = null

        val root = Ui.col(ctx)
        val pad = Ui.dp(ctx, 12)
        root.setPadding(pad, pad, pad, 0)

        root.addView(Ui.dim(ctx, Spools.summary(spool)))
        root.addView(Ui.space(ctx, 10))

        val left = Ui.input(ctx, "Grams left", Math.round(Spools.gramsLeft(spool)).toString())
        left.inputType = InputType.TYPE_CLASS_NUMBER
        root.addView(labelled(ctx, "Grams left", left))

        val total = Ui.input(ctx, "Total weight", Math.round(Spools.labelWeight(spool)).toString())
        total.inputType = InputType.TYPE_CLASS_NUMBER
        root.addView(labelled(ctx, "Full spool weighs", total))

        if (Spools.weightLocked(spool)) {
            root.addView(Ui.tiny(ctx, "The AMS is no longer updating this weight."))
            root.addView(Ui.space(ctx, 4))
            root.addView(Ui.button(ctx, "Let the AMS update it again") {
                dialog?.dismiss()
                patch(id, JSONObject().put("weight_locked", false), "Back on AMS updates")
            })
        } else {
            root.addView(Ui.tiny(ctx, "Correcting grams left stops the AMS updating this spool."))
        }
        root.addView(Ui.space(ctx, 10))

        root.addView(Ui.heading(ctx, "Location"))
        val locationRow = Ui.row(ctx)
        val locationText = Ui.body(ctx, chosenLocation ?: "Not set")
        locationRow.addView(locationText, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        locationRow.addView(Ui.button(ctx, "Choose") {
            chooseLocation(ctx, chosenLocation) { picked ->
                chosenLocation = picked
                locationText.text = picked ?: "Not set"
            }
        })
        root.addView(locationRow, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Ui.space(ctx, 10))

        val note = Ui.input(ctx, "Note", spool.str("note").orEmpty())
        root.addView(labelled(ctx, "Note", note))
        root.addView(Ui.space(ctx, 12))

        val actions = Ui.row(ctx)
        val sheet = AlertDialog.Builder(ctx)
            .setTitle(Assign.spoolName(spool))
            .setView(Ui.scroll(ctx, root))
            .setNegativeButton("Close", null)
            .create()
        dialog = sheet

        actions.addView(Ui.button(ctx, "Save", primary = true) {
            val built = Spools.editPayload(
                spool,
                left.text.toString(),
                total.text.toString(),
                note.text.toString(),
                chosenLocation
            )
            val payload = built.getOrElse {
                toast(it.message ?: "That is not a number")
                return@button
            }
            if (payload == null) {
                toast("Nothing changed")
                return@button
            }
            dialog?.dismiss()
            patch(id, payload, "Spool saved")
        })
        actions.addView(Ui.space(ctx, 1), Ui.lp(ctx, 6, 1))
        actions.addView(Ui.button(ctx, "Usage") { showUsage(ctx, spool) })
        root.addView(actions, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Ui.space(ctx, 8))

        val more = Ui.row(ctx)
        if (Spools.isArchived(spool)) {
            more.addView(Ui.button(ctx, "Restore") {
                dialog?.dismiss()
                restore(spool)
            })
        } else {
            more.addView(Ui.button(ctx, "Archive") {
                dialog?.dismiss()
                confirmArchive(ctx, spool)
            })
        }
        more.addView(Ui.space(ctx, 1), Ui.lp(ctx, 6, 1))
        more.addView(Ui.button(ctx, "Zero consumed") {
            dialog?.dismiss()
            confirmZeroCounter(ctx, spool)
        })
        root.addView(more, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Ui.space(ctx, 8))
        root.addView(Ui.tiny(ctx, "Consumed so far: ${Spools.grams(Spools.consumed(spool))}"))

        sheet.show()
    }

    private fun labelled(ctx: Context, label: String, field: View): LinearLayout {
        val col = Ui.col(ctx)
        col.addView(Ui.heading(ctx, label))
        col.addView(field, Ui.wide(ctx))
        col.addView(Ui.space(ctx, Ui.M))
        return col
    }

    /** Offers the locations the server already knows, or a new one by name. */
    private fun chooseLocation(ctx: Context, current: String?, onPick: (String?) -> Unit) {
        background({ Repo.api.locations() }) { result ->
            val options = Spools.locationOptions(result.getOrNull().objects())
            val labels = (options.map { it.second } + listOf("Somewhere else…", "Clear")).toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle("Where is it kept?")
                .setItems(labels) { _, which ->
                    when (which) {
                        options.size -> typeLocation(ctx, current, onPick)
                        options.size + 1 -> onPick(null)
                        else -> onPick(options[which].first)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun typeLocation(ctx: Context, current: String?, onPick: (String?) -> Unit) {
        val field = Ui.input(ctx, "Drybox 2, top shelf, …", current.orEmpty())
        val wrap = Ui.col(ctx)
        val pad = Ui.dp(ctx, 16)
        wrap.setPadding(pad, pad, pad, 0)
        wrap.addView(
            field,
            Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        AlertDialog.Builder(ctx)
            .setTitle("New location")
            .setView(wrap)
            // The server creates a location it has not seen before, so a name
            // typed here does not have to exist yet.
            .setPositiveButton("Use it") { _, _ -> onPick(field.text.toString().trim().ifBlank { null }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUsage(ctx: Context, spool: JSONObject) {
        background({ Repo.api.spoolUsage(spool.optInt("id")) }) { result ->
            val entries = result.getOrNull().objects()
            val body = when {
                result.isFailure -> result.exceptionOrNull()?.message ?: "Could not load the usage history"
                entries.isEmpty() -> "Nothing has printed from this spool yet."
                else -> entries.joinToString("\n") { Spools.usageLine(it) }
            }
            AlertDialog.Builder(ctx)
                .setTitle("${Assign.spoolName(spool)} — usage")
                .setMessage(body)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun confirmArchive(ctx: Context, spool: JSONObject) {
        AlertDialog.Builder(ctx)
            .setTitle("Archive this spool?")
            .setMessage("It leaves the list but keeps its history, and you can restore it later.")
            .setPositiveButton("Archive") { _, _ ->
                background({ Repo.api.archiveSpool(spool.optInt("id")) }) { result ->
                    result.onSuccess { toast("Archived"); load() }
                    result.onFailure { toast(it.message ?: "Could not archive it") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restore(spool: JSONObject) {
        background({ Repo.api.restoreSpool(spool.optInt("id")) }) { result ->
            result.onSuccess { toast("Restored"); load() }
            result.onFailure { toast(it.message ?: "Could not restore it") }
        }
    }

    private fun confirmZeroCounter(ctx: Context, spool: JSONObject) {
        AlertDialog.Builder(ctx)
            .setTitle("Zero the consumed counter?")
            // Saying this plainly matters: the endpoint moves a baseline and
            // deliberately leaves remaining weight alone, which is not what
            // "reset usage" sounds like it would do.
            .setMessage("Only the running total is cleared. Grams left stays as it is.")
            .setPositiveButton("Zero it") { _, _ ->
                background({ Repo.api.resetConsumedCounter(spool.optInt("id")) }) { result ->
                    result.onSuccess { toast("Counter zeroed"); load() }
                    result.onFailure { toast(it.message ?: "Could not zero the counter") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun patch(spoolId: Int, payload: JSONObject, success: String) {
        background({ Repo.api.updateSpool(spoolId, payload) }) { result ->
            result.onSuccess { toast(success); load() }
            result.onFailure { toast(it.message ?: "Could not save the spool") }
        }
    }
}
