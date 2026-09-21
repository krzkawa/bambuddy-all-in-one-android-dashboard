package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.krzkawa.bambuddyaio.net.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A screen: one padded column inside a scroller, rebuilt when the data moves. */
abstract class BaseFragment : Fragment() {

    protected lateinit var content: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        content = Ui.col(ctx)
        // The top bar carries the screen's name, so content starts flush with
        // it; the side gutters are wider than the gap to the bar above.
        content.setPadding(Ui.dp(ctx, Ui.M), Ui.dp(ctx, Ui.XS), Ui.dp(ctx, Ui.M), Ui.dp(ctx, Ui.M))
        build(ctx)
        val scroller = Ui.scroll(ctx, content)
        scroller.setBackgroundColor(Ui.bg(ctx))
        return scroller
    }

    abstract fun build(ctx: Context)

    /** Runs [block] on the main thread each time [flow] produces, while visible. */
    protected fun <T> observe(flow: Flow<T>, block: (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { block(it) }
            }
        }
    }

    /**
     * Fires on the main thread every [ms] while the screen is visible.
     *
     * Nothing at all is emitted while the network is down, so anything on
     * screen that ages — "not live since…" — needs a clock of its own.
     */
    protected fun ticker(ms: Long): Flow<Long> = flow {
        var tick = 0L
        while (true) {
            emit(tick++)
            delay(ms)
        }
    }

    /** Runs a blocking API call off the main thread and delivers the outcome back. */
    protected fun <T> background(work: () -> T, then: (Result<T>) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(work())
                } catch (e: Exception) {
                    Result.failure<T>(e)
                }
            }
            if (isAdded) then(result)
        }
    }

    protected fun toast(message: String) {
        if (isAdded) Ui.toast(requireContext(), message)
    }

    /** Sends a printer command and reports what happened. */
    protected fun command(label: String, block: () -> Unit) {
        Repo.action(label, block) { toast(it) }
    }

    /** Puts this screen's one action in the top bar, beside its name. */
    protected fun screenAction(label: String, onClick: () -> Unit) {
        (activity as? MainActivity)?.setScreenAction(label, onClick)
    }

    /** Full width, own height. */
    protected fun wide(ctx: Context): LinearLayout.LayoutParams = Ui.wide(ctx)

    /** What a screen says when it has nothing to show yet. */
    protected fun empty(ctx: Context, message: String): LinearLayout {
        val box = Ui.col(ctx)
        box.setPadding(0, Ui.dp(ctx, Ui.L), 0, Ui.dp(ctx, Ui.L))
        box.addView(Ui.dim(ctx, message))
        return box
    }
}

/**
 * Which printer the whole app is following, as one segmented strip.
 *
 * It used to be a "Printer" label over a row of filled buttons, where the four
 * printers shouted as loudly as each other and as loudly as Pause and Stop
 * below them. One strip, one segment lit, no label — a picker does not need to
 * announce that it is a picker.
 */
fun BaseFragment.buildPrinterPicker(ctx: Context, into: LinearLayout, onPick: (() -> Unit)? = null) {
    into.removeAllViews()
    val printers = Repo.printers.value.filter { it.optInt("id", -1) >= 0 }
    if (printers.size <= 1) return

    val names = printers.map { it.optString("name").ifBlank { "Printer ${it.optInt("id")}" } }
    val selected = printers.indexOfFirst { it.optInt("id") == Repo.selected.value }
    val strip = Ui.segmented(ctx, names, selected) { index ->
        Repo.select(printers[index].optInt("id"))
        onPick?.invoke()
    }

    // More printers than fit are scrolled to rather than wrapped: a strip that
    // reflows moves the segment he was aiming at.
    val scroller = HorizontalScrollView(ctx)
    scroller.isHorizontalScrollBarEnabled = false
    scroller.addView(strip)
    into.addView(scroller, Ui.lp(ctx, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    into.addView(Ui.space(ctx, Ui.M))
}
