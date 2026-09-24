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
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/**
 * The filament inventory: what he has, what is left of it, and where it lives —
 * and, one segment over, what is running out and what is on order.
 */
class SpoolsFragment : BaseFragment() {

    private lateinit var views: LinearLayout
    private lateinit var list: LinearLayout
    private var spools: List<JSONObject> = emptyList()
    private var shopping: List<JSONObject> = emptyList()
    private var lowPercent = Spools.DEFAULT_LOW_PERCENT
    private var filter = ""
    private var view = ALL
    private var loaded = false
    private var loadError: Throwable? = null

    override fun build(ctx: Context) {
        screenAction("Reload") { load() }

        val top = Ui.row(ctx)
        // Short, because the four view segments now sit beside it: the old hint
        // wrapped to two lines and took the row with it.
        val search = Ui.input(ctx, "Search spools")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString().orEmpty()
                render()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        top.addView(search, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        Ui.gap(ctx, top, Ui.S)
        views = Ui.row(ctx)
        top.addView(views)
        content.addView(top, Ui.wide(ctx))
        content.addView(Ui.space(ctx, Ui.M))

        list = Ui.col(ctx)
        content.addView(list, Ui.wide(ctx))
        load()
    }

    /** One strip picks the view; the counts on it say whether a view is worth opening. */
    private fun buildViews(ctx: Context) {
        views.removeAllViews()
        val active = spools.filter { !Spools.isArchived(it) }
        val low = Spools.lowList(active, lowPercent).size
        val toBuy = shopping.count { it.str("status") != "received" }
        val labels = listOf(
            if (loaded && view != ARCHIVED) "All ${active.size}" else "All",
            if (low > 0) "Running low $low" else "Running low",
            if (toBuy > 0) "To buy $toBuy" else "To buy",
            "Archived"
        )
        views.addView(Ui.segmented(ctx, labels, view) { picked ->
            if (picked == view) return@segmented
            // Archived spools come from a different request; the other three share one.
            val refetch = picked == ARCHIVED || view == ARCHIVED
            view = picked
            if (refetch) load() else render()
        })
    }

    private fun load() {
        list.removeAllViews()
        context?.let { list.addView(waiting(it)) }
        loaded = false
        loadError = null
        val archived = view == ARCHIVED
        background({
            val all = Repo.api.spools(archived).objects()
            // The list and the threshold are extras: a server that will not give them
            // (an API key without those permissions) still shows its spools.
            val items = if (archived) shopping else try {
                Repo.api.shoppingList().objects()
            } catch (e: Exception) {
                emptyList()
            }
            val threshold = try {
                Repo.api.settings().dbl("low_stock_threshold") ?: Spools.DEFAULT_LOW_PERCENT
            } catch (e: Exception) {
                lowPercent
            }
            Triple(all, items, threshold)
        }) { result ->
            result.onSuccess { (all, items, threshold) ->
                spools = if (archived) all.filter { Spools.isArchived(it) } else all
                shopping = items
                lowPercent = threshold
                loaded = true
            }
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
        buildViews(ctx)
        list.removeAllViews()
        loadError?.let {
            list.addView(failed(ctx, it, "your spools") { load() })
            return
        }
        when (view) {
            LOW -> renderLow(ctx)
            TO_BUY -> renderShopping(ctx)
            else -> renderAll(ctx)
        }
    }

    private fun renderAll(ctx: Context) {
        val shown = spools.filter { Spools.matches(it, filter) }

        val head = Ui.row(ctx)
        head.addView(Ui.tiny(ctx, when {
            spools.isEmpty() -> ""
            shown.size == spools.size -> "${spools.size} spools"
            else -> "${shown.size} of ${spools.size} spools"
        }))
        Ui.push(ctx, head)
        if (view == ALL) head.addView(Ui.quiet(ctx, "Add from a slot") { addFromSlot(ctx) })
        list.addView(head, Ui.wide(ctx))
        list.addView(Ui.space(ctx, Ui.XS))

        if (shown.isEmpty()) {
            list.addView(when {
                spools.isNotEmpty() ->
                    empty(ctx, "Nothing matches that.", "Try part of a material, colour or brand.")
                view == ARCHIVED -> empty(ctx, "Nothing is archived.")
                else -> empty(ctx, "No spools yet.", "Scan a spool tag to add one.")
            })
            return
        }
        grid(ctx, shown.map { spool ->
            val trailing = if (Spools.isArchived(spool)) Ui.quiet(ctx, "Restore") { restore(spool) }
            else Ui.button(ctx, "Assign") { assign(ctx, spool) }
            row(ctx, spool, Spools.summary(spool), trailing) as View
        })
    }

    /** The spools under their threshold, emptiest first, each one tap from the shopping list. */
    private fun renderLow(ctx: Context) {
        val low = Spools.lowList(spools, lowPercent).filter { Spools.matches(it, filter) }
        list.addView(Ui.tiny(ctx, "Under ${Math.round(lowPercent)}% left, or under a spool's own limit."))
        list.addView(Ui.space(ctx, Ui.S))
        if (low.isEmpty()) {
            list.addView(empty(ctx, if (spools.isEmpty()) "No spools yet." else "Nothing is running low."))
            return
        }
        grid(ctx, low.map { spool ->
            val line = "${Spools.grams(Spools.gramsLeft(spool))} left · ${Math.round(Spools.percentLeft(spool))}%" +
                (spool.str("storage_location")?.let { " · $it" } ?: "")
            val trailing = if (Spools.onShoppingList(spool, shopping)) Ui.tiny(ctx, "On the list")
            else Ui.button(ctx, "Add to list") { addToShopping(spool) }
            row(ctx, spool, line, trailing) as View
        })
    }

    /** Bambuddy's shopping list, moved along as rolls are ordered and arrive. */
    private fun renderShopping(ctx: Context) {
        val terms = filter.trim().lowercase()
        val items = shopping.filter { terms.isBlank() || Spools.shoppingLine(it).lowercase().contains(terms) }
        if (items.isEmpty()) {
            list.addView(empty(ctx, if (shopping.isEmpty()) "The shopping list is empty. Add to it from Running low."
                else "Nothing matches that."))
            return
        }
        for (item in items) {
            val card = Ui.inset(ctx)
            val info = Ui.col(ctx)
            info.addView(Ui.body(ctx, Spools.shoppingLine(item)))
            val bits = listOfNotNull(
                Spools.shoppingStatusWord(item),
                Spools.shortDate(item.str("added_at")).takeIf { it.isNotBlank() }?.let { "added $it" },
                item.str("note")
            )
            info.addView(Ui.tiny(ctx, bits.joinToString(" · ")))
            card.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(Ui.quiet(ctx, "Remove") { removeShopping(item) })
            Spools.nextShoppingStep(item)?.let { (status, label) ->
                Ui.gap(ctx, card, Ui.XS)
                card.addView(Ui.button(ctx, label) { moveShopping(item, status) })
            }
            list.addView(card, Ui.wide(ctx))
            list.addView(Ui.space(ctx, 6))
        }
    }

    /**
     * Lays a view's rows out across the screen rather than down it.
     *
     * An inventory is the screen with the most rows in the app and the least on
     * each of them: one line of text across 640 dp with a hand's width of
     * nothing beside it. Two columns is the same row twice over.
     */
    private fun grid(ctx: Context, rows: List<View>) {
        val grid = Ui.grid(ctx, rows, columns(ctx))
        list.addView(grid, Ui.wide(ctx))
        Ui.arrive(grid)
    }

    private fun row(ctx: Context, spool: JSONObject, summary: String, trailing: View): LinearLayout {
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
        // Half the inventory is "Bambu Lab · …", so on a narrow row the brand
        // moves to the line underneath rather than eating the two words that
        // tell two spools apart.
        val narrow = columns(ctx) > 1
        val name = Ui.body(
            ctx, if (narrow) Assign.shortSpoolName(spool) else Assign.spoolName(spool)
        )
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(name)
        val brand = spool.str("brand").takeIf { narrow }
        val line2 = Ui.tiny(ctx, listOfNotNull(brand, summary).joinToString(" · "))
        line2.maxLines = 1
        line2.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(line2)
        line.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(trailing)
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

    // ------------------------------------------------------------ shopping

    private fun addToShopping(spool: JSONObject) {
        background({ Repo.api.addToShoppingList(Spools.shoppingItem(spool)) }) { result ->
            result.onSuccess {
                shopping = listOf(it) + shopping
                toast("On the shopping list")
                render()
            }
            result.onFailure { toast(it.message ?: "Could not add it to the list") }
        }
    }

    private fun moveShopping(item: JSONObject, status: String) {
        background({ Repo.api.setShoppingStatus(item.optInt("id"), status) }) { result ->
            result.onSuccess { updated ->
                shopping = shopping.map { if (it.optInt("id") == updated.optInt("id")) updated else it }
                render()
            }
            result.onFailure { toast(it.message ?: "Could not update the list") }
        }
    }

    private fun removeShopping(item: JSONObject) {
        background({ Repo.api.removeFromShoppingList(item.optInt("id")) }) { result ->
            result.onSuccess {
                shopping = shopping.filter { it.optInt("id") != item.optInt("id") }
                render()
            }
            result.onFailure { toast(it.message ?: "Could not remove it") }
        }
    }

    // ------------------------------------------------------------ from a slot

    /**
     * Makes an inventory spool out of whatever the AMS reports in a slot — for a spool
     * that was loaded without ever being scanned.
     */
    private fun addFromSlot(ctx: Context) {
        val printerId = Repo.selected.value
        if (printerId < 0) {
            toast("Choose a printer on the Printers screen first")
            return
        }
        val slots = Assign.slotsFor(printerId).filter { it.occupant != null }
        if (slots.isEmpty()) {
            toast("No slot on ${Repo.printerName(printerId)} is reporting a filament")
            return
        }
        val labels = slots.map { "${it.label}  —  ${it.occupant}" }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Which slot?")
            .setItems(labels) { _, which -> checkSlot(ctx, printerId, slots[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Looks for the slot's spool in the inventory before offering to add it. */
    private fun checkSlot(ctx: Context, printerId: Int, slot: Assign.Slot) {
        val tray = SlotSpool.tray(Repo.statuses.value[printerId], slot.amsId, slot.trayId)
        if (tray == null || !SlotSpool.hasFilament(tray)) {
            toast("${slot.label} is not reporting a filament")
            return
        }
        val tagged = SlotSpool.hasTag(tray)
        background({
            val byTag = if (tagged) Repo.api.spoolByTag(tray.str("tray_uuid"), tray.str("tag_uid")) else null
            byTag ?: SlotSpool.assignedSpool(Repo.api.assignments().objects(), printerId, slot.amsId, slot.trayId)
        }) { result ->
            result.onFailure { toast(it.message ?: "Could not check your inventory") }
            result.onSuccess { existing ->
                if (existing != null) alreadyThere(ctx, existing, slot)
                else confirmFromSlot(ctx, printerId, slot, tray, tagged)
            }
        }
    }

    private fun alreadyThere(ctx: Context, spool: JSONObject, slot: Assign.Slot) {
        val chipless = spool.str("tag_uid") == null && spool.str("tray_uuid") == null
        val builder = AlertDialog.Builder(ctx)
            .setTitle("Already in your inventory")
            .setMessage("${slot.label} holds ${Assign.spoolName(spool)}, which Bambuddy already has." +
                if (chipless) " It has no tag; a sticker would make it scannable." else "")
            .setNegativeButton("Close", null)
        if (chipless && !Spools.isArchived(spool)) {
            builder.setPositiveButton("Write a sticker") { _, _ -> StickerWrite.show(ctx, viewLifecycleOwner, spool) }
        }
        builder.show()
    }

    private fun confirmFromSlot(ctx: Context, printerId: Int, slot: Assign.Slot, tray: JSONObject, tagged: Boolean) {
        val what = SlotSpool.describe(tray)
        val message = if (tagged) {
            "Adds $what from its tag, the way Bambuddy adds a spool it recognises, and assigns it to ${slot.label}."
        } else {
            "The spool has no tag, so it is added from what the AMS says: $what. " +
                "It is assigned to ${slot.label}, and you can write a sticker for it next."
        }
        AlertDialog.Builder(ctx)
            .setTitle("Add ${slot.label} to your inventory?")
            .setMessage(message)
            .setPositiveButton("Add it") { _, _ -> createFromSlot(ctx, printerId, slot, tray, tagged) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createFromSlot(ctx: Context, printerId: Int, slot: Assign.Slot, tray: JSONObject, tagged: Boolean) {
        background({
            if (tagged) {
                Repo.api.spoolFromSlot(printerId, slot.amsId, slot.trayId)
            } else {
                val created = Repo.api.createSpool(SlotSpool.payload(tray))
                Repo.api.assign(created.optInt("id"), printerId, slot.amsId, slot.trayId)
                created
            }
        }) { result ->
            result.onFailure {
                toast(it.message ?: "Could not add the spool")
                // The spool may have been made before the assignment failed.
                load()
            }
            result.onSuccess { spool ->
                load()
                if (tagged) {
                    toast("Added and assigned to ${slot.label}")
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle("Added")
                        .setMessage("${Assign.spoolName(spool)} is in your inventory and assigned to " +
                            "${slot.label}. Write a sticker so it scans next time?")
                        .setPositiveButton("Write a sticker") { _, _ ->
                            StickerWrite.show(ctx, viewLifecycleOwner, spool)
                        }
                        .setNegativeButton("Not now", null)
                        .show()
                }
            }
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
        if (!Spools.isArchived(spool)) {
            more.addView(Ui.space(ctx, 1), Ui.lp(ctx, 6, 1))
            more.addView(Ui.button(ctx, "Write a sticker") {
                dialog?.dismiss()
                StickerWrite.show(ctx, viewLifecycleOwner, spool)
            })
        }
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

    companion object {
        private const val ALL = 0
        private const val LOW = 1
        private const val TO_BUY = 2
        private const val ARCHIVED = 3
    }
}
