package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.nfc.NfcAdapter
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.nfc.BambuTag
import io.github.krzkawa.bambuddyaio.nfc.ScanFailure
import io.github.krzkawa.bambuddyaio.nfc.SpoolTag
import io.github.krzkawa.bambuddyaio.util.str
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Scan a spool's tag, then put it in a slot.
 *
 * The tag itself only identifies the filament; the assignment is Bambuddy's
 * record of which spool sits where, which is what the user asked for. Scanning
 * never writes to a tag; stickers are written from Spools, by [StickerWrite].
 */
class ScanFragment : BaseFragment() {

    private lateinit var body: LinearLayout
    private var lookedUpFor: String? = null
    private var matched: JSONObject? = null
    private var lookupFailed: String? = null
    private var hiccupTimer: Job? = null

    override fun build(ctx: Context) {
        body = Ui.col(ctx)
        content.addView(body, Ui.wide(ctx))

        observe(ScanState.tag) { tag ->
            if (tag != null && tag.tagUid != lookedUpFor) lookUp(tag)
            render()
        }
        observe(ScanState.busy) { render() }
        observe(ScanState.hiccup) { failed ->
            hiccupTimer?.cancel()
            // The banner has done its job once he has seen it; leaving it up would
            // sit on top of the scan it is there to protect.
            if (failed != null) {
                hiccupTimer = viewLifecycleOwner.lifecycleScope.launch {
                    delay(HICCUP_MS)
                    ScanState.dismissHiccup()
                }
            }
            render()
        }
        observe(Repo.selected) { render() }
        render()
    }

    private fun lookUp(tag: SpoolTag) {
        lookedUpFor = tag.tagUid
        matched = null
        lookupFailed = null
        background({ Repo.api.spoolByTag(tag.trayUuid, tag.tagUid) }) { result ->
            result.onSuccess { matched = it }
            result.onFailure { lookupFailed = it.message }
            render()
        }
    }

    private fun render() {
        val ctx = context ?: return
        body.removeAllViews()

        val tag = ScanState.tag.value
        if (ScanState.busy.value) {
            val card = Ui.card(ctx)
            card.addView(Ui.big(ctx, "Reading the tag"))
            card.addView(Ui.dim(ctx, "Hold it still for a moment."))
            body.addView(card, Ui.wide(ctx))
            return
        }
        if (tag == null) {
            body.addView(nfcStateCard(ctx))
            return
        }

        ScanState.hiccup.value?.let { failed ->
            body.addView(hiccupCard(ctx, failed, tag))
            body.addView(Ui.space(ctx, 8))
        }
        body.addView(tagCard(ctx, tag))
        body.addView(Ui.space(ctx, Ui.M))
        body.addView(matchCard(ctx, tag))
        body.addView(Ui.space(ctx, Ui.M))
        body.addView(Ui.quiet(ctx, "Scan another") {
            ScanState.clear()
            lookedUpFor = null
            matched = null
            render()
        })
    }

    /**
     * A read that was turned away, shown over the scan it was turned away for.
     *
     * The spool is still near the phone while he reaches for a slot button, so it gets
     * read again, often only halfway. That used to replace the slot buttons with an
     * error and cost him the scan; now it says what happened and leaves the scan alone.
     */
    private fun hiccupCard(ctx: Context, failed: SpoolTag, held: SpoolTag): LinearLayout {
        val card = Ui.card(ctx)
        card.addView(Ui.title(ctx, "Kept the scan below"))
        val line = Ui.body(ctx, failed.warning
            ?: "The tag was read again and gave up less that time, so the fuller scan is still shown.")
        line.setTextColor(Ui.warn(ctx))
        card.addView(line)
        if (failed.tagUid != held.tagUid) {
            card.addView(Ui.tiny(ctx, "That was a different tag. Tap \"Scan another\" first if you " +
                "meant to work with it."))
        }
        card.addView(Ui.space(ctx, 6))
        card.addView(Ui.button(ctx, "Dismiss") { ScanState.dismissHiccup() })
        return card
    }

