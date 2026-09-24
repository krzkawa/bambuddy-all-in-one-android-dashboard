package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.krzkawa.bambuddyaio.R
import io.github.krzkawa.bambuddyaio.net.ApiError
import io.github.krzkawa.bambuddyaio.net.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screens behind Queue and History — the file library, one old print, and
 * the sheet that sends a print — and how he gets back out of them.
 *
 * They are not rail tabs: ten already fill the height of the phone exactly.
 * Each one takes the place of the screen that opened it and knows where Back
 * leads, which is written down as a short string so it survives the fragment
 * being rebuilt.
 */
object PrintFlow {

    const val ARG_BACK = "back"

    const val TO_QUEUE = "queue"
    const val TO_HISTORY = "history"
    fun toLibrary(folderId: Int?) = "library/${folderId ?: ""}"
    fun toArchive(archiveId: Int) = "archive/$archiveId"

    /** The screen a back target names. Anything unreadable goes to the queue. */
    fun screenFor(target: String?): Fragment {
        val t = target.orEmpty()
        return when {
            t == TO_HISTORY -> HistoryFragment()
            t.startsWith("library/") -> LibraryFragment.of(t.removePrefix("library/").toIntOrNull())
            t.startsWith("archive/") -> t.removePrefix("archive/").toIntOrNull()
                ?.let { ArchiveFragment.of(it) } ?: HistoryFragment()
            else -> QueueFragment()
        }
    }

    /** Swaps the content area to [next], leaving the rail where it is. */
    fun open(from: Fragment, next: Fragment) {
        if (!from.isAdded) return
        // The screen being left may have put its own action in the top bar;
        // the next one puts its own there if it has one.
        (from.activity as? MainActivity)?.setScreenAction(null, null)
        from.parentFragmentManager.beginTransaction()
            .replace(R.id.content_frame, next)
            .commitAllowingStateLoss()
    }

    /**
     * Makes the phone's Back key, and the "‹" at the top of the screen, lead to
     * [target] instead of closing the app.
     */
    fun handleBack(fragment: Fragment, target: () -> String) {
        fragment.requireActivity().onBackPressedDispatcher.addCallback(
            fragment.viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = open(fragment, screenFor(target()))
            }
        )
    }

    /**
     * The first line of a screen reached from another: a way back, and where
     * he is. Built from the same quiet button and dim text as everything else.
     */
    fun crumb(ctx: Context, fragment: Fragment, backLabel: String, target: String, where: String): LinearLayout {
        val line = Ui.row(ctx)
        line.addView(Ui.quiet(ctx, "‹  $backLabel") { open(fragment, screenFor(target)) })
        if (where.isNotBlank()) {
            Ui.gap(ctx, line, Ui.XS)
            val place = Ui.dim(ctx, where)
            place.maxLines = 1
            place.ellipsize = android.text.TextUtils.TruncateAt.START
            line.addView(place, Ui.lp(ctx, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return line
    }

    /**
     * Starts a staged queue item, and turns the server's one refusal worth
     * arguing with into a question.
     *
     * A 409 carrying a filament deficit means the assigned spools cannot cover
     * the job as far as the server can tell. That is often a spool it has the
     * wrong weight for, so it is his call, not a dead end: the same call with
     * the check skipped goes through, and the server remembers the decision so
     * its own scheduler does not re-block the item a moment later.
     */
    fun start(
        fragment: Fragment,
        itemId: Int,
        name: String,
        skipFilamentCheck: Boolean = false,
        onDone: () -> Unit
    ) {
        val ctx = fragment.context ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Repo.api.queueStart(itemId, skipFilamentCheck) }
            }
            if (!fragment.isAdded) return@launch
            result.onSuccess {
                Repo.notice("Started $name")
                Ui.toast(ctx, "Starting $name")
                Repo.refresh()
                onDone()
            }
            result.onFailure { failure ->
                val shortfalls = (failure as? ApiError)?.let { Queue.shortfalls(it) }
                if (shortfalls == null) {
                    Ui.toast(ctx, failure.message ?: "Could not start it")
                    return@onFailure
                }
                AlertDialog.Builder(ctx)
                    .setTitle("Not enough filament")
                    .setMessage(Queue.shortfallMessage(shortfalls) + "\n\nPrint it anyway?")
                    .setPositiveButton("Print anyway") { _, _ ->
                        start(fragment, itemId, name, skipFilamentCheck = true, onDone = onDone)
                    }
                    .setNegativeButton("Not now") { _, _ ->
                        // The item stays in the queue, staged, which is where
                        // he would look for it after deciding against it.
                        onDone()
                    }
                    .show()
            }
        }
    }
}
