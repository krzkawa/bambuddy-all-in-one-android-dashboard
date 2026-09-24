package io.github.krzkawa.bambuddyaio.appliance

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * The phone saying what happened, in a sentence.
 *
 * Uses the text-to-speech engine that ships with Android, which works offline
 * on Android 7. English first, since every word the app says is English; the
 * phone's own language when it has no English voice.
 */
object Voice {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Starts the engine, which takes a second or two the first time. */
    fun warmUp(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                tts = null
                return@TextToSpeech
            }
            val english = engine.setLanguage(Locale.UK)
            if (english == TextToSpeech.LANG_MISSING_DATA || english == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
            ready = true
            pending.forEach { say(it) }
            pending.clear()
        }
    }

    /** Speaks [line] after [delayMs], queued behind anything already being said. */
    fun speak(ctx: Context, line: String, delayMs: Long = 0) {
        warmUp(ctx)
        main.postDelayed({
            if (ready) say(line) else pending.add(line)
        }, delayMs)
    }

    private fun say(line: String) {
        try {
            tts?.speak(line, TextToSpeech.QUEUE_ADD, null, "bambuddy-${line.hashCode()}")
        } catch (e: Exception) {
            // An engine that dies mid-sentence costs the sentence, nothing more.
        }
    }

    /** True once an engine answered; false on a phone with no voice installed. */
    val available: Boolean get() = ready
}
