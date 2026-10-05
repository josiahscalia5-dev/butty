package com.mylo.browser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mylo.browser.web.TabHost
import java.net.IDN
import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

enum class SearchProvider(val displayName: String, val domain: String, private val queryPrefix: String) {
    GOOGLE("Google", "google.com", "https://www.google.com/search?q="),
    BRAVE("Brave", "search.brave.com", "https://search.brave.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "duckduckgo.com", "https://duckduckgo.com/?q="),
    BING("Bing", "bing.com", "https://www.bing.com/search?q="),
    YAHOO("Yahoo", "search.yahoo.com", "https://search.yahoo.com/search?p="),
    STARTPAGE("Startpage", "startpage.com", "https://www.startpage.com/sp/search?query=");

    fun searchUrl(query: String): String = queryPrefix + URLEncoder.encode(query, "UTF-8")
}

/**
 * Resolves an address-bar submission to an HTTP(S) URL, or null for an empty or
 * unsafe address. Other URI schemes are never handed to WebView or an Intent.
 */
fun resolveInput(input: String, provider: SearchProvider = SearchProvider.DUCKDUCKGO): String? {
    val value = input.trim()
    if (value.isEmpty() || value.any { it.code < 32 || it.code == 127 }) return null

    // A host with a port must not be confused with a URI scheme.
    val hostWithPort = Regex("^(?:[^\\s/:]+[.。．｡][^\\s/:]+|localhost):[0-9]+(?:[/\\?#].*)?$", RegexOption.IGNORE_CASE)
        .matches(value)
    val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(value)?.groupValues?.get(1)
    if (scheme != null && !hostWithPort) {
        if (scheme.equals("https", true) || scheme.equals("http", true)) return normalizeWebUrl(value)
        // Search operators and ordinary prose can contain a colon. Keep explicit
        // non-web addresses blocked, but allow queries such as "weather: London".
        val blockedSchemes = setOf(
            "javascript", "data", "file", "content", "intent", "about", "blob",
            "ftp", "ftps", "ws", "wss", "mailto", "tel", "sms", "smsto", "mms",
            "mmsto", "geo", "market", "android-app", "chrome", "chrome-extension",
            "view-source"
        )
        if (scheme.lowercase() in blockedSchemes || value.substringAfter(':').startsWith("//")) return null
        return provider.searchUrl(value)
    }

    if (!value.any(Char::isWhitespace)) {
        val authority = value.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringBefore(':')
        val looksLikeHost = !authority.contains('@') &&
            (host.equals("localhost", true) || host.any { it in ".。．｡" } || authority.startsWith('['))
        if (looksLikeHost) {
            normalizeWebUrl("https://$value")?.let { return it }
        }
    }
    return provider.searchUrl(value)
}

private fun normalizeWebUrl(value: String): String? = runCatching {
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "https" && scheme != "http") return null
    val authority = uri.rawAuthority ?: return null
    if (authority.isBlank() || authority.contains('@') || authority.contains('\\')) return null

    // URI.host is null for internationalized domains; normalize only the host,
    // preserving the user's encoded path, query, and fragment verbatim.
    val normalizedAuthority = if (authority.startsWith("[")) {
        if (uri.host == null) return null
        val portSuffix = authority.substringAfter(']', "")
        if (portSuffix.isNotEmpty() &&
            (!portSuffix.startsWith(':') || portSuffix.drop(1).toIntOrNull()?.let { it in 1..65535 } != true)) return null
        authority.lowercase()
    } else {
        val host = authority.substringBefore(':')
        val portSuffix = authority.substringAfter(':', "")
        if (authority.contains(':') &&
            (portSuffix.toIntOrNull()?.let { it in 1..65535 } != true)) return null
        val asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
        if (asciiHost.isBlank() || asciiHost.startsWith('.') || asciiHost.endsWith("..")) return null
        asciiHost + if (portSuffix.isNotEmpty()) ":$portSuffix" else ""
    }
    buildString {
        append(scheme)
        append("://")
        append(normalizedAuthority)
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { append('?'); append(it) }
        uri.rawFragment?.let { append('#'); append(it) }
    }.let { URI(it).toASCIIString() }
}.getOrNull()

/** The search provider saved in Settings, read on its own (Private Mode reads nothing else of normal browsing). */
fun savedSearchProvider(context: Context): SearchProvider =
    SearchProvider.entries.firstOrNull { it.name == context.applicationContext.getSharedPreferences("mylo_browser", Context.MODE_PRIVATE).getString("provider", null) }
        ?: SearchProvider.DUCKDUCKGO

data class Bookmark(val url: String, val title: String, val addedAt: Long)
data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)
/** [openerId]: the tab whose page opened this one as a new window (a pop-up or target=_blank link). */
data class BrowserTab(val id: Long, val title: String, val url: String, val privateMode: Boolean, val openerId: Long? = null)

