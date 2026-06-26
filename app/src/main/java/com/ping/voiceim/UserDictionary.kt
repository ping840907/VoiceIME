package com.ping.voiceim

import android.content.Context
import org.json.JSONObject

/**
 * Stores user-defined ASR post-correction entries in SharedPreferences.
 *
 * Each entry maps a recognition error (or shorthand) → the desired output.
 * Applied after ASR + OpenCC, longest-match-first to avoid partial overlaps.
 *
 * Storage format: JSON object  {"wrong": "correct", ...}
 */
object UserDictionary {

    private const val PREF_NAME = "user_dict"
    private const val KEY_ENTRIES = "entries"

    fun load(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(context: Context, entries: Map<String, String>) {
        val obj = JSONObject()
        entries.forEach { (k, v) -> obj.put(k, v) }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, obj.toString()).apply()
    }

    fun add(context: Context, from: String, to: String) {
        val entries = load(context).toMutableMap()
        entries[from] = to
        save(context, entries)
    }

    fun remove(context: Context, from: String) {
        val entries = load(context).toMutableMap()
        entries.remove(from)
        save(context, entries)
    }

    /** Apply dictionary substitutions to [text], longest key first. */
    fun apply(text: String, entries: Map<String, String>): String {
        if (entries.isEmpty()) return text
        var result = text
        entries.entries.sortedByDescending { it.key.length }.forEach { (from, to) ->
            result = result.replace(from, to)
        }
        return result
    }
}
