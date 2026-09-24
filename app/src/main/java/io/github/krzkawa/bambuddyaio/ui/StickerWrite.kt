package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.nfc.Tag
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.nfc.OpenSpool
import io.github.krzkawa.bambuddyaio.nfc.TagWriter
import io.github.krzkawa.bambuddyaio.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Writing an OpenSpool sticker for a spool that came without a chip.
 *
 * The NFC reader normally turns every tag into a scan. While a write is armed here,
 * [MainActivity] hands the next tag to [handle] instead, and exactly one tag is
 * written: the request is taken as the tag arrives, so a sticker brushed twice is not
 * written twice and a spool held up afterwards is scanned as usual.
 */
object StickerWrite {

    data class Request(val spoolId: Int, val spoolName: String, val json: String)

    sealed class Phase {
        object Idle : Phase()
        object Waiting : Phase()
        object Writing : Phase()
        /** [linkError] is null when Bambuddy took the link, or why it did not. */
        data class Done(val outcome: TagWriter.Outcome, val spoolName: String, val linkError: String?) : Phase()
    }

    private val request = AtomicReference<Request?>(null)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** True while the next tag should be written rather than read. */
    val armed: Boolean get() = request.get() != null

    fun arm(r: Request) {
        request.set(r)
        _phase.value = Phase.Waiting
    }

    fun cancel() {
        request.set(null)
        _phase.value = Phase.Idle
    }

    /**
     * Writes the armed record to [tag], then links the tag to the spool in Bambuddy so a
     * scan of the sticker finds it. Called for a tag the NFC stack delivered.
     */
    suspend fun handle(tag: Tag, context: Context) {
        val r = request.getAndSet(null) ?: return
        _phase.value = Phase.Writing
        val outcome = withContext(Dispatchers.IO) { TagWriter.write(tag, r.json) }
        var linkError: String? = null
        if (outcome.written) {
            linkError = withContext(Dispatchers.IO) {
                try {
                    Repo.api.linkWrittenTag(r.spoolId, outcome.tagUid)
                    null
                } catch (e: Exception) {
                    e.message ?: "Could not reach Bambuddy"
                }
            }
        }
        withContext(Dispatchers.Main) {
            ScanFeedback.buzz(context, outcome.written && linkError == null)
            _phase.value = Phase.Done(outcome, r.spoolName, linkError)
        }
    }

    // ------------------------------------------------------------- the sheet

