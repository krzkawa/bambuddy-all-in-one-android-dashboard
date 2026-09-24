package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One print from History: what it looked like, how it went, and the way to
 * print it again.
 */
class ArchiveFragment : BaseFragment() {

    private lateinit var body: LinearLayout
    private var archiveId = 0

    override fun build(ctx: Context) {
        archiveId = arguments?.getInt(ARG_ID) ?: 0
        PrintFlow.handleBack(this) { PrintFlow.TO_HISTORY }
        content.addView(PrintFlow.crumb(ctx, this, "History", PrintFlow.TO_HISTORY, ""), Ui.wide(ctx))
        content.addView(Ui.space(ctx, Ui.XS))
        body = Ui.col(ctx)
        content.addView(body, Ui.wide(ctx))
        body.addView(empty(ctx, "Loading…"))
        background({ Repo.api.archive(archiveId) }) { result ->
            result.onSuccess { render(it) }
            result.onFailure {
                body.removeAllViews()
                body.addView(empty(ctx, it.message ?: "Could not load this print"))
            }
        }
    }

    private fun render(archive: JSONObject) {
        val ctx = context ?: return
        body.removeAllViews()
        val name = archive.str("print_name") ?: PrintPlan.stripExtension(archive.str("filename") ?: "Print")

        val split = Ui.row(ctx)
        split.gravity = Gravity.TOP
        val thumb = ImageView(ctx)
        thumb.scaleType = ImageView.ScaleType.FIT_CENTER
        thumb.background = Ui.rounded(Ui.cardColor(ctx), 12, ctx)
        val pad = Ui.dp(ctx, Ui.S)
        thumb.setPadding(pad, pad, pad, pad)
        split.addView(thumb, Ui.lp(ctx, THUMB_DP, THUMB_DP))
        if (archive.str("thumbnail_path") != null) {
            Thumbs.load(thumb, "arc:$archiveId", THUMB_DP) { Repo.api.archiveThumbUrl(archiveId, it) }
        }
        Ui.gap(ctx, split, Ui.L)

        val info = Ui.col(ctx)
        val title = Ui.big(ctx, name)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        info.addView(title)

        val status = archive.str("status")
        val (word, colour) = when (status) {
            "completed", "success" -> "Finished" to Ui.good(ctx)
            "failed" -> "Failed" to Ui.bad(ctx)
            "cancelled", "stopped" -> "Stopped" to Ui.warn(ctx)
            "printing" -> "Printing" to Ui.good(ctx)
            null -> "" to Ui.dimColor(ctx)
            else -> status.replaceFirstChar { it.uppercase() } to Ui.dimColor(ctx)
        }
        val whenText = PrintPlan.parseInstant(archive.str("completed_at") ?: archive.str("started_at"))
            ?.let { PrintPlan.sayWhen(it, LocalDateTime.now(), ZoneId.systemDefault()) }
        val line = Ui.row(ctx)
        if (word.isNotEmpty()) {
            line.addView(Ui.dot(ctx, colour))
            Ui.gap(ctx, line, Ui.S)
            line.addView(Ui.dim(ctx, word))
        }
        whenText?.let {
            line.addView(Ui.dim(ctx, if (word.isEmpty()) it else "  ·  $it"))
        }
        info.addView(line)
        archive.str("failure_reason")?.let {
            val why = Ui.tiny(ctx, it)
            why.setTextColor(Ui.bad(ctx))
            info.addView(why)
        }

        info.addView(Ui.space(ctx, Ui.M))
        val stats = Ui.row(ctx)
        fun stat(label: String, value: String?) {
            if (value == null) return
            if (stats.childCount > 0) Ui.gap(ctx, stats, Ui.XL)
            stats.addView(Ui.stat(ctx, label, value))
        }
        stat("Took", archive.int("actual_time_seconds")?.takeIf { it > 0 }?.let { Ui.minutes(it / 60) }
            ?: archive.int("print_time_seconds")?.takeIf { it > 0 }?.let { "~" + Ui.minutes(it / 60) })
        stat("Filament", archive.dbl("filament_used_grams")?.takeIf { it > 0 }?.let { Queue.grams(it) })
        stat("Material", archive.str("filament_type"))
        stat("Printer", archive.int("printer_id")?.let { Repo.printerName(it) })
        info.addView(stats)

        val facts = ArrayList<String>()
        val runs = archive.optInt("run_count")
        if (runs > 1) facts.add("printed $runs times")
        archive.dbl("layer_height")?.let { facts.add("${it} mm layers") }
        archive.int("total_layers")?.takeIf { it > 0 }?.let { facts.add("$it layers") }
        archive.str("bed_type")?.let { facts.add(it) }
        archive.str("sliced_for_model")?.let { facts.add("sliced for $it") }
        if (facts.isNotEmpty()) {
            info.addView(Ui.space(ctx, Ui.S))
            info.addView(Ui.tiny(ctx, facts.joinToString(" · ")))
        }
        archive.str("notes")?.let {
            info.addView(Ui.space(ctx, Ui.S))
            info.addView(Ui.dim(ctx, it))
        }

        info.addView(Ui.space(ctx, Ui.L))
        val actions = Ui.row(ctx)
        val again = Ui.button(ctx, "Print again", primary = true) {
            PrintFlow.open(
                this,
                PrintSheetFragment.forArchive(
                    archiveId, name, PrintFlow.toArchive(archiveId),
                    archive.int("plate_id"), archive.str("sliced_for_model")
                )
            )
        }
        again.minHeight = Ui.dp(ctx, 44)
        actions.addView(again)
        info.addView(actions)

        split.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(split, Ui.wide(ctx))

        photos(ctx, archive)
    }

    /** Photos he took of the result, if any, in a strip under the rest. */
    private fun photos(ctx: Context, archive: JSONObject) {
        val list = archive.optJSONArray("photos") ?: return
        val names = (0 until list.length()).mapNotNull { list.optString(it).ifBlank { null } }
        if (names.isEmpty()) return
        body.addView(Ui.space(ctx, Ui.L))
        body.addView(Ui.heading(ctx, if (names.size == 1) "Photo" else "Photos"))
        val strip = Ui.row(ctx)
        for ((i, file) in names.withIndex()) {
            if (i > 0) Ui.gap(ctx, strip, Ui.S)
            val image = ImageView(ctx)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.background = Ui.rounded(Ui.cardColor(ctx), 10, ctx)
            strip.addView(image, Ui.lp(ctx, PHOTO_DP, PHOTO_DP))
            Thumbs.load(image, "photo:$archiveId:$file", PHOTO_DP) { Repo.api.archivePhotoUrl(archiveId, file, it) }
        }
        val scroller = HorizontalScrollView(ctx)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(strip)
        body.addView(scroller, Ui.wide(ctx))
    }

    companion object {
        private const val ARG_ID = "id"
        private const val THUMB_DP = 150
        private const val PHOTO_DP = 110

        fun of(archiveId: Int): ArchiveFragment = ArchiveFragment().also {
            it.arguments = Bundle().apply { putInt(ARG_ID, archiveId) }
        }
    }
}
