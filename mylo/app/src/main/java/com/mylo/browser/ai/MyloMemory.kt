package com.mylo.browser.ai

import android.content.Context
import org.json.JSONArray

/**
 * Things the person asked Mylo to remember ("I like short answers", "My plan is Family"). Kept on this phone
 * only; Mylo AI reads them only when Saved Mylo Memory is allowed in What Mylo can see. Private Mode never uses
 * this store.
 */
class MyloMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mylo_ai_memory", Context.MODE_PRIVATE)

    val items: List<String>
        get() = runCatching { JSONArray(prefs.getString(KEY, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())

    /** Adds [text] (trimmed, at most [MAX_CHARS]); false if empty, a duplicate or the list is full. */
    fun add(text: String): Boolean {
        val item = text.trim().take(MAX_CHARS)
        val now = items
        if (item.isEmpty() || item in now || now.size >= MAX_ITEMS) return false
        save(now + item)
        return true
    }

    fun remove(text: String) = save(items - text)

    fun clear() = save(emptyList())

    private fun save(list: List<String>) { prefs.edit().putString(KEY, JSONArray(list).toString()).apply() }

    companion object {
        private const val KEY = "items"
        const val MAX_ITEMS = 30
        const val MAX_CHARS = 300
    }
}
