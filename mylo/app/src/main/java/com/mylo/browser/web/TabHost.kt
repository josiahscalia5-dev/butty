package com.mylo.browser.web

import com.mylo.browser.BrowserTab

/** Where a [TabEngine]'s tabs live: the normal tab list ([com.mylo.browser.BrowserStore]) or a private session's. */
interface TabHost {
    /** A tab a page opened as a new window, placed next to its opener. */
    fun createChildTab(openerId: Long): BrowserTab
    fun updateTab(id: Long, url: String, title: String)
    fun closeTab(id: Long)
    /** A finished page load. Normal browsing records it in history; Private Mode only counts it. */
    fun recordVisit(url: String, title: String)
}

/** How an engine keeps website data. */
enum class EngineMode {
    /** Cookies (first- and third-party) and site storage persist, like Chrome. */
    Normal,
    /**
     * A private session in its own process and WebView data directory: first-party cookies and storage work
     * for the session only, third-party cookies are blocked, permissions are kept in memory, nothing goes to
     * history, and downloads are off. Everything is destroyed when the session burns.
     */
    Private,
}
