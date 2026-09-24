package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/**
 * Bambuddy's file library, for picking something to print.
 *
 * Reached from the Queue screen's "Add print". Folders sit above the files in
 * a strip of their own, files are a two-column grid with their pictures, and
 * anything not yet sliced is shown but greyed: seeing the file he expected,
 * and why it cannot go, beats wondering where it went.
 */
class LibraryFragment : BaseFragment() {

    private lateinit var folderRow: LinearLayout
    private lateinit var grid: LinearLayout
    private var folderId: Int? = null
    private var folders: List<PrintPlan.Folder> = emptyList()
    private var files: List<JSONObject> = emptyList()
    private var query = ""

    override fun build(ctx: Context) {
        folderId = arguments?.getInt(ARG_FOLDER, -1)?.takeIf { it >= 0 }
        screenAction("Reload") {
            Thumbs.retryMissing()
            load()
        }
        // Back climbs one folder at a time, then leaves for the queue.
        PrintFlow.handleBack(this) {
            val here = folderId
            if (here == null) PrintFlow.TO_QUEUE
            else PrintFlow.toLibrary(folders.firstOrNull { it.id == here }?.parentId)
        }

        val top = Ui.row(ctx)
        top.addView(
            PrintFlow.crumb(ctx, this, "Queue", PrintFlow.TO_QUEUE, ""),
            Ui.lp(ctx, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        Ui.push(ctx, top)
        val search = Ui.input(ctx, "Search files")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                renderFiles()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        top.addView(search, Ui.lp(ctx, 220, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(top, Ui.wide(ctx))
        content.addView(Ui.space(ctx, Ui.S))

        folderRow = Ui.col(ctx)
        content.addView(folderRow, Ui.wide(ctx))
        grid = Ui.col(ctx)
        content.addView(grid, Ui.wide(ctx))
        load()
    }

    private fun load() {
        val ctx = context ?: return
        grid.removeAllViews()
        grid.addView(empty(ctx, "Loading…"))
        val here = folderId
        background({
            // The folder tree is small and every screen needs it for the path,
            // so it comes along with each listing rather than being cached.
            Repo.api.libraryFolders() to Repo.api.libraryFiles(here)
        }) { result ->
            result.onSuccess { (tree, list) ->
                folders = PrintPlan.folders(tree)
                files = PrintPlan.sortFiles(list.objects())
                renderFolders()
                renderFiles()
            }
            result.onFailure {
                grid.removeAllViews()
                grid.addView(empty(ctx, it.message ?: "Could not load the files"))
            }
        }
    }

    private fun go(folder: Int?) {
        PrintFlow.open(this, of(folder))
    }

    private fun renderFolders() {
        val ctx = context ?: return
        folderRow.removeAllViews()

        // Where he is, as a line of steps he can tap back up.
        val path = PrintPlan.pathTo(folders, folderId)
        if (path.isNotEmpty()) {
            val line = Ui.row(ctx)
            line.addView(Ui.quiet(ctx, "All files") { go(null) })
            for ((i, step) in path.withIndex()) {
                line.addView(Ui.tiny(ctx, "›"))
                val last = i == path.lastIndex
                if (last) {
                    val t = Ui.title(ctx, step.name)
                    t.setPadding(Ui.dp(ctx, 10), 0, 0, 0)
                    line.addView(t)
                } else {
                    line.addView(Ui.quiet(ctx, step.name) { go(step.id) })
                }
            }
            folderRow.addView(line, Ui.wide(ctx))
            folderRow.addView(Ui.space(ctx, Ui.XS))
        }

        val inside = PrintPlan.childrenOf(folders, folderId)
        if (inside.isEmpty()) return
        // Three to a line: a folder name is short, and a column of them would
        // push the files, which are what he came for, off the screen.
        for (chunk in inside.chunked(3)) {
            val line = Ui.row(ctx)
            chunk.forEachIndexed { i, folder ->
                if (i > 0) Ui.gap(ctx, line, Ui.S)
                line.addView(folderTile(ctx, folder), Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(3 - chunk.size) {
                Ui.gap(ctx, line, Ui.S)
                line.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
            }
            folderRow.addView(line, Ui.wide(ctx))
            folderRow.addView(Ui.space(ctx, Ui.S))
        }
    }

    private fun folderTile(ctx: Context, folder: PrintPlan.Folder): LinearLayout {
        val tile = Ui.inset(ctx)
        tile.background = Ui.pressable(
            ctx, Ui.rounded(Ui.insetColor(ctx), 10, ctx),
            Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.pressed), 10
        )
        val info = Ui.col(ctx)
        val name = Ui.body(ctx, folder.name)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        info.addView(name)
        info.addView(Ui.tiny(ctx, if (folder.fileCount == 1) "1 file" else "${folder.fileCount} files"))
        tile.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tile.addView(Ui.tiny(ctx, "›"))
        tile.isClickable = true
        tile.setOnClickListener { go(folder.id) }
        return tile
    }

    private fun renderFiles() {
        val ctx = context ?: return
        grid.removeAllViews()
        val shown = files.filter { PrintPlan.matches(it, query) }
        if (shown.isEmpty()) {
            grid.addView(
                empty(
                    ctx,
                    when {
                        files.isNotEmpty() -> "Nothing here matches that."
                        PrintPlan.childrenOf(folders, folderId).isNotEmpty() -> "No files here, only folders."
                        else -> "No files here. Add them in Bambuddy's File Manager."
                    }
                )
            )
            return
        }
        for (pair in shown.chunked(2)) {
            val line = Ui.row(ctx)
            line.gravity = android.view.Gravity.TOP
            pair.forEachIndexed { i, file ->
                if (i > 0) Ui.gap(ctx, line, Ui.S)
                line.addView(fileCard(ctx, file), Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (pair.size == 1) {
                Ui.gap(ctx, line, Ui.S)
                line.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
            }
            grid.addView(line, Ui.wide(ctx))
            grid.addView(Ui.space(ctx, Ui.S))
        }
    }

    private fun fileCard(ctx: Context, file: JSONObject): LinearLayout {
        val sliced = PrintPlan.isSliced(file)
        val card = Ui.inset(ctx)
        card.background = Ui.pressable(
            ctx, Ui.rounded(Ui.cardColor(ctx), 10, ctx),
            Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.pressed), 10
        )

        val id = file.optInt("id")
        val thumb = ImageView(ctx)
        thumb.scaleType = ImageView.ScaleType.FIT_CENTER
        thumb.background = Ui.rounded(Ui.insetColor(ctx), 8, ctx)
        card.addView(thumb, Ui.lp(ctx, 56, 56))
        if (file.str("thumbnail_path") != null) {
            Thumbs.load(thumb, "lib:$id", 56) { Repo.api.libraryThumbUrl(id, it) }
        }
        Ui.gap(ctx, card, Ui.M)

        val info = Ui.col(ctx)
        val name = Ui.body(ctx, PrintPlan.fileName(file))
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        info.addView(name)
        val bits = ArrayList<String>()
        if (sliced) {
            file.int("print_time_seconds")?.takeIf { it > 0 }?.let { bits.add(Ui.minutes(it / 60)) }
            file.dbl("filament_used_grams")?.takeIf { it > 0 }?.let { bits.add(Queue.grams(it)) }
            file.str("sliced_for_model")?.let { bits.add(it) }
            file.int("print_count")?.takeIf { it > 0 }?.let { bits.add(if (it == 1) "printed once" else "printed $it×") }
        } else {
            bits.add("Not sliced")
        }
        info.addView(Ui.tiny(ctx, bits.joinToString(" · ")))
        card.addView(info, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!sliced) card.alpha = 0.55f
        card.isClickable = true
        card.setOnClickListener {
            if (!sliced) {
                toast("Slice it in Bambu Studio or Orca first, then it can be printed")
            } else {
                PrintFlow.open(
                    this,
                    PrintSheetFragment.forFile(
                        id, PrintPlan.fileName(file), PrintFlow.toLibrary(folderId), file.str("sliced_for_model")
                    )
                )
            }
        }
        return card
    }

    companion object {
        private const val ARG_FOLDER = "folder"

        fun of(folderId: Int?): LibraryFragment {
            val f = LibraryFragment()
            f.arguments = Bundle().apply { putInt(ARG_FOLDER, folderId ?: -1) }
            return f
        }
    }
}
