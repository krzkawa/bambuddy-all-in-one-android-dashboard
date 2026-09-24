package io.github.krzkawa.bambuddyaio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import io.github.krzkawa.bambuddyaio.net.ApiError
import io.github.krzkawa.bambuddyaio.net.Repo
import okhttp3.HttpUrl
import java.util.concurrent.Executors

/**
 * Print thumbnails for the file library, the print sheet and History.
 *
 * Bambuddy serves these on routes built for browser `<img>` tags, which want
 * the camera's stream token in the query string. One token lasts an hour, so
 * it is fetched once and reused rather than minted per picture.
 *
 * Deliberately small: two download threads, pictures decoded down to the size
 * they are drawn at, and a cache sized to a few screens of them. A 2016 phone
 * that decodes forty full-size PNGs at once is a phone that stutters.
 */
object Thumbs {

    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** About 6 MB of bitmaps, counted in kilobytes. */
    private val cache = object : LruCache<String, Bitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Pictures the server has said it does not have, so they are not asked for again. */
    private val missing = HashSet<String>()

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L
    private const val TOKEN_REUSE_MS = 45 * 60 * 1000L

    private fun token(): String? {
        val now = System.currentTimeMillis()
        token?.let { if (now - tokenAt < TOKEN_REUSE_MS) return it }
        // An install with auth switched off answers without one, so a failure
        // here is not the end: the picture is still asked for.
        val fresh = try {
            Repo.api.cameraToken().ifBlank { null }
        } catch (e: Exception) {
            null
        }
        token = fresh
        tokenAt = now
        return fresh
    }

    /**
     * Draws the picture at [key] into [view], sized for [sizeDp].
     *
     * [url] builds the address from a token. The view is tagged with the key
     * so a row recycled for another file before its picture arrives is not
     * handed the wrong one.
     */
    fun load(view: ImageView, key: String, sizeDp: Int, url: (String?) -> HttpUrl) {
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        synchronized(missing) { if (key in missing) return }
        val px = Ui.dp(view.context, sizeDp)
        pool.execute {
            val bitmap = try {
                decode(Repo.api.bytes(url(token())), px)
            } catch (e: ApiError) {
                // A 401 is most likely a token that expired early; the next
                // picture asks for a new one. Only a 404 is worth remembering.
                if (e.code == 401) token = null
                if (e.code != 404) return@execute
                null
            } catch (e: Exception) {
                return@execute
            }
            if (bitmap == null) {
                synchronized(missing) { missing.add(key) }
                return@execute
            }
            cache.put(key, bitmap)
            main.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    /** Decodes at the smallest power-of-two step that still covers [px]. */
    private fun decode(bytes: ByteArray, px: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Plate pictures are drawn on a transparent ground, which a
            // format without alpha would fill in black.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Forgets what failed, for a Reload after the server has come back. */
    fun retryMissing() {
        synchronized(missing) { missing.clear() }
    }
}
