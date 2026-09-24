package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.krzkawa.bambuddyaio.nfc.SpoolTag

/**
 * What a scan feels like.
 *
 * A tag is read with the phone face down against the spool, so at the moment it
 * happens the screen is pointing away from the user. A buzz is the only feedback
 * that reaches him without turning the phone over: one short one when the
 * filament decoded, two when it did not.
 */
object ScanFeedback {

    /** Wait, buzz. One short pulse: the tag read. */
    private val GOOD = longArrayOf(0, 55)

    /** Wait, buzz, wait, buzz. Two pulses: nothing usable came off the tag. */
    private val BAD = longArrayOf(0, 45, 110, 45)

    /** Buzzes for the outcome of [tag]. Silent on a phone with no vibrator. */
    fun buzz(context: Context, tag: SpoolTag) {
        vibrate(context, if (tag.failure == null) GOOD else BAD)
    }

    /** The same two patterns for anything else done with the phone against a tag. */
    fun buzz(context: Context, ok: Boolean) {
        vibrate(context, if (ok) GOOD else BAD)
    }

    // VIBRATOR_SERVICE and the pattern overload are both deprecated in favour of
    // VibratorManager, which arrived in API 31. They still work, and on the Android 7
    // phone this app is built for they are the only path that does.
    @Suppress("DEPRECATION")
    private fun vibrate(context: Context, pattern: LongArray) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // -1 means play the pattern once rather than looping it.
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            // A phone that will not buzz is no reason to lose the scan.
        }
    }
}
