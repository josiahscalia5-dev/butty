package com.mylo.browser.web

import java.net.URI
import java.net.URLDecoder

/**
 * Mylo's browser-engine policy, shared by every tab no matter which search provider (or link) opened it.
 * Pure Kotlin so the rules are unit-tested: what loads inside Mylo, what may open another app (and only
 * after a tap or a redirect, with confirmation for arbitrary apps), and what is never allowed.
 */
sealed interface NavigationDecision {
    /** Ordinary web navigation: stays inside Mylo. */
    data object LoadInMylo : NavigationDecision
    /** A link meant for another app; Mylo hands it over safely (see [ExternalTarget.needsConfirmation]). */
    data class OpenExternal(val target: ExternalTarget) : NavigationDecision
    /** Refused outright (unsafe scheme, or an app launch nobody tapped for). */
    data class Blocked(val why: String) : NavigationDecision
}

enum class ExternalKind(val label: String) {
    Email("email"), Phone("phone"), Message("messages"), Map("maps"), Store("an app store"), App("an app")
}

/**
 * A link for another app. For `intent:` links only the target URI, package and an http(s) fallback are
 * honoured; components, selectors and extras are dropped so a page can't reach non-browsable app internals.
 */
data class ExternalTarget(
    val kind: ExternalKind,
    /** The URI handed to Android (for `intent:` links, rebuilt from its scheme, host and path). */
    val uri: String,
    val scheme: String,
    val packageName: String? = null,
    /** An http(s) page to open in Mylo when no app can handle the link (`S.browser_fallback_url`). */
    val fallbackUrl: String? = null,
) {
    /** Email, phone, messages, maps and store links open the system's own chooser or dialer directly. */
    val needsConfirmation: Boolean get() = kind == ExternalKind.App
}

object WebPolicy {
    private val inMylo = setOf("http", "https")
    /** Never followed, from any page, in any frame. */
    private val never = setOf(
        "javascript", "file", "content", "data", "blob", "filesystem", "chrome", "chrome-extension",
        "view-source", "ws", "wss", "jar", "android-app", "about",
    )
    private val known = mapOf(
        "mailto" to ExternalKind.Email, "tel" to ExternalKind.Phone, "sms" to ExternalKind.Message,
        "smsto" to ExternalKind.Message, "mms" to ExternalKind.Message, "mmsto" to ExternalKind.Message,
        "geo" to ExternalKind.Map, "market" to ExternalKind.Store,
    )
    private val schemePattern = Regex("^[a-z][a-z0-9+.-]{0,63}$")

    /**
     * Decides a navigation the page (not the user's address bar) asked for.
     * [hasGesture]: the user tapped. [isRedirect]: a server redirect within a navigation the user started.
     */
    fun decide(url: String, isMainFrame: Boolean, hasGesture: Boolean, isRedirect: Boolean): NavigationDecision {
        val scheme = url.substringBefore(':', "").lowercase()
        if (url.equals("about:blank", ignoreCase = true) || url.startsWith("about:srcdoc", ignoreCase = true)) return NavigationDecision.LoadInMylo
        if (scheme in inMylo) return NavigationDecision.LoadInMylo
        if (scheme.isEmpty() || !schemePattern.matches(scheme) || scheme in never) {
            return NavigationDecision.Blocked("Mylo doesn't open $scheme: links from web pages")
        }
        // Another app may only be opened for the page the user is on, in response to a tap or redirect.
        if (!isMainFrame && !hasGesture) return NavigationDecision.Blocked("A frame tried to open another app")
        if (!hasGesture && !isRedirect) return NavigationDecision.Blocked("The page tried to open another app without a tap")
        if (scheme == "intent") return intentTarget(url)?.let { NavigationDecision.OpenExternal(it) }
            ?: NavigationDecision.Blocked("Malformed app link")
        return NavigationDecision.OpenExternal(ExternalTarget(known[scheme] ?: ExternalKind.App, url, scheme))
    }

