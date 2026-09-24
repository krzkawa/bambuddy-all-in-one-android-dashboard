package io.github.krzkawa.bambuddyaio.ui

import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * "Print on…": everything between choosing a file and a printer moving.
 *
 * One screen, read top to bottom the way the decision is made: which plate,
 * which printer, which spool feeds each filament, a few print options, and
 * when. Nothing is sent until he has picked the printer himself and said yes
 * to a summary of all of it — this is the one thing in the app that moves a
 * machine that may be in another room.
 *
 * Used for a library file and for printing an old job again from History; the
 * only difference is which id goes in the queue item.
 */
class PrintSheetFragment : BaseFragment() {

    private lateinit var side: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var facts: android.widget.TextView
    private lateinit var goSlot: LinearLayout

    private var isArchive = false
    private var sourceId = 0
    private var name = ""
    private var back = PrintFlow.TO_QUEUE
    private var slicedFor: String? = null

    private var plates: List<PrintPlan.Plate> = emptyList()
    private var plate: Int? = null
    private var needs: List<PrintPlan.Need>? = null
    private var needsFailed: String? = null
    private var noGcode = false

    private var printerId: Int? = null
    private val manual = HashMap<Int, Int>()
    private var bedLevelling = "auto"
    private var flowCali = "auto"
    private var timelapse = false
    private var whenToPrint = PrintPlan.When.NOW
    private var at: Instant? = null
    private var sending = false
    private var optionsOpen = false

