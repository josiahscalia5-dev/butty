package com.mylo.browser.ai

import com.mylo.browser.web.KeyValues

/** Browser data Mylo AI might use. Nothing is sent unless the switchboard allows it. */
enum class AiDataSource(val label: String, val explanation: String, val allowsAlways: Boolean = true) {
    CurrentPage("Current Page", "The text and address of the page on screen."),
    SelectedText("Selected Text", "Only the words you selected on the page."),
    Screenshot("Screenshot", "A picture of what's on screen, for questions about charts, layouts or images.", allowsAlways = false),
    OtherTabs("Other Tabs", "The titles, addresses and text of your other open tabs."),
    History("History", "Your recent browsing history in Mylo."),
    Location("Location", "Your approximate location from Android.", allowsAlways = false),
    MyloMemory("Saved Mylo Memory", "Things you asked Mylo to remember."),
}

/** How much a source is allowed: never, for the next request only, or until changed. */
enum class AiGrant { Off, Once, Always }

/**
 * Mylo's AI permission switchboard ("What Mylo can see"). Defaults to the minimum: only the current page,
 * which the user can turn off too. [Once] grants are used up by the next request that reads the source.
 * Private Mode passes no storage, so its choices last only for the session.
 */
class AiSwitchboard(private val store: KeyValues? = null) {
    private val grants = mutableMapOf<AiDataSource, AiGrant>()

    init {
        AiDataSource.entries.forEach { source ->
            val saved = store?.get(key(source))?.let { runCatching { AiGrant.valueOf(it) }.getOrNull() }
            grants[source] = saved?.takeIf { it != AiGrant.Once } ?: default(source)
        }
    }

    fun grant(source: AiDataSource): AiGrant = grants.getValue(source)

    fun allowed(source: AiDataSource): Boolean = grant(source) != AiGrant.Off

    /** Sets a source. "Always" is refused for sources that may only be shared one request at a time. */
    fun set(source: AiDataSource, grant: AiGrant) {
        val value = if (grant == AiGrant.Always && !source.allowsAlways) AiGrant.Once else grant
        grants[source] = value
        // A one-time grant is never persisted: it must not survive the request (or a restart).
        store?.put(key(source), if (value == AiGrant.Once) null else value.name)
    }

    /** Off ↔ on with the source's usual "on" (Always where allowed, otherwise Once). */
    fun toggle(source: AiDataSource) = set(source, if (allowed(source)) AiGrant.Off else if (source.allowsAlways) AiGrant.Always else AiGrant.Once)

    /**
     * The sources one request may read, from those it [wants]. One-time grants are consumed here, so they
     * can't be reused by a later request.
     */
    fun authorize(wants: Set<AiDataSource>): Set<AiDataSource> {
        val granted = wants.filter { allowed(it) }.toSet()
        granted.filter { grant(it) == AiGrant.Once }.forEach { grants[it] = AiGrant.Off }
        return granted
    }

    fun snapshot(): Map<AiDataSource, AiGrant> = grants.toMap()

    private fun key(source: AiDataSource) = "ai_grant_${source.name}"

    companion object {
        fun default(source: AiDataSource) = if (source == AiDataSource.CurrentPage) AiGrant.Always else AiGrant.Off
    }
}
