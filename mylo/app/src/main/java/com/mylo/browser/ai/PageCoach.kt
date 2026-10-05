package com.mylo.browser.ai

/** A price seen on a page, with the words around it ("Basic: $5 per month, one device"). */
data class PriceSeen(val amount: Double, val currency: String, val period: Period, val label: String) {
    enum class Period(val words: String) { Once(""), Month("a month"), Year("a year"), Week("a week") }

    /** Comparable per-month amount where the period is known; null for one-off prices. */
    val monthly: Double? get() = when (period) {
        Period.Month -> amount
        Period.Year -> amount / 12
        Period.Week -> amount * 52 / 12
        Period.Once -> null
    }
}

/** One tab's prices for the comparison. */
data class TabPrices(val title: String, val host: String, val prices: List<PriceSeen>) {
    val lowest: PriceSeen? get() = prices.filter { it.monthly != null }.minByOrNull { it.monthly!! } ?: prices.minByOrNull { it.amount }
}

/**
 * Small, on-phone helpers behind Compare tabs and Page Coach. Prices are read from the pages' own words (no AI);
 * steps come from the page's instructions or from Mylo AI's numbered answer. Both are heuristics and say so.
 */
object PageCoach {
    private val symbols = mapOf("$" to "USD", "US$" to "USD", "€" to "EUR", "£" to "GBP", "¥" to "JPY", "₹" to "INR", "A$" to "AUD", "C$" to "CAD")
    private val price = Regex("""(US\$|A\$|C\$|\$|€|£|¥|₹)\s?(\d{1,3}(?:[,.]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)|(?<![\d,.])(\d{1,3}(?:[,.]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s?(USD|EUR|GBP|dollars?|euros?)""", RegexOption.IGNORE_CASE)
    private val words = mapOf("five" to 5.0, "ten" to 10.0, "twelve" to 12.0, "twenty" to 20.0)

    /** Prices in [lines] (each a short piece of page text), at most [max], in page order. */
    fun prices(lines: List<String>, max: Int = 12): List<PriceSeen> = lines.flatMap { line ->
        price.findAll(line).mapNotNull { match ->
            val symbol = match.groupValues[1]
            val number = (match.groupValues[2].ifEmpty { match.groupValues[3] }).let(::parseAmount) ?: return@mapNotNull null
            val currency = symbols[symbol] ?: when (match.groupValues[4].lowercase().removeSuffix("s")) {
                "usd", "dollar" -> "USD"; "eur", "euro" -> "EUR"; "gbp" -> "GBP"; else -> return@mapNotNull null
            }
            PriceSeen(number, currency, periodOf(line.substring(match.range.last + 1).take(40)), line.trim().take(140))
        }.toList()
    }.distinctBy { it.amount to it.label }.take(max)

    private fun parseAmount(text: String): Double? {
        val cleaned = if (Regex("""\d[.,]\d{3}$""").containsMatchIn(text) || Regex("""\d[.,]\d{3}[.,]""").containsMatchIn(text)) text.replace(Regex("[,.](?=\\d{3})"), "") else text.replace(',', '.')
        return cleaned.toDoubleOrNull()?.takeIf { it > 0 }
    }

    private fun periodOf(after: String): PriceSeen.Period {
        val t = after.lowercase()
        return when {
            Regex("""^\s*(/|per|a|an|each)?\s*(mo\b|month|monthly)""").containsMatchIn(t) -> PriceSeen.Period.Month
            Regex("""^\s*(/|per|a|an|each)?\s*(yr\b|year|annually|annual)""").containsMatchIn(t) -> PriceSeen.Period.Year
            Regex("""^\s*(/|per|a|an|each)?\s*(wk\b|week|weekly)""").containsMatchIn(t) -> PriceSeen.Period.Week
            else -> PriceSeen.Period.Once
        }
    }

    /** One plain sentence comparing tabs' lowest prices, or null when fewer than two tabs show prices. */
    fun compare(tabs: List<TabPrices>): String? {
        val priced = tabs.filter { it.lowest != null }
        if (priced.size < 2) return null
        val comparable = priced.filter { it.lowest!!.monthly != null }
        val (cheapest, rest) = if (comparable.size >= 2) comparable.minBy { it.lowest!!.monthly!! } to comparable else priced.minBy { it.lowest!!.amount } to priced
        val currencies = rest.map { it.lowest!!.currency }.toSet()
        if (currencies.size > 1) return "These tabs show prices in different currencies (${currencies.joinToString(", ")}), so Mylo won't rank them."
        val low = cheapest.lowest!!
        return "The lowest price is on “${cheapest.title}” (${cheapest.host}): ${format(low)}" + if (low.monthly != null && comparable.size >= 2) ", compared per month." else "."
    }

    fun format(p: PriceSeen): String {
        val amount = if (p.amount % 1.0 == 0.0) p.amount.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, p.amount)
        val symbol = when (p.currency) { "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "JPY" -> "¥"; "INR" -> "₹"; else -> p.currency + " " }
        return symbol + amount + if (p.period != PriceSeen.Period.Once) " " + p.period.words else ""
    }

    /** A coaching step: what to do and the words to find on the page for it. */
    data class Step(val text: String, val target: String)

    /**
     * Steps from instructions: a numbered or bulleted list ("1. Open Account"), or one sentence of steps ("Open
     * Account, choose Plan, then Cancel plan."). The target is the step's object: quoted words, else its last words.
     */
    fun steps(instructions: String, max: Int = 8): List<Step> {
        val listed = instructions.lines().map { it.trim() }.filter { Regex("""^(\d+[.)]|[-•*])\s+\S""").containsMatchIn(it) }
            .map { it.replace(Regex("""^(\d+[.)]|[-•*])\s+"""), "") }
        val raw = listed.ifEmpty {
            instructions.split(Regex("""(?<=[.!?])\s+|\n+""")).firstOrNull { it.contains(',') || it.contains(" then ", ignoreCase = true) }
                ?.trimEnd('.', '!', '?')?.split(Regex(""",\s*(?:and\s+|then\s+)?|\s+then\s+""", RegexOption.IGNORE_CASE)).orEmpty()
        }
        return raw.map { it.trim().trimEnd('.') }.filter { it.length > 2 }.take(max).map { step ->
            val quoted = Regex("""[“"'‘]([^”"'’]{2,40})[”"'’]""").find(step)?.groupValues?.get(1)
            Step(step.replaceFirstChar { it.uppercase() }, quoted ?: targetOf(step))
        }
    }

    private val verbs = setOf("open", "go", "tap", "click", "press", "select", "choose", "pick", "find", "scroll", "to", "the", "your", "on", "then", "and", "a", "an", "into")

    private fun targetOf(step: String): String {
        val wordsLeft = step.split(Regex("""\s+""")).dropWhile { it.lowercase().trim(',', '.') in verbs }
        return wordsLeft.take(3).joinToString(" ").trim(',', '.', ' ').ifEmpty { step }
    }
}
