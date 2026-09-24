package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ImageView
import io.github.krzkawa.bambuddyaio.net.Api
import io.github.krzkawa.bambuddyaio.net.STREAM_READ_TIMEOUT_SECONDS
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * Shows an MJPEG stream in an ImageView.
 *
 * Written by hand rather than pulled in as a library: the stream is just JPEG
 * frames back to back, and decoding them at half size into RGB565 is what keeps
 * this usable on a phone with a few hundred megabytes to play with.
 */
class MjpegView(ctx: Context) : ImageView(ctx) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var worker: Thread? = null

    /**
     * The in-flight request, kept only so [stop] can cancel it.
     *
     * Setting `running = false` and interrupting the worker is not enough on its
     * own: the worker spends its life blocked in a socket read, and a blocking
     * read ignores Thread.interrupt(). It would notice the flag on the next
     * frame — which, if the server has gone quiet, never comes. Cancelling the
     * call closes the socket underneath it, which is what actually ends the read.
     */
    private var call: Call? = null
    private var last: Bitmap? = null

    /** True while this view is being moved between parents rather than thrown away. */
    private var moving = false

    var onError: ((String) -> Unit)? = null

    /** Fires on the first frame of a stream, so a screen can drop its placeholder. */
    var onFirstFrame: (() -> Unit)? = null

    init {
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.card_alt))
    }

    fun start(api: Api, url: HttpUrl) {
        stop()
        running = true
        val request = api.authHeaders(Request.Builder().url(url)).get().build()
        val pending = api.streamClient.newCall(request)
        call = pending
        worker = Thread {
            try {
                pending.execute().use { response ->
                    if (!response.isSuccessful) {
                        report("Camera returned ${response.code}")
                        return@use
                    }
                    val stream = response.body?.byteStream() ?: run {
                        report("Camera sent nothing")
                        return@use
                    }
                    pump(stream)
                    if (running) report("The camera stopped sending.")
                }
            } catch (e: SocketTimeoutException) {
                if (running) {
                    report("No picture for ${STREAM_READ_TIMEOUT_SECONDS}s — is the camera still on?")
                }
            } catch (e: Exception) {
                // A cancelled call lands here too, which is the normal way out.
                if (running) report(e.message ?: "Camera stream stopped")
            }
        }
        worker?.isDaemon = true
        worker?.start()
    }

    fun stop() {
        running = false
        call?.cancel()
        call = null
        worker?.interrupt()
        worker = null
        // Drop the last frame's bitmap rather than hold a full-screen image
        // while the user is on another screen. Clearing the view first, because
        // recycling a bitmap that is still being drawn crashes on draw.
        setImageDrawable(null)
        last?.recycle()
        last = null
    }

    /**
     * Moves this view into [into] without dropping the stream.
     *
     * Leaving a window normally stops the stream, since a view nobody can see
     * should not be holding a socket and a bitmap. Changing parent looks exactly
     * like that from in here, so a move has to announce itself — otherwise
     * going fullscreen would tear the stream down and rebuild it.
     */
    fun moveTo(into: ViewGroup, params: ViewGroup.LayoutParams) {
        if (parent === into) return
        moving = true
        try {
            (parent as? ViewGroup)?.removeView(this)
            into.addView(this, params)
        } finally {
            moving = false
        }
    }

    override fun onDetachedFromWindow() {
        if (!moving) stop()
        super.onDetachedFromWindow()
    }

    private fun pump(stream: InputStream) {
        val chunk = ByteArray(16 * 1024)
        val frames = MjpegFrames()
        var lastFrameAt = 0L
        var lastShownAt = System.currentTimeMillis()
        var complained = false

        while (running) {
            val read = stream.read(chunk)
            if (read < 0) break

            val produced = frames.append(chunk, read)
            for (frame in produced) {
                // Decoding every frame would peg the CPU on an old phone, and
                // the eye cannot tell above a few frames a second anyway.
                val now = System.currentTimeMillis()
                if (now - lastFrameAt >= 180) {
                    lastFrameAt = now
                    show(frame)
                }
            }

            val now = System.currentTimeMillis()
            if (produced.isNotEmpty()) {
                lastShownAt = now
                complained = false
            } else if (!complained && now - lastShownAt > NO_FRAME_GRACE_MS) {
                // Bytes are arriving but no picture is coming out of them. The
                // read timeout covers a stream that has gone silent; this covers
                // the other shape of failure, where the far end is talking but
                // not in JPEG. Which one it is decides what to say.
                complained = true
                report(
                    if (frames.awaitingFrame) "Still waiting on a frame — slow connection."
                    else "The camera is sending something that is not a picture."
                )
            }
        }
    }

    private fun show(jpeg: ByteArray) {
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.RGB_565
        options.inSampleSize = 2
        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return

        main.post {
            if (!running) {
                bitmap.recycle()
                return@post
            }
            val previous = last
            last = bitmap
            setImageBitmap(bitmap)
            previous?.recycle()
            if (previous == null) onFirstFrame?.invoke()
        }
    }

    private fun report(message: String) {
        main.post { onError?.invoke(message) }
    }

    private companion object {
        /** How long bytes may arrive without yielding a frame before it is worth saying so. */
        const val NO_FRAME_GRACE_MS = 6_000L
    }
}
