package com.mylo.browser.ai

import android.webkit.WebView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a page the way a person sees it: its title, address, visible text (`innerText`, which leaves out
 * hidden elements, form values and passwords) and the current selection. Only called for sources the
 * switchboard allowed; nothing is read otherwise.
 */
object PageReader {
    private const val SCRIPT = """(function(){
  var selection = '';
  try { selection = String(window.getSelection ? window.getSelection() : ''); } catch (e) {}
  var text = document.body ? document.body.innerText : '';
  return JSON.stringify({ title: document.title || '', url: location.href, text: text.slice(0, 60000), selection: selection.slice(0, 8000) });
})()"""

    data class Page(val url: String, val title: String, val text: String, val selection: String)

    /** The page in [view], or null if it couldn't be read within a few seconds. Main thread only. */
    suspend fun read(view: WebView): Page? = withTimeoutOrNull(4_000) {
        val raw = suspendCancellableCoroutine<String?> { done ->
            view.evaluateJavascript(SCRIPT) { result -> if (done.isActive) done.resume(result) }
        }
        runCatching {
            // evaluateJavascript returns the script's string result as a JSON string literal.
            val json = JSONObject(JSONArray("[$raw]").getString(0))
            Page(json.optString("url"), json.optString("title"), tidy(json.optString("text")), json.optString("selection").trim())
        }.getOrNull()
    }

    /** Collapses runs of blank lines and spaces so more of the page fits. */
    fun tidy(text: String): String = text.lines().map { it.trim().replace(Regex("[ \\t\\u00A0]+"), " ") }
        .fold(mutableListOf<String>()) { lines, line -> if (line.isNotEmpty() || lines.lastOrNull()?.isNotEmpty() == true) lines.add(line); lines }
        .joinToString("\n").trim()
}