    /** The trays last drawn, so a status poll that changes nothing redraws nothing. */
    private var traySignature = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        side = Ui.col(ctx)
        val scroller = super.onCreateView(inflater, container, savedInstanceState)
        val root = Ui.row(ctx)
        root.gravity = Gravity.TOP
        root.setBackgroundColor(Ui.bg(ctx))
        side.setPadding(Ui.dp(ctx, Ui.M), Ui.dp(ctx, Ui.XS), 0, Ui.dp(ctx, Ui.M))
        root.addView(side, Ui.lp(ctx, SIDE_DP, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(scroller, Ui.lp(ctx, 0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    override fun build(ctx: Context) {
        val args = arguments ?: Bundle()
        isArchive = args.getString(ARG_KIND) == KIND_ARCHIVE
        sourceId = args.getInt(ARG_ID)
        name = args.getString(ARG_NAME) ?: "Print"
        back = args.getString(PrintFlow.ARG_BACK) ?: PrintFlow.TO_QUEUE
        slicedFor = args.getString(ARG_SLICED_FOR)
        plate = args.getInt(ARG_PLATE, -1).takeIf { it > 0 }

        // With one printer there is nothing to choose; with more, none is
        // chosen for him, so a print cannot land on whichever was selected
        // elsewhere in the app by accident.
        val printers = Repo.printers.value.filter { it.optInt("id", -1) >= 0 }
        if (printers.size == 1) printerId = printers[0].optInt("id")

        PrintFlow.handleBack(this) { back }

        // The side column stays put while the choices beside it scroll, so
        // the picture and the button that sends it are always in view.
        side.addView(Ui.quiet(ctx, "‹  " + if (isArchive) "Back" else "Files") {
            PrintFlow.open(this, PrintFlow.screenFor(back))
        }, Ui.lp(ctx, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        side.addView(Ui.space(ctx, Ui.XS))
        thumb = ImageView(ctx)
        thumb.scaleType = ImageView.ScaleType.FIT_CENTER
        thumb.background = Ui.rounded(Ui.cardColor(ctx), 12, ctx)
        val pad = Ui.dp(ctx, Ui.S)
        thumb.setPadding(pad, pad, pad, pad)
        side.addView(thumb, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, THUMB_DP))
        side.addView(Ui.space(ctx, Ui.S))
        facts = Ui.dim(ctx, "")
        side.addView(facts, Ui.wide(ctx))
        side.addView(Ui.space(ctx, Ui.M))
        goSlot = Ui.col(ctx)
        side.addView(goSlot, Ui.wide(ctx))

        body = Ui.col(ctx)
        content.addView(body, Ui.wide(ctx))

        showThumb()
        render()
        loadPlates()

        observe(Repo.statuses) {
            val sig = printerId?.let { id ->
                PrintPlan.trays(it[id]).joinToString { t -> "${t.globalId}${t.type}${t.color}" } +
                    PrintPlan.readiness(it[id])
            }.orEmpty()
            if (sig != traySignature) {
                traySignature = sig
                render()
            }
        }
    }

    // ------------------------------------------------------------ loading

    private fun loadPlates() {
        background({
            if (isArchive) Repo.api.archivePlates(sourceId) else Repo.api.libraryPlates(sourceId)
        }) { result ->
            result.onSuccess { response ->
                plates = PrintPlan.plates(response)
                // An archive says outright when it kept no G-code; queueing one
                // would only fail at upload with a less useful message.
                noGcode = isArchive && response.has("has_gcode") && !response.optBoolean("has_gcode")
                if (plate == null || plates.none { it.index == plate }) plate = plates.firstOrNull()?.index
                showThumb()
                loadNeeds()
            }
            // Without the plate list the print can still go: plate 1, and the
            // server works out the filaments. Say nothing and carry on.
            result.onFailure { loadNeeds() }
        }
    }

    private fun loadNeeds() {
        needs = null
        needsFailed = null
        render()
        val chosen = plate
        background({
            if (isArchive) Repo.api.archiveFilaments(sourceId, chosen)
            else Repo.api.libraryFilaments(sourceId, chosen)
        }) { result ->
            result.onSuccess { needs = PrintPlan.needs(it) }
            result.onFailure {
                needs = emptyList()
                needsFailed = it.message
            }
            render()
        }
    }

    private fun showThumb() {
        val p = plates.firstOrNull { it.index == plate }
        val id = sourceId
        when {
            p != null && p.hasThumbnail && isArchive ->
                Thumbs.load(thumb, "arcplate:$id:${p.index}", THUMB_DP) { Repo.api.archivePlateThumbUrl(id, p.index, it) }
            p != null && p.hasThumbnail ->
                Thumbs.load(thumb, "libplate:$id:${p.index}", THUMB_DP) { Repo.api.libraryPlateThumbUrl(id, p.index, it) }
            isArchive -> Thumbs.load(thumb, "arc:$id", THUMB_DP) { Repo.api.archiveThumbUrl(id, it) }
            else -> Thumbs.load(thumb, "lib:$id", THUMB_DP) { Repo.api.libraryThumbUrl(id, it) }
        }
    }

    // ----------------------------------------------------------- drawing

    private fun printers(): List<JSONObject> = Repo.printers.value.filter { it.optInt("id", -1) >= 0 }

    private fun printerLabel(p: JSONObject): String =
        p.optString("name").ifBlank { "Printer ${p.optInt("id")}" }

    private fun chosenPrinter(): JSONObject? = printerId?.let { id -> printers().firstOrNull { it.optInt("id") == id } }

    private fun picks(): List<PrintPlan.Pick> {
        val id = printerId ?: return emptyList()
        return PrintPlan.match(needs.orEmpty(), PrintPlan.trays(Repo.statuses.value[id]), manual)
    }

    private fun render() {
        val ctx = context ?: return
        body.removeAllViews()

        val title = Ui.big(ctx, name)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.setPadding(0, Ui.dp(ctx, Ui.S), 0, 0)
        body.addView(title)

        val p = plates.firstOrNull { it.index == plate }
        val bits = ArrayList<String>()
        p?.seconds?.takeIf { it > 0 }?.let { bits.add(Ui.minutes(it / 60)) }
        p?.grams?.takeIf { it > 0 }?.let { bits.add(Queue.grams(it)) }
        slicedFor?.let { bits.add("sliced for $it") }
        facts.text = bits.joinToString(" · ")
        facts.visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE

        goSlot.removeAllViews()
        if (noGcode) {
            body.addView(Ui.space(ctx, Ui.M))
            body.addView(warning(ctx, "Bambuddy kept no G-code for this print, so it cannot be sent again. " +
                "Print it from the file in the library instead.", bad = true))
            return
        }
        goSlot.addView(goButton(ctx), Ui.wide(ctx))

        if (plates.size > 1) section(ctx, "Plate") { platePicker(ctx) }
        section(ctx, "Printer") { printerPicker(ctx) }
        section(ctx, "Filament") { filaments(ctx) }
        section(ctx, "When") { whenPicker(ctx) }
        section(ctx, "Options") { options(ctx) }
    }

    private fun section(ctx: Context, heading: String, fill: () -> android.view.View) {
        body.addView(Ui.space(ctx, Ui.M))
        body.addView(Ui.heading(ctx, heading))
        body.addView(fill(), Ui.wide(ctx))
    }

    /** A strip that scrolls sideways rather than wrapping, like the printer picker elsewhere. */
    private fun strip(ctx: Context, view: android.view.View): android.view.View {
        val scroller = HorizontalScrollView(ctx)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(view)
        return scroller
    }

    private fun platePicker(ctx: Context): android.view.View {
        val selected = plates.indexOfFirst { it.index == plate }
        if (plates.size <= 5) {
            return strip(ctx, Ui.segmented(ctx, plates.map { "Plate ${it.index}" }, selected) { i ->
                pickPlate(plates[i].index)
            })
        }
        // A plate list longer than the strip can hold goes in a list instead.
        val label = plates.getOrNull(selected)?.label ?: "Choose a plate"
        return Ui.row(ctx).also { row ->
            row.addView(Ui.button(ctx, "$label  ▾") {
                AlertDialog.Builder(ctx)
                    .setTitle("Which plate?")
                    .setItems(plates.map { plateLine(it) }.toTypedArray()) { _, i -> pickPlate(plates[i].index) }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        }
    }

    private fun plateLine(p: PrintPlan.Plate): String {
        val bits = ArrayList<String>()
        p.seconds?.takeIf { it > 0 }?.let { bits.add(Ui.minutes(it / 60)) }
        p.grams?.takeIf { it > 0 }?.let { bits.add(Queue.grams(it)) }
        return if (bits.isEmpty()) p.label else "${p.label}  —  ${bits.joinToString(" · ")}"
    }

    private fun pickPlate(index: Int) {
        if (index == plate) return
        plate = index
        manual.clear()
        showThumb()
        loadNeeds()
    }

    private fun printerPicker(ctx: Context): android.view.View {
        val box = Ui.col(ctx)
        val all = printers()
        if (all.isEmpty()) {
            box.addView(Ui.dim(ctx, "No printers yet. Add one in Bambuddy first."))
            return box
        }
        val selected = all.indexOfFirst { it.optInt("id") == printerId }
        box.addView(strip(ctx, Ui.segmented(ctx, all.map { printerLabel(it) }, selected) { i ->
            val id = all[i].optInt("id")
            if (id != printerId) {
                printerId = id
                // Tray choices belong to the printer they were made on.
                manual.clear()
                traySignature = ""
                render()
            }
        }))
        val printer = chosenPrinter()
        if (printer == null) {
            box.addView(Ui.space(ctx, Ui.XS))
            box.addView(Ui.tiny(ctx, "Pick the printer this goes to."))
            return box
        }
        val status = Repo.statuses.value[printer.optInt("id")]
        val lines = ArrayList<Pair<String, Int>>()
        if (PrintPlan.modelMismatch(slicedFor, printer.str("model"))) {
            lines.add("Sliced for $slicedFor, and this is ${printer.str("model")}. " +
                "It may fail or crash the nozzle." to Ui.bad(ctx))
        }
        when (PrintPlan.readiness(status)) {
            PrintPlan.Readiness.READY -> lines.add("Idle" to Ui.good(ctx))
            PrintPlan.Readiness.BUSY ->
                lines.add("${Ui.stateWord(status?.str("state"))} now. This goes after the current print." to Ui.warn(ctx))
            PrintPlan.Readiness.PLATE ->
                lines.add("The plate needs clearing before it can start." to Ui.warn(ctx))
            PrintPlan.Readiness.OFFLINE ->
                lines.add("Offline. It will wait in the queue until the printer is back." to Ui.warn(ctx))
            PrintPlan.Readiness.UNKNOWN -> {}
        }
        for ((text, colour) in lines) {
            box.addView(Ui.space(ctx, Ui.XS))
            val t = Ui.tiny(ctx, text)
            t.setTextColor(colour)
            box.addView(t)
        }
        return box
    }

    private fun filaments(ctx: Context): android.view.View {
        val box = Ui.col(ctx)
        val list = needs
        when {
            list == null -> {
                box.addView(Ui.dim(ctx, "Reading the file…"))
                return box
            }
            list.isEmpty() -> {
                box.addView(Ui.dim(ctx,
                    if (needsFailed != null) "Could not read which filaments it uses. Bambuddy will match them itself."
                    else "The file does not list its filaments. Bambuddy will match them itself."))
                return box
            }
        }
        val picks = picks()
        val needList = list!!
        for ((i, need) in needList.withIndex()) {
            if (i > 0) box.addView(Ui.space(ctx, Ui.XS))
            box.addView(filamentRow(ctx, need, picks.firstOrNull { it.need.slotId == need.slotId }), Ui.wide(ctx))
        }
        if (printerId == null) {
            box.addView(Ui.space(ctx, Ui.XS))
            box.addView(Ui.tiny(ctx, "Pick a printer to match these to what it has loaded."))
        }
        return box
    }

    private fun filamentRow(ctx: Context, need: PrintPlan.Need, pick: PrintPlan.Pick?): LinearLayout {
        val row = Ui.inset(ctx)
        row.addView(Ui.swatch(ctx, need.color, 18))
        Ui.gap(ctx, row, Ui.S)
        val what = Ui.col(ctx)
        what.addView(Ui.body(ctx, need.type.ifBlank { "Filament ${need.slotId}" }))
        what.addView(Ui.tiny(ctx, if (need.grams > 0) Queue.grams(need.grams) else "slot ${need.slotId}"))
        row.addView(what, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (printerId == null || pick == null) return row

        row.addView(Ui.tiny(ctx, "from"))
        Ui.gap(ctx, row, Ui.S)
        val tray = pick.tray
        if (tray != null) {
            row.addView(Ui.swatch(ctx, tray.color, 18))
            Ui.gap(ctx, row, Ui.S)
        }
        val where = Ui.col(ctx)
        where.addView(Ui.body(ctx, tray?.label ?: "Nothing loaded"))
        val verdict = when (pick.fit) {
            PrintPlan.Fit.MATCH -> (if (pick.manual) "Your choice" else "Match") to Ui.good(ctx)
            PrintPlan.Fit.TYPE_ONLY -> "Different colour" to Ui.warn(ctx)
            PrintPlan.Fit.NONE -> (if (tray == null) "No ${need.type} loaded" else "Wrong material") to Ui.bad(ctx)
        }
        val v = Ui.tiny(ctx, verdict.first)
        v.setTextColor(verdict.second)
        where.addView(v)
        row.addView(where, Ui.lp(ctx, 170, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(Ui.tiny(ctx, "▾"))

        row.background = Ui.pressable(
            ctx, Ui.rounded(Ui.insetColor(ctx), 10, ctx),
            Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.pressed), 10
        )
        row.isClickable = true
        row.setOnClickListener { chooseTray(ctx, need) }
        return row
    }

    private fun chooseTray(ctx: Context, need: PrintPlan.Need) {
        val id = printerId ?: return
        val trays = PrintPlan.trays(Repo.statuses.value[id])
        if (trays.isEmpty()) {
            toast("This printer is not reporting any loaded filament")
            return
        }
        val labels = ArrayList<String>()
        labels.add("Automatic")
        trays.forEach { t ->
            val remain = if (t.remain in 0..100) " · ${t.remain}%" else ""
            labels.add("${t.label}  —  ${t.type}$remain")
        }
        AlertDialog.Builder(ctx)
            .setTitle("Feed ${need.type.ifBlank { "this filament" }} from")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    manual.remove(need.slotId)
                } else {
                    val chosen = trays[which - 1].globalId
                    // One tray feeds one filament: taking it here frees it elsewhere.
                    manual.entries.removeAll { it.value == chosen }
                    manual[need.slotId] = chosen
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Print options, folded to one line: nearly every print goes with the
     * printer's own defaults, and three rows of switches would push the
     * choices that matter off the screen.
     */
    private fun options(ctx: Context): android.view.View {
        if (!optionsOpen) {
            val row = Ui.inset(ctx)
            val summary = listOf(
                "bed levelling $bedLevelling",
                "flow calibration $flowCali",
                if (timelapse) "timelapse on" else "no timelapse"
            ).joinToString(" · ").replaceFirstChar { it.uppercase() }
            row.addView(Ui.dim(ctx, summary), Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.quiet(ctx, "Change") {
                optionsOpen = true
                render()
            })
            return row
        }
        val box = Ui.col(ctx)
        val first = Ui.row(ctx)
        first.gravity = Gravity.TOP
        val tri = listOf("Auto", "On", "Off")
        first.addView(labelled(ctx, "Bed levelling",
            Ui.segmented(ctx, tri, PrintPlan.TRI_STATES.indexOf(bedLevelling)) { i ->
                bedLevelling = PrintPlan.TRI_STATES[i]
                render()
            }))
        Ui.gap(ctx, first, Ui.M)
        first.addView(labelled(ctx, "Flow calibration",
            Ui.segmented(ctx, tri, PrintPlan.TRI_STATES.indexOf(flowCali)) { i ->
                flowCali = PrintPlan.TRI_STATES[i]
                render()
            }))
        box.addView(strip(ctx, first))
        box.addView(Ui.space(ctx, Ui.S))
        box.addView(labelled(ctx, "Timelapse",
            Ui.segmented(ctx, listOf("Off", "On"), if (timelapse) 1 else 0) { i ->
                timelapse = i == 1
                render()
            }))
        return box
    }

    /** A control with its name under it, the way a reading has its name under it. */
    private fun labelled(ctx: Context, label: String, control: android.view.View): LinearLayout {
        val c = Ui.col(ctx)
        c.addView(control)
        val name = Ui.tiny(ctx, label)
        name.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 3), 0, 0)
        c.addView(name)
        return c
    }

    private fun whenPicker(ctx: Context): android.view.View {
        val row = Ui.row(ctx)
        val modes = listOf(PrintPlan.When.NOW, PrintPlan.When.HOLD, PrintPlan.When.LATER)
        row.addView(Ui.segmented(ctx, listOf("Now", "Wait for me", "Later"), modes.indexOf(whenToPrint)) { i ->
            whenToPrint = modes[i]
            if (whenToPrint == PrintPlan.When.LATER && at == null) pickTime(ctx) else render()
        })
        if (whenToPrint == PrintPlan.When.LATER) {
            Ui.gap(ctx, row, Ui.S)
            val label = at?.let { PrintPlan.sayWhen(it, LocalDateTime.now(), ZoneId.systemDefault()) } ?: "Pick a time"
            row.addView(Ui.button(ctx, label) { pickTime(ctx) })
        } else {
            Ui.gap(ctx, row, Ui.M)
            val hint = Ui.tiny(ctx,
                if (whenToPrint == PrintPlan.When.NOW) "As soon as the printer is free"
                else "Stays in the queue until you press Start")
            row.addView(hint, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    private fun pickTime(ctx: Context) {
        val now = LocalDateTime.now()
        val current = at?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) } ?: now.plusHours(1)
        TimePickerDialog(ctx, { _, hour, minute ->
            at = PrintPlan.nextAt(hour, minute, LocalDateTime.now(), ZoneId.systemDefault())
            render()
        }, current.hour, current.minute, DateFormat.is24HourFormat(ctx)).apply {
            setOnCancelListener {
                if (at == null) whenToPrint = PrintPlan.When.NOW
                render()
            }
        }.show()
    }

    private fun warning(ctx: Context, text: String, bad: Boolean = false): android.view.View {
        val t = Ui.dim(ctx, text)
        t.setTextColor(if (bad) Ui.bad(ctx) else Ui.warn(ctx))
        return t
    }

    // ----------------------------------------------------------- sending

    private fun goLabel(printer: JSONObject?): String {
        val on = printer?.let { printerLabel(it) }
        return when (whenToPrint) {
            PrintPlan.When.NOW -> if (on == null) "Print" else "Print on $on"
            PrintPlan.When.HOLD -> if (on == null) "Add to the queue" else "Add to $on's queue"
            PrintPlan.When.LATER -> if (on == null) "Schedule" else "Schedule on $on"
        }
    }

    private fun goButton(ctx: Context): android.view.View {
        val printer = chosenPrinter()
        val ready = printer != null && needs != null && !sending &&
            (whenToPrint != PrintPlan.When.LATER || at != null)
        val button = Ui.button(ctx, if (sending) "Sending…" else goLabel(printer), primary = true) {
            if (printer != null) confirm(ctx, printer)
        }
        button.minHeight = Ui.dp(ctx, 48)
        button.isEnabled = ready
        button.alpha = if (ready) 1f else 0.4f
        return button
    }

    /** Everything that is about to happen, said once more before it does. */
    private fun confirm(ctx: Context, printer: JSONObject) {
        val picks = picks()
        val lines = ArrayList<String>()
        lines.add(name)
        plates.firstOrNull { it.index == plate }?.takeIf { plates.size > 1 }?.let { lines.add(it.label) }
        lines.add("On ${printerLabel(printer)}")
        if (picks.isNotEmpty()) {
            lines.add("")
            for (p in picks) {
                lines.add("${p.need.type} from ${p.tray?.label ?: "nothing loaded"}")
            }
        }
        val gaps = picks.filter { it.tray == null }
        if (gaps.isNotEmpty()) {
            lines.add("")
            lines.add("Nothing is loaded for ${gaps.joinToString { it.need.type }}. " +
                "The printer will stop and ask for it.")
        }
        if (PrintPlan.modelMismatch(slicedFor, printer.str("model"))) {
            lines.add("")
            lines.add("This was sliced for $slicedFor, not ${printer.str("model")}.")
        }
        lines.add("")
        lines.add(when (whenToPrint) {
            PrintPlan.When.NOW -> when (PrintPlan.readiness(Repo.statuses.value[printer.optInt("id")])) {
                PrintPlan.Readiness.READY -> "The printer starts moving straight away."
                else -> "It starts as soon as the printer is free."
            }
            PrintPlan.When.HOLD -> "It waits in the queue until you press Start."
            PrintPlan.When.LATER -> "It starts ${at?.let { PrintPlan.sayWhen(it, LocalDateTime.now(), ZoneId.systemDefault()) }}, " +
                "if the printer is free then."
        })
        val (title, yes) = when (whenToPrint) {
            PrintPlan.When.NOW -> "Start this print?" to "Print"
            PrintPlan.When.HOLD -> "Add to the queue?" to "Add"
            PrintPlan.When.LATER -> "Schedule this print?" to "Schedule"
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(yes) { _, _ -> send(printer, picks) }
            .setNegativeButton("Not yet", null)
            .show()
    }

    private fun send(printer: JSONObject, picks: List<PrintPlan.Pick>) {
        val request = PrintPlan.Request(
            printerId = printer.optInt("id"),
            libraryFileId = if (isArchive) null else sourceId,
            archiveId = if (isArchive) sourceId else null,
            plateId = plate,
            // His own choices always go; otherwise the phone's match goes only
            // when it found something for every filament. A mapping with a gap
            // in it would be sent as-is, where leaving it out lets Bambuddy
            // match again when the job starts, against whatever is loaded then.
            mapping = if (manual.isNotEmpty() || picks.all { it.tray != null }) PrintPlan.amsMapping(picks) else null,
            bedLevelling = bedLevelling,
            flowCali = flowCali,
            timelapse = timelapse,
            whenToPrint = whenToPrint,
            at = at
        )
        sending = true
        render()
        val label = printerLabel(printer)
        background({ Repo.api.queueAdd(PrintPlan.payload(request)) }) { result ->
            sending = false
            result.onFailure {
                render()
                toast(it.message ?: "Could not queue it")
            }
            result.onSuccess { item ->
                val itemId = item.optInt("id", -1)
                if (whenToPrint == PrintPlan.When.NOW && itemId >= 0) {
                    PrintFlow.start(this, itemId, name) { PrintFlow.open(this, QueueFragment()) }
                } else {
                    Repo.notice(
                        if (whenToPrint == PrintPlan.When.LATER) "Scheduled $name on $label"
                        else "Added $name to $label's queue"
                    )
                    PrintFlow.open(this, QueueFragment())
                }
            }
        }
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"
        private const val ARG_PLATE = "plate"
        private const val ARG_SLICED_FOR = "sliced_for"
        private const val KIND_FILE = "file"
        private const val KIND_ARCHIVE = "archive"
        private const val THUMB_DP = 140
        private const val SIDE_DP = 184

        fun forFile(fileId: Int, name: String, back: String, slicedFor: String? = null): PrintSheetFragment =
            make(KIND_FILE, fileId, name, back, null, slicedFor)

        fun forArchive(archiveId: Int, name: String, back: String, plate: Int?, slicedFor: String?): PrintSheetFragment =
            make(KIND_ARCHIVE, archiveId, name, back, plate, slicedFor)

        private fun make(kind: String, id: Int, name: String, back: String, plate: Int?, slicedFor: String?) =
            PrintSheetFragment().also {
                it.arguments = Bundle().apply {
                    putString(ARG_KIND, kind)
                    putInt(ARG_ID, id)
                    putString(ARG_NAME, name)
                    putString(PrintFlow.ARG_BACK, back)
                    putInt(ARG_PLATE, plate ?: -1)
                    putString(ARG_SLICED_FOR, slicedFor)
                }
            }
    }
}