    /**
     * Shows what is about to be written, lets him correct it, then waits for a sticker.
     *
     * Nothing touches a tag until he presses Write, and the record on screen is the
     * record that goes out, byte for byte.
     */
    fun show(ctx: Context, owner: LifecycleOwner, spool: JSONObject) {
        val start = Spools.stickerRecord(spool)
        val name = Assign.spoolName(spool)

        val root = Ui.col(ctx)
        val pad = Ui.dp(ctx, Ui.M)
        root.setPadding(pad, pad, pad, 0)

        // The spool and the one action, beside it: a dialog on a 360 dp tall screen has
        // no room for a button row under the form, and the keyboard takes half of it.
        val top = Ui.row(ctx)
        top.addView(Ui.swatch(ctx, start.colorHex, 28))
        Ui.gap(ctx, top, Ui.M)
        val titles = Ui.col(ctx)
        titles.addView(Ui.title(ctx, "Sticker for $name"))
        titles.addView(Ui.tiny(ctx, "Colour #${start.colorHex}, from the spool"))
        top.addView(titles, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actions = Ui.row(ctx)
        top.addView(actions)
        root.addView(top, Ui.wide(ctx))
        root.addView(Ui.space(ctx, Ui.M))

        // What goes on the sticker, editable.
        val preview = Ui.col(ctx)

        val brand = Ui.input(ctx, "Brand", start.brand)
        val type = Ui.input(ctx, "Material", start.type)
        val min = Ui.input(ctx, "Min °C", start.minTemp?.toString().orEmpty())
        val max = Ui.input(ctx, "Max °C", start.maxTemp?.toString().orEmpty())
        min.inputType = InputType.TYPE_CLASS_NUMBER
        max.inputType = InputType.TYPE_CLASS_NUMBER
        val fields = Ui.row(ctx)
        fun field(label: String, input: EditText, weight: Float) {
            val c = Ui.col(ctx)
            c.addView(Ui.heading(ctx, label))
            c.addView(input, Ui.wide(ctx))
            fields.addView(c, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
        }
        field("Brand", brand, 1.4f)
        Ui.gap(ctx, fields, Ui.S)
        field("Material", type, 1.2f)
        Ui.gap(ctx, fields, Ui.S)
        field("Nozzle min", min, 1f)
        Ui.gap(ctx, fields, Ui.S)
        field("Nozzle max", max, 1f)
        preview.addView(fields, Ui.wide(ctx))
        preview.addView(Ui.space(ctx, Ui.M))

        spool.str("tag_uid")?.let {
            val linked = Ui.body(ctx, "This spool is linked to tag $it now. The sticker takes over that link.")
            linked.setTextColor(Ui.warn(ctx))
            preview.addView(linked)
            preview.addView(Ui.space(ctx, Ui.S))
        }

        preview.addView(Ui.heading(ctx, "What gets written"))
        val json = Ui.dim(ctx, "")
        json.setTextIsSelectable(true)
        preview.addView(json)
        val size = Ui.tiny(ctx, "")
        size.setPadding(0, Ui.dp(ctx, Ui.XS), 0, 0)
        preview.addView(size)
        root.addView(preview, Ui.wide(ctx))

        // Waiting for, writing to, and done with a sticker.
        val status = Ui.col(ctx)
        val headline = Ui.big(ctx, "")
        val detail = Ui.dim(ctx, "")
        status.addView(headline)
        status.addView(Ui.space(ctx, Ui.XS))
        status.addView(detail)
        root.addView(status, Ui.wide(ctx))
        root.addView(Ui.space(ctx, Ui.M))

        fun record() = OpenSpool.Record(
            type = type.text.toString().trim().ifBlank { start.type }.take(24),
            colorHex = start.colorHex,
            brand = brand.text.toString().trim().ifBlank { "Generic" }.take(32),
            minTemp = min.text.toString().trim().toIntOrNull()?.takeIf { it > 0 },
            maxTemp = max.text.toString().trim().toIntOrNull()?.takeIf { it > 0 }
        )

        fun refreshPreview() {
            val encoded = OpenSpool.encode(record())
            json.text = encoded
            val bytes = OpenSpool.messageSize(encoded)
            size.text = if (bytes <= OpenSpool.NTAG213_BYTES) "$bytes bytes. Fits any NTAG sticker."
            else "$bytes bytes. Needs an NTAG215 or 216; an NTAG213 is too small."
        }
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshPreview()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(brand, type, min, max).forEach { it.addTextChangedListener(watcher) }
        refreshPreview()

        // Nothing is focused until he taps a field, so the keyboard does not open over
        // the record the moment the sheet appears.
        root.isFocusableInTouchMode = true
        root.requestFocus()

        val dialog = AlertDialog.Builder(ctx)
            .setView(Ui.scroll(ctx, root))
            .setNegativeButton("Close", null)
            .create()

        fun armWrite() {
            val r = record()
            if (r.minTemp != null && r.maxTemp != null && r.minTemp > r.maxTemp) {
                Ui.toast(ctx, "The lowest nozzle temperature is above the highest")
                return
            }
            this.arm(Request(spool.optInt("id"), name, OpenSpool.encode(r)))
        }

        fun render(phase: Phase) {
            actions.removeAllViews()
            preview.visibility = if (phase is Phase.Idle) View.VISIBLE else View.GONE
            status.visibility = if (phase is Phase.Idle) View.GONE else View.VISIBLE
            headline.setTextColor(Ui.textColor(ctx))
            when (phase) {
                is Phase.Idle -> {
                    actions.addView(Ui.button(ctx, "Write", primary = true) { armWrite() })
                }
                is Phase.Waiting -> {
                    headline.text = "Hold the sticker against the back of the phone"
                    detail.text = "A blank NTAG215 or 216. A Bambu tag, or a tag holding anything " +
                        "else, is left alone."
                    actions.addView(Ui.quiet(ctx, "Cancel") { cancel() })
                }
                is Phase.Writing -> {
                    headline.text = "Writing"
                    detail.text = "Hold it still for a moment."
                }
                is Phase.Done -> {
                    val o = phase.outcome
                    when {
                        !o.written -> {
                            headline.text = "Nothing was written"
                            headline.setTextColor(Ui.warn(ctx))
                            detail.text = o.message
                            actions.addView(Ui.button(ctx, "Try again", primary = true) { armWrite() })
                            Ui.gap(ctx, actions, Ui.S)
                            actions.addView(Ui.quiet(ctx, "Change it") { cancel() })
                        }
                        phase.linkError != null -> {
                            headline.text = "Written, but not linked"
                            headline.setTextColor(Ui.warn(ctx))
                            detail.text = "${o.message} Bambuddy did not take the link: ${phase.linkError}. " +
                                "Scan the sticker and link it from the Scan screen."
                        }
                        else -> {
                            headline.text = "Sticker ready"
                            headline.setTextColor(Ui.good(ctx))
                            detail.text = "${o.message} Scanning it now finds ${phase.spoolName}. " +
                                "Tag ${o.tagUid}."
                        }
                    }
                }
            }
        }

        val job = owner.lifecycleScope.launch { phase.collect { render(it) } }
        dialog.setOnDismissListener {
            job.cancel()
            cancel()
        }
        cancel()
        dialog.show()
    }
}
