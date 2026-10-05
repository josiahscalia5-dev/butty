package com.mylo.browser.ai

import com.mylo.browser.web.TrackerBlocker
import java.net.URI

/** What Mylo's page reader saw that matters for safety (never field values). */
data class PageSignals(
    val url: String,
    val text: String = "",
    val passwordFields: Int = 0,
    val cardFields: Int = 0,
    /** Hosts the page's forms send to. */
    val formTargets: List<String> = emptyList(),
    val externalScripts: Int = 0,
)

enum class Concern { Good, Caution, Warning }

data class Finding(val concern: Concern, val title: String, val detail: String)

/** A site check's result: the findings, worst first, and one plain summary line. */
data class SiteReport(val host: String, val findings: List<Finding>) {
    val level: Concern get() = findings.maxOfOrNull { it.concern } ?: Concern.Good
    val summary: String get() = when (level) {
        Concern.Warning -> "Be careful on $host: Mylo found red flags."
        Concern.Caution -> "Mostly fine, with things to watch on $host."
        Concern.Good -> "No red flags found on $host."
    }
}

/**
 * "Is this site safe?" on the phone: concrete checks of the address, the connection, the forms and the wording,
 * each explained. It can't prove a site is safe (no check can), and says so; Android's Safe Browsing still
 * blocks known dangerous sites, and Mylo AI can add its reading when it is connected.
 */
object SiteCheck {
    private val brands = listOf("paypal", "apple", "icloud", "microsoft", "office365", "outlook", "google", "gmail", "amazon", "netflix",
        "facebook", "instagram", "whatsapp", "chase", "wellsfargo", "citi", "hsbc", "barclays", "santander", "coinbase",
        "binance", "metamask", "irs", "hmrc", "usps", "fedex", "dhl", "ups", "steam", "roblox")
    private val riskyTlds = setOf("zip", "mov", "top", "xyz", "icu", "click", "gq", "tk", "ml", "cf", "ga", "rest", "cam", "buzz", "monster")
    private val pressure = listOf("account suspended", "account has been suspended", "account will be closed", "verify your account", "verify your identity",
        "unusual activity", "confirm your details", "act now", "within 24 hours", "immediately", "final notice", "you have won", "you've won",
        "claim your prize", "limited time", "your payment failed", "update your payment", "security alert")
    private val strangePayments = listOf("gift card", "itunes card", "google play card", "wire transfer", "western union", "moneygram",
        "bitcoin", "crypto wallet", "seed phrase", "recovery phrase", "private key")

    fun check(signals: PageSignals): SiteReport {
        val uri = runCatching { URI(signals.url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val findings = mutableListOf<Finding>()
        val secure = uri?.scheme.equals("https", ignoreCase = true)
        val site = TrackerBlocker.hostOf(signals.url)?.let { siteOf(it) } ?: host

        // Address
        if (host.split('.').any { it.startsWith("xn--") }) findings += Finding(Concern.Warning, "Unusual letters in the address",
            "The address uses international characters ($host), which can imitate a familiar name letter for letter.")
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || host.startsWith("[")) findings += Finding(Concern.Warning, "A bare number as the address",
            "Real shops and banks use names, not numbers like $host.")
        if (signals.url.substringAfter("://").substringBefore('/').contains('@')) findings += Finding(Concern.Warning, "A disguised address",
            "The link contains “@”, which can hide the real destination.")
        val words = host.split('.', '-')
        brands.firstOrNull { brand -> brand in words && !ownsBrand(site, brand) }?.let { brand ->
            findings += Finding(Concern.Warning, "A familiar name inside someone else’s address",
                "“$brand” appears in $host, but the site is $site. Real ${brand.replaceFirstChar { it.uppercase() }} pages don't live there.")
        }
        if (host.substringAfterLast('.') in riskyTlds) findings += Finding(Concern.Caution, "An address ending often used by short-lived sites",
            "Sites ending in .${host.substringAfterLast('.')} are more often used for scams. That alone doesn't make this one unsafe.")
        if (host.count { it == '.' } >= 4 || host.length > 45) findings += Finding(Concern.Caution, "A very long address",
            "Long addresses with many parts can hide where you really are: the site is $site.")

        // Connection and forms
        if (!secure) findings += Finding(if (signals.passwordFields + signals.cardFields > 0) Concern.Warning else Concern.Caution, "Not encrypted",
            "This page doesn't use HTTPS, so what you type could be seen on the network.")
        if (signals.passwordFields > 0 && !secure) findings += Finding(Concern.Warning, "Password field without encryption", "Never type a password on an unencrypted page.")
        if (signals.cardFields > 0) findings += Finding(if (secure) Concern.Caution else Concern.Warning, "Asks for card details",
            "Only enter card details on a site you trust and reached on your own, not through a message or ad.")
        signals.formTargets.map { it.lowercase() }.distinct().filter { target -> siteOf(target) != site }.forEach { target ->
            findings += Finding(Concern.Warning, "A form sends to another site", "What you type here goes to $target, not $site.")
        }

        // Wording
        val text = " " + signals.text.lowercase().replace(Regex("\\s+"), " ") + " "
        pressure.filter { it in text }.take(3).takeIf { it.isNotEmpty() }?.let { words ->
            findings += Finding(Concern.Caution, "Pressure to act quickly", "The page says ${words.joinToString(", ") { "“$it”" }}. Scams often rush people.")
        }
        strangePayments.filter { it in text }.take(3).takeIf { it.isNotEmpty() }?.let { words ->
            findings += Finding(Concern.Warning, "Unusual ways to pay or recover accounts",
                "The page mentions ${words.joinToString(", ") { "“$it”" }}. Real companies don't ask for these; never share a recovery phrase.")
        }

        if (findings.none { it.concern != Concern.Good }) {
            findings += Finding(Concern.Good, "Nothing suspicious found", "The address, connection, forms and wording look ordinary.")
        }
        if (secure) findings += Finding(Concern.Good, "Encrypted connection (HTTPS)", "Others on the network can't read what you send. It doesn't prove who runs the site.")
        findings += Finding(Concern.Good, "What Mylo can't check", "No check can prove a site is safe. Android's Safe Browsing still blocks known dangerous sites.")
        return SiteReport(site.ifEmpty { host }, findings.sortedByDescending { it.concern })
    }

    /** The registrable part of a host, using the tracker list's site rules (e.g. "secure-login.example.co.uk" → "example.co.uk"). */
    private fun siteOf(host: String): String = TrackerBlocker.site(host)

    private fun ownsBrand(site: String, brand: String): Boolean = site.substringBefore('.') == brand || site.startsWith("$brand.") ||
        (brand == "gmail" && site == "google.com") || (brand == "icloud" && site == "apple.com") || (brand == "office365" && site == "microsoft.com") ||
        (brand == "outlook" && (site == "live.com" || site == "microsoft.com"))
}
