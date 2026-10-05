package com.mylo.browser.voice

/** What Voice Mode's page actions may do on the page on screen (all on the phone, no AI needed). */
interface PageHelper {
    /** Finds the first of [candidates] on the page, scrolls to it and marks it; the words found there, or null. */
    suspend fun show(candidates: List<String>): String?
    /** A short note shown on the page ("Mylo found the pricing section."). */
    fun note(message: String)
    /** A sample of the page's visible text, for telling its language. */
    suspend fun sample(): String?
    /** The page's translation state, or null when it shows the original. */
    val translated: Pair<String, String>?
    /** Translates on the phone; how many pieces of text were translated (0: nothing to translate). */
    suspend fun translate(source: String, target: String, onProgress: (Int, Int) -> Unit): Int
    suspend fun showOriginal()
}

/** Words that mark the sections the page actions look for, in order of preference. */
object PageTargets {
    val pricing = listOf("pricing", "plans and pricing", "plans", "prices", "price", "subscription", "cost")
    val cancel = listOf("cancel your plan", "cancel subscription", "cancel membership", "cancel", "unsubscribe", "close account", "end subscription")
}
