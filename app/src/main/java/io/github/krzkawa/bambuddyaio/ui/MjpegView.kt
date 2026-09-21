package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import io.github.krzkawa.bambuddyaio.net.Api
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.InputStream

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
    private var last: Bitmap? = null

    var onError: ((String) -> Unit)? = null

    init {
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(Ui.color(ctx, io.github.krzkawa.bambuddyaio.R.color.card_alt))
    }

    fun start(api: Api, url: HttpUrl) {
        stop()
        running = true
        worker = Thread {
            try {
                val request = api.authHeaders(Request.Builder().url(url)).get().build()
                api.streamClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        report("Camera returned ${response.code}")
                        return@use
                    }
                    val stream = response.body?.byteStream() ?: run {
                        report("Camera sent nothing")
                        return@use
                    }
                    pump(stream)
                }
            } catch (e: Exception) {
                if (running) report(e.message ?: "Camera stream stopped")
            }
        }
        worker?.isDaemon = true
        worker?.start()
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        // Drop the last frame's bitmap rather than hold a full-screen image
        // while the user is on another screen. Clearing the view first, because
        // recycling a bitmap that is still being drawn crashes on draw.
        setImageDrawable(null)
        last?.recycle()
        last = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun pump(stream: InputStream) {
        val chunk = ByteArray(16 * 1024)
        val frames = MjpegFrames()
        var lastFrameAt = 0L

        while (running) {
            val read = stream.read(chunk)
            if (read < 0) break

            for (frame in frames.append(chunk, read)) {
                // Decoding every frame would peg the CPU on an old phone, and
                // the eye cannot tell above a few frames a second anyway.
                val now = System.currentTimeMillis()
                if (now - lastFrameAt >= 180) {
                    lastFrameAt = now
                    show(frame)
                }
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
        }
    }

    private fun report(message: String) {
        main.post { onError?.invoke(message) }
    }
}
