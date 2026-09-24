package io.github.krzkawa.bambuddyaio.appliance

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Three short sounds, drawn in code rather than shipped as files.
 *
 * Rising for good news, three taps for "come and look", falling for trouble —
 * so he can tell from the next room which one it was before any voice speaks.
 * Played on the media stream, which is what the phone's volume keys set while
 * the app is open.
 */
object Chime {

    private const val RATE = 22_050

    /** A note: pitch in Hz, length in ms, and the silence after it. */
    private data class Note(val hz: Double, val ms: Int, val gapMs: Int = 0)

    private fun notes(tone: Events.Tone): List<Note> = when (tone) {
        // C5 E5 G5: a finished print.
        Events.Tone.GOOD -> listOf(Note(523.25, 150), Note(659.25, 150), Note(783.99, 320))
        // A5 three times: a pause or a dropped printer.
        Events.Tone.ATTENTION -> listOf(Note(880.0, 110, 90), Note(880.0, 110, 90), Note(880.0, 160))
        // G4 down to C4: a failure or a fault.
        Events.Tone.BAD -> listOf(Note(392.0, 260, 40), Note(261.63, 480))
    }

    /** How long [tone] lasts, so a spoken line can wait for it to finish. */
    fun lengthMs(tone: Events.Tone): Int = notes(tone).sumOf { it.ms + it.gapMs }

    /**
     * The samples for [tone] at [gain] (0..1), 16-bit mono.
     *
     * Each note is a sine with a little of its octave for warmth, a 6 ms
     * attack so it does not click, and an exponential fall like a struck bell.
     */
    fun samples(tone: Events.Tone, gain: Float): ShortArray {
        val list = notes(tone)
        val total = list.sumOf { (it.ms + it.gapMs) * RATE / 1000 }
        val out = ShortArray(total)
        var at = 0
        val attack = RATE * 6 / 1000
        for (note in list) {
            val n = note.ms * RATE / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-3.2 * i / n)
                val wave = sin(2 * PI * note.hz * t) * 0.8 + sin(4 * PI * note.hz * t) * 0.2
                out[at + i] = (wave * env * gain.coerceIn(0f, 1f) * Short.MAX_VALUE * 0.9).toInt().toShort()
            }
            at += n + note.gapMs * RATE / 1000
        }
        return out
    }

    /** Plays [tone]. Never throws: a phone that will not make a sound still shows the alert. */
    fun play(tone: Events.Tone, gain: Float) {
        try {
            val pcm = samples(tone, gain)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        } catch (e: Exception) {
            // Out of audio tracks, or no audio hardware at all.
        }
    }
}
