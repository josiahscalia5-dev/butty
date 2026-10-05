package com.mylo.browser.ai

/** Kinds of personal or secret text Mylo looks for before anything leaves the device. */
enum class SensitiveKind(val label: String, val placeholder: String) {
    Email("Email address", "[email]"),
    Phone("Phone number", "[phone]"),
    Card("Payment card number", "[card number]"),
    NationalId("ID number", "[ID number]"),
    Iban("Bank account (IBAN)", "[bank account]"),
    Address("Street address", "[address]"),
    SecretInLink("Secret in a link", "[link with secret removed]"),
}

data class SensitiveMatch(val kind: SensitiveKind, val start: Int, val end: Int, val text: String)

/**
 * Finds likely personal data (emails, phone numbers, card numbers, ID numbers, IBANs, street addresses and
 * secrets in links) so Mylo can offer to hide it before text or a capture is shared or sent to Mylo AI.
 * It is a careful helper, not a guarantee: card numbers must pass the Luhn check and IBANs their checksum to
 * keep false alarms low, and anything it misses is still the user's to review.
 */
object Redactor {
    private val email = Regex("""(?<![\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,24}(?![\w-])""")
    private val card = Regex("""(?<![\d-])(?:\d[ -]?){12,18}\d(?![\d-])""")
    private val ssn = Regex("""(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)""")
    private val iban = Regex("""(?<![A-Z0-9])[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?(?![A-Z0-9])""")
    private val phone = Regex("""(?<![\w+])(?:\+\d{1,3}[ .-]?)?(?:\(\d{2,4}\)[ .-]?|\d{2,4}[ .-])\d{3,4}[ .-]?\d{3,4}(?![\w])""")
    private val address = Regex(
        """(?i)\b\d{1,6}\s+(?:[A-Z][a-z]+\.?\s+){1,4}(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Way|Court|Ct|Place|Pl|Terrace|Parkway|Pkwy|Circle|Cir|Highway|Hwy)\b\.?(?:,?\s+(?:Apt|Apartment|Suite|Unit|#)\s*[\w-]+)?""",
    )
    private val secretLink = Regex("""(?i)https?://[^\s"'<>]+?[?&](?:token|access_token|id_token|key|api_key|apikey|secret|session|sessionid|sid|password|pass|auth|code|sig|signature)=[^\s&"'<>]+[^\s"'<>]*""")

    fun find(text: String): List<SensitiveMatch> {
        val found = mutableListOf<SensitiveMatch>()
        fun add(kind: SensitiveKind, r: IntRange) {
            if (found.none { r.first < it.end && it.start <= r.last }) found += SensitiveMatch(kind, r.first, r.last + 1, text.substring(r))
        }
        secretLink.findAll(text).forEach { add(SensitiveKind.SecretInLink, it.range) }
        email.findAll(text).forEach { add(SensitiveKind.Email, it.range) }
        card.findAll(text).filter { luhn(it.value) }.forEach { add(SensitiveKind.Card, it.range) }
        iban.findAll(text).filter { ibanValid(it.value) }.forEach { add(SensitiveKind.Iban, it.range) }
        ssn.findAll(text).forEach { add(SensitiveKind.NationalId, it.range) }
        phone.findAll(text).filter { it.value.count(Char::isDigit) in 9..15 && !looksLikeDate(it.value) }.forEach { add(SensitiveKind.Phone, it.range) }
        address.findAll(text).forEach { add(SensitiveKind.Address, it.range) }
        return found.sortedBy { it.start }
    }

    /** The text with every match (or only [kinds]) replaced by a readable placeholder. */
    fun redact(text: String, kinds: Set<SensitiveKind> = SensitiveKind.entries.toSet()): String {
        val matches = find(text).filter { it.kind in kinds }
        if (matches.isEmpty()) return text
        val out = StringBuilder()
        var at = 0
        for (m in matches) {
            out.append(text, at, m.start).append(m.kind.placeholder)
            at = m.end
        }
        return out.append(text, at, text.length).toString()
    }

    fun summary(matches: List<SensitiveMatch>): Map<SensitiveKind, Int> = matches.groupingBy { it.kind }.eachCount()

    internal fun luhn(value: String): Boolean {
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 13..19 || digits.all { it == digits[0] }) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    internal fun ibanValid(value: String): Boolean {
        val compact = value.replace(" ", "")
        if (compact.length !in 15..34) return false
        val rearranged = compact.drop(4) + compact.take(4)
        var remainder = 0
        for (c in rearranged) {
            val n = if (c.isDigit()) (c - '0').toString() else (c - 'A' + 10).toString()
            for (d in n) remainder = (remainder * 10 + (d - '0')) % 97
        }
        return remainder == 1
    }

    private fun looksLikeDate(value: String) = Regex("""^\d{4}[-./]\d{1,2}[-./]\d{1,2}$|^\d{1,2}[-./]\d{1,2}[-./]\d{2,4}$""").matches(value.trim())
}