    private fun nfcStateCard(ctx: Context): LinearLayout {
        val card = Ui.card(ctx)
        val adapter = NfcAdapter.getDefaultAdapter(ctx)
        val (headline, detail, colour) = when {
            adapter == null -> Triple(
                "This phone has no NFC",
                "Spools can still be assigned by hand from the AMS screen.",
                Ui.warn(ctx)
            )
            !adapter.isEnabled -> Triple(
                "NFC is switched off",
                "Turn it on in Android settings, then come back.",
                Ui.warn(ctx)
            )
            !BambuTag.phoneSupportsMifareClassic(ctx) -> Triple(
                "This phone cannot read Bambu tags",
                "Its NFC chip has no Mifare Classic support. OpenSpool tags still scan, " +
                    "and any tag can be linked to a spool by hand.",
                Ui.warn(ctx)
            )
            else -> Triple(
                "Hold a spool against the back of the phone",
                "Bambu tags sit in the cardboard core, near the rim.",
                Ui.good(ctx)
            )
        }
        val top = Ui.row(ctx)
        top.addView(Ui.dot(ctx, colour, 9))
        Ui.gap(ctx, top, 10)
        top.addView(Ui.big(ctx, headline))
        card.addView(top, Ui.wide(ctx))
        card.addView(Ui.space(ctx, Ui.XS))
        card.addView(Ui.dim(ctx, detail))
        return card
    }

    private fun tagCard(ctx: Context, tag: SpoolTag): LinearLayout {
        val card = Ui.card(ctx)

        val top = Ui.row(ctx)
        top.addView(Ui.swatch(ctx, tag.rgba, 34))
        if (tag.isDualColor) {
            top.addView(Ui.space(ctx, 1), Ui.lp(ctx, 3, 1))
            top.addView(Ui.swatch(ctx, tag.secondRgba, 34))
        }
        Ui.gap(ctx, top, Ui.M)
        val titles = Ui.col(ctx)
        titles.addView(Ui.big(ctx, tag.title))
        titles.addView(Ui.dim(ctx, tag.label))
        top.addView(titles)
        card.addView(top, Ui.wide(ctx))

        if (tag.warning != null) {
            card.addView(Ui.space(ctx, 6))
            val warning = Ui.body(ctx, tag.warning!!)
            warning.setTextColor(Ui.warn(ctx))
            card.addView(warning)
            when (tag.failure) {
                ScanFailure.TAG_LOST ->
                    card.addView(Ui.tiny(ctx, "Hold the spool still against the back of the phone and scan again."))
                ScanFailure.UNSUPPORTED_DEVICE ->
                    card.addView(Ui.tiny(ctx, "Nothing to retry — this phone's NFC chip cannot read these tags. Link the spool by hand below."))
                ScanFailure.AUTH_FAILED ->
                    card.addView(Ui.tiny(ctx, "Not a genuine Bambu tag. You can still link it to a spool below."))
                else -> {}
            }
        }

        card.addView(Ui.space(ctx, Ui.M))
        val facts = Ui.row(ctx)
        fun fact(label: String, value: String?) {
            if (value == null) return
            facts.addView(Ui.stat(ctx, label, value))
            Ui.gap(ctx, facts, Ui.XL)
        }
        fact("Brand", tag.brand)
        fact("Weight", tag.filamentWeightG?.let { "$it g" })
        fact("Nozzle", listOfNotNull(tag.nozzleTempMin, tag.nozzleTempMax).takeIf { it.size == 2 }
            ?.let { "${it[0]}–${it[1]}°" })
        fact("Bed", tag.bedTemp?.let { "$it°" })
        fact("Dry", tag.dryingTemp?.let { t -> tag.dryingHours?.let { "$t° / ${it}h" } ?: "$t°" })
        fact("Diameter", tag.diameterMm?.let { String.format("%.2f mm", it) })
        card.addView(facts, Ui.wide(ctx))

        card.addView(Ui.space(ctx, Ui.M))
        card.addView(Ui.tiny(ctx, "UID ${tag.tagUid}" + (tag.trayUuid?.let { "  ·  tray $it" } ?: "")))
        return card
    }

