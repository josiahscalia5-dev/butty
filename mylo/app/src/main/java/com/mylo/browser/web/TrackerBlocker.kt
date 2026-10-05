package com.mylo.browser.web

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Why a domain is on Mylo's tracker list. */
enum class TrackerCategory(val label: String) {
    Advertising("Advertising"),
    Analytics("Analytics"),
    Social("Social tracking"),
    Fingerprinting("Fingerprinting"),
}

/** One blocked request, for the session's "what was blocked" list. Never stored on disk. */
data class BlockedTracker(val domain: String, val category: TrackerCategory, val pageHost: String)

/**
 * Mylo's tracker blocking for the shared browser engine. A sub-resource request is blocked when its host is
 * (or is under) a domain on [TrackerList] and the page does not belong to that domain or its company
 * ([TrackerList.owners]), so a site keeps working when you visit it directly. Main-frame navigations are never blocked: the user always gets
 * the page they asked for.
 *
 * Thread-safe: WebView asks from its network thread.
 */
class TrackerBlocker(
    private val list: Map<String, TrackerCategory> = TrackerList.domains,
    private val owners: Map<String, Set<String>> = TrackerList.owners,
) {
    @Volatile var enabled: Boolean = true

    private val total = AtomicInteger(0)
    private val byDomain = ConcurrentHashMap<String, AtomicInteger>()
    private val categories = ConcurrentHashMap<String, TrackerCategory>()

    /** Requests blocked since the last [reset]. */
    val blockedCount: Int get() = total.get()

    /** The list domain a host falls under, or null. `ads.doubleclick.net` → `doubleclick.net`. */
    fun match(host: String): String? {
        var candidate = host.lowercase().trimEnd('.')
        while (true) {
            if (candidate in list) return candidate
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.lastIndex) return null
            candidate = candidate.substring(dot + 1)
        }
    }

    /**
     * Decides one request. [requestUrl] is what the page is loading and [pageUrl] the page in the tab (null
     * when not known yet; listed hosts are then blocked). Returns what was blocked, or null to let it load.
     */
    fun shouldBlock(requestUrl: String, pageUrl: String?, isMainFrame: Boolean): BlockedTracker? {
        if (!enabled || isMainFrame) return null
        val scheme = requestUrl.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = hostOf(requestUrl) ?: return null
        val listed = match(host) ?: return null
        val pageHost = pageUrl?.let(::hostOf)
        // First-party: the page is the tracker's own site, or another site of the same company.
        if (pageHost != null && (match(pageHost) == listed || sameSite(pageHost, host) || site(pageHost) in owners[listed].orEmpty())) return null
        val category = list.getValue(listed)
        total.incrementAndGet()
        byDomain.getOrPut(listed) { AtomicInteger(0) }.incrementAndGet()
        categories[listed] = category
        return BlockedTracker(listed, category, pageHost.orEmpty())
    }

    /** The most-blocked domains this session, highest first. */
    fun summary(limit: Int = 20): List<Pair<String, Int>> =
        byDomain.entries.map { it.key to it.value.get() }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).take(limit)

    fun categoryOf(domain: String): TrackerCategory? = categories[domain] ?: list[domain]

    /** Forgets every count (Burn Session). */
    fun reset() {
        total.set(0)
        byDomain.clear()
        categories.clear()
    }

    companion object {
        fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase()?.trimEnd('.') }.getOrNull()?.takeIf { it.isNotEmpty() }

        /** Same registrable site, approximately: the last two labels (three under common two-part suffixes). */
        fun sameSite(a: String, b: String): Boolean = site(a) == site(b)

        private val twoPartSuffixes = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "ne.jp", "or.jp",
            "com.br", "com.mx", "com.ar", "com.tr", "co.in", "co.za", "com.sg", "com.hk", "com.cn", "co.kr", "com.tw",
        )

        fun site(host: String): String {
            val labels = host.lowercase().trimEnd('.').split('.')
            if (labels.size <= 2) return labels.joinToString(".")
            val lastTwo = labels.takeLast(2).joinToString(".")
            return if (lastTwo in twoPartSuffixes) labels.takeLast(3).joinToString(".") else lastTwo
        }
    }
}
