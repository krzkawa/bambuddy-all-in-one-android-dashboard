package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo

/** The printer's own camera, at a frame rate an old phone can keep up with. */
class CameraFragment : BaseFragment() {

    private lateinit var picker: LinearLayout
    private lateinit var holder: LinearLayout
    private lateinit var note: android.widget.TextView
    private var view: MjpegView? = null
    private var showing = -1

    override fun build(ctx: Context) {
        val head = Ui.row(ctx)
        head.addView(Ui.big(ctx, "Camera"))
        head.addView(Ui.space(ctx, 1), Ui.lp(ctx, 0, 1, 1f))
        head.addView(Ui.button(ctx, "Reconnect") { openStream(force = true) })
        content.addView(head, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Ui.space(ctx, 8))

        picker = Ui.col(ctx)
        content.addView(picker, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        note = Ui.dim(ctx, "")
        content.addView(note)

        holder = Ui.col(ctx)
        content.addView(holder, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, 240))

        observe(Repo.selected) { openStream(force = true) }
        buildPrinterPicker(ctx, picker) { openStream(force = true) }
        openStream(force = false)
    }

    private fun openStream(force: Boolean) {
        val ctx = context ?: return
        val printerId = Repo.selected.value
        if (printerId < 0) {
            note.text = "Choose a printer first."
            return
        }
        if (!force && showing == printerId && view != null) return
        showing = printerId

        buildPrinterPicker(ctx, picker) { openStream(force = true) }

        view?.stop()
        holder.removeAllViews()
        val player = MjpegView(ctx)
        player.onError = { message -> if (isAdded) note.text = message }
        holder.addView(
            player,
            Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        view = player
        note.text = "Connecting to the camera…"

        // The stream route takes a token in the URL rather than a header, so
        // one has to be minted before the first frame can arrive.
        background({
            val token = try {
                Repo.api.cameraToken()
            } catch (e: Exception) {
                ""
            }
            Repo.api.cameraStreamUrl(printerId, 5, token)
        }) { result ->
            result.onSuccess { url ->
                note.text = Repo.printerName(printerId)
                player.start(Repo.api, url)
            }
            result.onFailure { note.text = it.message ?: "Could not reach the camera" }
        }
    }

    override fun onPause() {
        super.onPause()
        // A live stream behind a switched-away screen is pure battery drain.
        view?.stop()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) openStream(force = true)
    }

    override fun onDestroyView() {
        view?.stop()
        view = null
        super.onDestroyView()
    }
}