    private fun matchCard(ctx: Context, tag: SpoolTag): LinearLayout {
        val card = Ui.card(ctx)
        val printerId = Repo.selected.value
        val spool = matched

        if (lookupFailed != null) {
            card.addView(Ui.title(ctx, "Could not check your inventory"))
            card.addView(Ui.dim(ctx, lookupFailed!!))
            card.addView(Ui.space(ctx, 8))
            card.addView(Ui.button(ctx, "Try again") { lookUp(tag) })
            return card
        }

        if (spool == null) {
            card.addView(Ui.title(ctx, "Not in your inventory yet"))
            card.addView(Ui.dim(ctx, "Add it, or point this tag at a spool you already have."))
            card.addView(Ui.space(ctx, Ui.M))
            val row = Ui.row(ctx)
            row.addView(Ui.button(ctx, "Add to inventory", primary = true) { createSpool(tag) })
            Ui.gap(ctx, row, Ui.S)
            row.addView(Ui.button(ctx, "Link to a spool") { linkExisting(tag) })
            card.addView(row, Ui.wide(ctx))
            return card
        }

        card.addView(Ui.heading(ctx, "In your inventory"))
        if (!spool.isNull("archived_at")) {
            val archived = Ui.body(ctx, "This spool is archived in Bambuddy. It is still here, so " +
                "there is no need to add it again.")
            archived.setTextColor(Ui.warn(ctx))
            card.addView(archived)
            card.addView(Ui.space(ctx, 4))
        }
        val line = Ui.row(ctx)
        line.addView(Ui.swatch(ctx, spool.str("rgba"), 22))
        line.addView(Ui.space(ctx, 1), Ui.lp(ctx, 8, 1))
        val details = Ui.col(ctx)
        details.addView(Ui.title(ctx, Assign.spoolName(spool)))
        details.addView(Ui.tiny(ctx, Assign.spoolRemaining(spool)))
        line.addView(details)
        card.addView(line, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(Ui.space(ctx, Ui.M))
        if (printerId < 0) {
            card.addView(Ui.dim(ctx, "Choose a printer on the Printers screen first."))
            return card
        }

        card.addView(Ui.divider(ctx))
        card.addView(Ui.space(ctx, Ui.M))
        card.addView(Ui.heading(ctx, "Put it in a slot on ${Repo.printerName(printerId)}"))

        val slots = Assign.slotsFor(printerId)
        if (slots.isEmpty()) {
            card.addView(Ui.dim(ctx, "That printer is not reporting any AMS slots."))
            return card
        }

        // Slots are laid out as buttons rather than hidden behind a dialog: the
        // whole point of the scan is to land on a slot in one tap.
        var row = Ui.row(ctx)
        slots.forEachIndexed { index, slot ->
            if (index > 0 && index % 4 == 0) {
                card.addView(row, Ui.wide(ctx))
                card.addView(Ui.space(ctx, 6))
                row = Ui.row(ctx)
            }
            val label = if (slot.occupant == null) slot.label else "${slot.label}\n${slot.occupant}"
            row.addView(Ui.button(ctx, label) {
                confirmAssign(ctx, spool, printerId, slot)
            })
            Ui.gap(ctx, row, 6)
        }
        card.addView(row, Ui.wide(ctx))
        return card
    }

    private fun confirmAssign(ctx: Context, spool: JSONObject, printerId: Int, slot: Assign.Slot) {
        val occupied = slot.occupant
        if (occupied == null) {
            Assign.send(spool.optInt("id"), printerId, slot) { toast(it) }
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("Replace what is in ${slot.label}?")
            .setMessage("That slot currently holds $occupied.")
            .setPositiveButton("Assign") { _, _ ->
                Assign.send(spool.optInt("id"), printerId, slot) { toast(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Creates an inventory entry straight from what the tag said. */
    private fun createSpool(tag: SpoolTag) {
        background({ Repo.api.createSpool(SpoolPayload.from(tag)) }) { result ->
            result.onSuccess {
                matched = it
                toast("Added to your inventory")
                render()
            }
            result.onFailure { toast(it.message ?: "Could not add the spool") }
        }
    }

    /** Points this tag at a spool the user already has. */
    private fun linkExisting(tag: SpoolTag) {
        pickSpool("Which spool is this?") { chosen ->
            background({ Repo.api.linkTag(chosen.optInt("id"), tag.tagUid, tag.trayUuid) }) { linked ->
                linked.onSuccess {
                    matched = it
                    toast("Tag linked")
                    render()
                }
                linked.onFailure { toast(it.message ?: "Could not link the tag") }
            }
        }
    }

    companion object {
        /** How long a turned-away read stays on screen before it clears itself. */
        private const val HICCUP_MS = 8_000L
    }
}