    /** Parses Chrome's `intent://host/path#Intent;scheme=…;package=…;S.browser_fallback_url=…;end` form. */
    fun intentTarget(url: String): ExternalTarget? {
        if (!url.startsWith("intent:", ignoreCase = true)) return null
        val hash = url.indexOf("#Intent;")
        if (hash < 0 || !url.endsWith(";end")) return null
        val fields = url.substring(hash + "#Intent;".length, url.length - ";end".length).split(';')
            .mapNotNull { field -> field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val action = fields["action"]
        if (action != null && action != "android.intent.action.VIEW") return null
        val scheme = fields["scheme"]?.lowercase()?.takeIf { schemePattern.matches(it) && it !in never && it != "intent" }
        val packageName = fields["package"]?.takeIf { it.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")) }
        val fallback = fields["S.browser_fallback_url"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.takeIf { it.substringBefore(':', "").lowercase() in inMylo }
        val rest = url.substring("intent:".length, hash)
        if (scheme == null) {
            // No URI to view: the page's own fallback, or else the app's store listing.
            fallback?.let { return ExternalTarget(ExternalKind.App, it, "https", packageName, fallbackUrl = it) }
            return packageName?.let { ExternalTarget(ExternalKind.Store, "market://details?id=$it", "market", it) }
        }
        val uri = "$scheme:$rest"
        return ExternalTarget(known[scheme] ?: ExternalKind.App, uri, scheme, packageName, fallback)
    }
}

/** Web origins as Mylo keys site settings: `scheme://host[:port]`, lower case, default ports dropped. */
object Origins {
    fun of(url: String?): String? {
        val uri = runCatching { URI(url ?: return null) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port.takeIf { it != -1 && !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    fun host(url: String?): String = runCatching { URI(url ?: "").host }.getOrNull()?.removePrefix("www.") ?: "This site"

    /** Choices are remembered only for secure origins (https, or this device's own localhost). */
    fun rememberable(origin: String): Boolean =
        origin.startsWith("https://") || origin.startsWith("http://localhost") || origin.startsWith("http://127.0.0.1")
}

/** Safe file names for downloads: no paths, no control characters, a sensible length. */
object DownloadNames {
    fun sanitize(name: String?): String {
        val cleaned = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() && it !in "<>:\"|?*" }
            .trim().trimStart('.').take(120)
        return cleaned.ifEmpty { "download" }
    }
}

/**
 * Pages that refuse Android WebView by their own policy. Mylo does not disguise itself to get past them;
 * it explains the refusal and offers to open the page in another browser.
 *
 * Generic: any site whose address reports `disallowed_useragent` (the OAuth error code for refusing
 * embedded browsers). Site-specific, and documented in docs/compat/README.md: Google's sign-in host is the
 * only one whose page text is read, because its refusal page doesn't always say so in the address.
 * Nothing is read from any other page.
 */
object WebViewRefusals {
    data class Refusal(val message: String)

    private val google = Regex("(^|\\.)accounts\\.google\\.com$")

    fun hostNeedsCheck(url: String?): Boolean = runCatching { URI(url ?: "").host }.getOrNull()?.let { google.containsMatchIn(it) } == true

    /** From the address alone; safe to run on every page. */
    fun fromUrl(url: String?): Refusal? {
        val host = runCatching { URI(url ?: "").host }.getOrNull() ?: return null
        if (!url.orEmpty().contains("disallowed_useragent", ignoreCase = true)) return null
        return refusal(host)
    }

    fun detect(url: String?, pageText: String): Refusal? {
        fromUrl(url)?.let { return it }
        val host = runCatching { URI(url ?: "").host }.getOrNull() ?: return null
        if (google.containsMatchIn(host) &&
            (pageText.contains("disallowed_useragent") || pageText.contains("This browser or app may not be secure"))) {
            return refusal(host)
        }
        return null
    }

    private fun refusal(host: String): Refusal {
        val who = if (google.containsMatchIn(host)) "Google" else host.removePrefix("www.")
        return Refusal("$who doesn't allow signing in from in-app browsers such as Mylo's Android WebView. Open this page in another browser to continue there.")
    }
}