/**
 * Small local browser model for normal browsing. Bookmarks, history and the selected search provider
 * survive app restarts; tabs remain in memory. Private Mode has its own [com.mylo.browser.privacy.PrivateTabs]
 * in a separate process and never writes here. Only call its mutation methods on the main thread.
 */
class BrowserStore(context: Context) : TabHost {
    private val preferences = context.applicationContext.getSharedPreferences("mylo_browser", Context.MODE_PRIVATE)
    private var nextTabId = 1L

    private var selectedProvider by mutableStateOf(
        SearchProvider.entries.firstOrNull { it.name == preferences.getString("provider", null) }
            ?: SearchProvider.DUCKDUCKGO
    )
    val provider: SearchProvider get() = selectedProvider

    val bookmarks = mutableStateListOf<Bookmark>()
    val history = mutableStateListOf<HistoryEntry>()
    val tabs = mutableStateListOf<BrowserTab>()

    init {
        readArray("bookmarks").forEach { item ->
            val url = item.optString("url")
            if (normalizeWebUrl(url) != null) {
                bookmarks.add(Bookmark(url, item.optString("title", url), item.optLong("addedAt")))
            }
        }
        readArray("history").take(MAX_HISTORY).forEach { item ->
            val url = item.optString("url")
            if (normalizeWebUrl(url) != null) {
                history.add(HistoryEntry(url, item.optString("title", url), item.optLong("visitedAt")))
            }
        }
    }

    fun setProvider(value: SearchProvider) {
        selectedProvider = value
        preferences.edit().putString("provider", value.name).apply()
    }

    fun addBookmark(url: String, title: String) {
        val normalized = normalizeWebUrl(url) ?: return
        bookmarks.removeAll { it.url == normalized }
        bookmarks.add(0, Bookmark(normalized, title.trim().ifEmpty { normalized }, System.currentTimeMillis()))
        persistBookmarks()
    }

    fun removeBookmark(url: String) {
        val normalized = normalizeWebUrl(url) ?: url
        bookmarks.removeAll { it.url == normalized }
        persistBookmarks()
    }

    override fun recordVisit(url: String, title: String) = recordVisit(url, title, privateMode = false)

    fun recordVisit(url: String, title: String, privateMode: Boolean) {
        if (privateMode) return
        val normalized = normalizeWebUrl(url) ?: return
        history.removeAll { it.url == normalized }
        history.add(0, HistoryEntry(normalized, title.trim().ifEmpty { normalized }, System.currentTimeMillis()))
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        persistHistory()
    }

    fun clearHistory() {
        history.clear()
        preferences.edit().remove("history").apply()
    }

    fun createTab(url: String = "", privateMode: Boolean = false): BrowserTab {
        val safeUrl = if (url.isBlank()) "" else normalizeWebUrl(url) ?: ""
        val tab = BrowserTab(nextTabId++, if (privateMode) "Private tab" else "New tab", safeUrl, privateMode)
        tabs.add(tab)
        return tab
    }

    /** A tab a page opened as a new window; it sits next to its opener, which stays open behind it. */
    override fun createChildTab(openerId: Long): BrowserTab {
        val tab = BrowserTab(nextTabId++, "New window", "", privateMode = false, openerId = openerId)
        val index = tabs.indexOfFirst { it.id == openerId }
        if (index < 0) tabs.add(tab) else tabs.add(index + 1, tab)
        return tab
    }

    /**
     * Address-bar submissions navigate the active tab, including an existing webpage. Words search
     * with the provider saved in Settings; web addresses open directly.
     */
    fun navigateInCurrentTab(currentTabId: Long?, input: String): BrowserTab? {
        val url = resolveInput(input, provider) ?: return null
        val index = tabs.indexOfFirst { it.id == currentTabId }
        if (index < 0) return createTab(url)
        val tab = tabs[index].copy(url = url, title = url)
        tabs[index] = tab
        return tab
    }

    override fun updateTab(id: Long, url: String, title: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val safeUrl = normalizeWebUrl(url) ?: return
        tabs[index] = tabs[index].copy(url = safeUrl, title = title.trim().ifEmpty { safeUrl })
    }

    override fun closeTab(id: Long) {
        tabs.removeAll { it.id == id }
    }

    private fun readArray(key: String): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun persistBookmarks() {
        val array = JSONArray()
        bookmarks.forEach { item ->
            array.put(JSONObject().put("url", item.url).put("title", item.title).put("addedAt", item.addedAt))
        }
        preferences.edit().putString("bookmarks", array.toString()).apply()
    }

    private fun persistHistory() {
        val array = JSONArray()
        history.forEach { item ->
            array.put(JSONObject().put("url", item.url).put("title", item.title).put("visitedAt", item.visitedAt))
        }
        preferences.edit().putString("history", array.toString()).apply()
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}
