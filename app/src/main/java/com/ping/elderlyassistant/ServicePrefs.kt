package com.ping.elderlyassistant

import android.content.Context

/**
 * Thin SharedPreferences wrapper for persisting service preferences.
 * BootReceiver reads [isEnabled] to decide whether to restart the service
 * after a device reboot. [getAsrEngine] / [setAsrEngine] let the user
 * switch between SenseVoice-Small and Qwen3-ASR-0.6B.
 */
object ServicePrefs {

    private const val PREFS_NAME    = "elder_assistant_prefs"
    private const val KEY_ENABLED   = "service_enabled"
    private const val KEY_ASR_ENGINE = "asr_engine"

    const val ASR_ENGINE_SENSEVOICE = "sensevoice"
    const val ASR_ENGINE_QWEN3      = "qwen3"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setAsrEngine(context: Context, engine: String) =
        prefs(context).edit().putString(KEY_ASR_ENGINE, engine).apply()

    fun getAsrEngine(context: Context): String =
        prefs(context).getString(KEY_ASR_ENGINE, ASR_ENGINE_SENSEVOICE) ?: ASR_ENGINE_SENSEVOICE
}
