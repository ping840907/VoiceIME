package com.ping.elderlyassistant

import android.content.Context

/**
 * Thin SharedPreferences wrapper that persists whether the user has enabled
 * the voice-assistant service. BootReceiver reads this flag to decide whether
 * to restart the service after a device reboot.
 */
object ServicePrefs {

    private const val PREFS_NAME  = "elder_assistant_prefs"
    private const val KEY_ENABLED = "service_enabled"

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
}
