package org.thanatophilia.xyuime.ime

import android.content.Context
import org.json.JSONArray

/**
 * Most-recently-used symbol texts, newest first. Stored locally in the IME's private
 * preferences; nothing is uploaded. Bounded so the preference stays small.
 */
internal class SymbolRecentStore(context: Context) {
    private val prefs = context.getSharedPreferences("symbol_recent", Context.MODE_PRIVATE)

    fun list(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val text = array.optString(i)
                    if (text.isNotEmpty() && size < LIMIT) add(text)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Moves [text] to the front and persists the updated order. */
    fun record(text: String): List<String> {
        val updated = list().toMutableList().apply {
            remove(text)
            add(0, text)
            while (size > LIMIT) removeAt(size - 1)
        }
        prefs.edit().putString(KEY, JSONArray(updated).toString()).apply()
        return updated
    }

    private companion object {
        const val KEY = "items"
        const val LIMIT = 60
    }
}
