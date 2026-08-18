package com.ping.voiceime

import android.content.Context
import org.json.JSONObject

/**
 * Tracks how many times each UserDictionary entry (keyed by "from") has been
 * applied, so the candidate chip list can surface frequently-used words first.
 */
object DictUsage {

    private const val PREF_NAME = "dict_usage"
    private const val KEY_COUNTS = "counts"

    private fun load(context: Context): MutableMap<String, Int> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_COUNTS, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Int>()
            obj.keys().forEach { k -> map[k] = obj.getInt(k) }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun save(context: Context, counts: Map<String, Int>) {
        val obj = JSONObject()
        counts.forEach { (k, v) -> obj.put(k, v) }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_COUNTS, obj.toString()).apply()
    }

    fun recordUse(context: Context, from: String) {
        val counts = load(context)
        counts[from] = (counts[from] ?: 0) + 1
        save(context, counts)
    }

    fun countOf(context: Context, from: String): Int = load(context)[from] ?: 0

    fun allCounts(context: Context): Map<String, Int> = load(context)
}
