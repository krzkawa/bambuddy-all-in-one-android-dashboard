package io.github.krzkawa.bambuddyaio.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.krzkawa.bambuddyaio.net.Repo

/** The printer's own camera, at a frame rate an old phone can keep up with. */
class CameraFragment : BaseFragment() {

    private lateinit var picker: LinearLayout
    private lateinit var holder: LinearLayout
    private lateinit var note: android.widget.TextView
    private var view: MjpegView? = null
    private var showing = -1

    /** The printer whose stream the server currently believes is wanted, or -1. */
    private var streaming = -1

    private var fullscreen: Dialog? = null

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
        // The video takes whatever is left below the header rather than a fixed
        // 240 dp, which on a 288 dp-tall screen left it a postage stamp with
        // empty space under it. The floor is there so it cannot vanish entirely
        // if a printer picker and a long error message both want room.
        holder.minimumHeight = Ui.dp(ctx, 140)
        content.addView(holder, Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

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

        // Switching printers means the old stream is finished for good, so the
        // server should be told. A reconnect to the same printer must not say
        // that: the stop would race the request that follows it.
        if (streaming >= 0 && streaming != printerId) releaseStream()

        showing = printerId
        buildPrinterPicker(ctx, picker) { openStream(force = true) }

        closeFullscreen()
        view?.stop()
        holder.removeAllViews()
        val player = MjpegView(ctx)
        player.onError = { message -> if (isAdded) note.text = message }
        player.isClickable = true
        player.setOnClickListener { openFullscreen() }
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
            // Minting the token takes a round trip, and in that time the screen
            // may have moved on — a second Reconnect, or another printer picked.
            // Starting this player now would put a stream behind a view nobody
            // holds any more, and nothing would ever stop it.
            if (view !== player) return@background

            result.onSuccess { url ->
                note.text = "${Repo.printerName(printerId)} · tap the picture for fullscreen"
                streaming = printerId
                player.start(Repo.api, url)
            }
            result.onFailure { note.text = it.message ?: "Could not reach the camera" }
        }
    }

    // ------------------------------------------------------------- fullscreen

    /**
     * Fills the screen with the running stream.
     *
     * The view is carried into the dialog rather than a second one being made,
     * so going fullscreen costs nothing: same socket, same frames, no reconnect
     * and no second stream for the server to serve.
     */
    private fun openFullscreen() {
        val ctx = context ?: return
        val player = view ?: return
        if (fullscreen != null) return

        val stage = FrameLayout(ctx)
        stage.setBackgroundColor(Color.BLACK)

        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(
            stage,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.setCanceledOnTouchOutside(false)
        // Back has to be caught here rather than handled on dismiss. Dismissing
        // tears the dialog's views off the window, which is the one thing that
        // stops the stream, and by the time a dismiss listener runs that has
        // already happened. So every way out goes through closeFullscreen,
        // which walks the view home first.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                closeFullscreen()
                true
            } else {
                false
            }
        }
        // Belt and braces: if the dialog ever goes away by some route that did
        // not come through closeFullscreen, the stream is gone with it, so the
        // screen is rebuilt from scratch rather than left showing a dead frame.
        dialog.setOnDismissListener {
            if (fullscreen != null) {
                fullscreen = null
                if (isAdded) openStream(force = true)
            }
        }
        fullscreen = dialog

        player.moveTo(
            stage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // Tapping the picture is how you got here, so it is how you leave.
        player.setOnClickListener { closeFullscreen() }
        dialog.show()
    }

    /** Brings the view back inline, then takes the dialog down. Order matters. */
    private fun closeFullscreen() {
        val dialog = fullscreen ?: return
        fullscreen = null

        val ctx = context
        val player = view
        if (ctx != null && player != null) {
            player.moveTo(
                holder,
                Ui.lp(ctx, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            player.setOnClickListener { openFullscreen() }
        }
        dialog.dismiss()
    }

    // ---------------------------------------------------------------- leaving

    /**
     * Tells the server the stream is over.
     *
     * Bambuddy keeps the printer's camera feed open for whoever asked for it, so
     * walking off the screen without saying so leaves it running. There is no
     * lifecycle left to hang a blocking call on by the time this matters, hence
     * the fire-and-forget form.
     */
    private fun releaseStream() {
        val printerId = streaming
        streaming = -1
        if (printerId < 0) return
        try {
            Repo.api.cameraStopAsync(printerId)
        } catch (e: Exception) {
            // Nothing to do about it and nobody left on screen to tell.
        }
    }

    override fun onPause() {
        super.onPause()
        // A live stream behind a switched-away screen is pure battery drain.
        closeFullscreen()
        view?.stop()
        releaseStream()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) openStream(force = true)
    }

    override fun onDestroyView() {
        closeFullscreen()
        view?.stop()
        view = null
        releaseStream()
        super.onDestroyView()
    }
}
