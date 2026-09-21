package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.krzkawa.bambuddyaio.net.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
        val p = Ui.dp(ctx, 12)
        content.setPadding(p, p, p, p)
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

    protected fun header(ctx: Context, title: String, subtitle: String? = null): LinearLayout {
        val head = Ui.col(ctx)
        head.addView(Ui.big(ctx, title))
        if (subtitle != null) head.addView(Ui.dim(ctx, subtitle))
        head.addView(Ui.space(ctx, 10))
        return head
    }
}

/** Shared row of printer buttons; the whole app follows one selection. */
fun BaseFragment.buildPrinterPicker(ctx: Context, into: LinearLayout, onPick: (() -> Unit)? = null) {
    into.removeAllViews()
    val printers = Repo.printers.value
    if (printers.size <= 1) return
    into.addView(Ui.heading(ctx, "Printer"))
    val row = Ui.row(ctx)
    for (p in printers) {
        val id = p.optInt("id", -1)
        if (id < 0) continue
        val on = Repo.selected.value == id
        val chip = Ui.button(ctx, p.optString("name").ifBlank { "Printer $id" }, primary = on) {
            Repo.select(id)
            onPick?.invoke()
        }
        row.addView(chip)
        row.addView(Ui.space(ctx, 1), Ui.lp(ctx, 6, 1))
    }
    into.addView(row)
    into.addView(Ui.space(ctx, 10))
}
