package com.ping.voiceime

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

    data class Suggestion(val matchedText: String, val replacement: String, val from: String)

    /**
     * Find near-miss ASR errors in [text]: substrings that are NOT an exact
     * dictionary key but are within edit-distance 1 of one. Exact matches are
     * skipped (nothing to suggest). Only keys of length 2+ are considered to
     * avoid noisy single-character matches.
     */
    fun findFuzzySuggestions(text: String, entries: Map<String, String>): List<Suggestion> {
        if (text.isEmpty() || entries.isEmpty()) return emptyList()
        val results = mutableListOf<Suggestion>()
        val seen = mutableSetOf<String>()
        for ((from, to) in entries) {
            if (from.length < 2 || from == to) continue
            // Compare windows of length from.length-1 .. from.length+1 to tolerate
            // one insertion/deletion in addition to one substitution.
            for (winLen in (from.length - 1)..(from.length + 1)) {
                if (winLen < 1 || winLen > text.length) continue
                for (start in 0..(text.length - winLen)) {
                    val window = text.substring(start, start + winLen)
                    if (window == from || !seen.add(window)) continue
                    if (levenshtein(window, from) == 1) {
                        results += Suggestion(window, to, from)
                    }
                }
            }
        }
        return results
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }
}
