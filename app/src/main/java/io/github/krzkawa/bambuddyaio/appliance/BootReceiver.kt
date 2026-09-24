package io.github.krzkawa.bambuddyaio.appliance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.krzkawa.bambuddyaio.ui.MainActivity

/**
 * Brings the dashboard back after the phone restarts or the app is updated.
 *
 * A power cut otherwise leaves the phone on its launcher until someone walks
 * over and opens the app. Starting an activity from here is allowed up to
 * Android 9, which covers the phone this is built for.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (!Appliance.of(context).startOnBoot) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            // Newer Android refuses background starts; he opens it by hand there.
        }
    }
}
