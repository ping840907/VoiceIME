package com.ping.elderlyassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts FloatingBubbleService after device reboot — but ONLY if the user had
 * explicitly started the service before the reboot (checked via [ServicePrefs]).
 *
 * Without this guard, the service would start on the very first boot even before
 * the user opens the app and grants permissions.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!ServicePrefs.isEnabled(context)) {
            Log.d("BootReceiver", "Service not enabled by user — skipping auto-start")
            return
        }

        Log.i("BootReceiver", "Boot complete — restarting FloatingBubbleService")
        val svc = Intent(context, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
