package com.mylo.browser.ai

import com.mylo.browser.web.TrackerBlocker

/** A form field as Mylo's page reader describes it (never its secret value). */
data class FieldInfo(val type: String = "text", val name: String = "", val label: String = "", val autocomplete: String = "", val filled: Boolean = false)

/** An element on the page, as described by Mylo's page reader. */
data class ElementInfo(
    val tag: String,
    val text: String = "",
    val type: String? = null,
    val href: String? = null,
    val download: Boolean = false,
    val inForm: Boolean = false,
    val formAction: String? = null,
    val formMethod: String? = null,
    val formFields: List<FieldInfo> = emptyList(),
)

/** What Mylo AI may ask the browser to do. The model never touches the WebView directly. */
sealed interface BrowserAction {
    /** Scroll to and highlight a part of the page the user asked for. */
    data class ScrollTo(val what: String) : BrowserAction
    data class Highlight(val what: String) : BrowserAction
    data class ReadAloud(val what: String) : BrowserAction
    /** Back in this tab's history ("Take me back"). */
    data object GoBack : BrowserAction
    /** A search the user asked for, with their saved provider. */
    data class Search(val query: String) : BrowserAction
    data class OpenLink(val url: String, val label: String) : BrowserAction
    data class Click(val element: ElementInfo) : BrowserAction
    data class Fill(val field: FieldInfo, val formAction: String?) : BrowserAction
    data class Submit(val element: ElementInfo) : BrowserAction
    data class Download(val url: String) : BrowserAction
    data class Upload(val what: String) : BrowserAction
    data class GrantPermission(val permission: String) : BrowserAction
}

/** What happens to a proposed action. */
sealed interface ActionVerdict {
    /** Ordinary, reversible browsing the user asked for: done directly. */
    data object Allow : ActionVerdict
    /** Consequential: shown to the user first, with exactly what would happen; runs only after Allow. */
    data class Preview(val summary: String, val details: List<String>, val destination: String?) : ActionVerdict
    /** Never done by Mylo AI (the user can still do it themselves). */
    data class Refuse(val reason: String) : ActionVerdict
}

/**
 * Mylo Action Preview's rules. Scrolling, highlighting, reading, going back, searching and opening a link on
 * the same site happen directly. Anything consequential (submitting or filling forms, purchases,
 * subscriptions, account changes, sharing personal information, downloads, uploads, site permissions, or a
 * button whose words say it does such a thing) stops for an explicit Allow, described in plain words such as
 * "Mylo is about to submit your email and ZIP code to example.com". Passwords and card numbers are never
 * typed by Mylo AI.
 */
object ActionGate {
    private val consequentialWords = listOf(
        "buy", "purchase", "order", "pay", "checkout", "check out", "place order", "subscribe", "upgrade", "start trial", "free trial",
        "sign up", "signup", "register", "create account", "join", "confirm", "submit", "send", "post", "publish", "share", "delete",
        "remove", "cancel", "unsubscribe", "close account", "deactivate", "save changes", "update", "change", "transfer", "donate",
        "book", "reserve", "apply", "accept", "agree", "allow", "install", "download", "upload", "log out", "logout", "sign out",
        "add to cart", "add to bag", "renew", "authorize", "approve",
    )
    private val neverTyped = setOf("password", "cc-number", "cc-csc", "cc-exp", "one-time-code")

    fun review(action: BrowserAction, pageUrl: String?): ActionVerdict {
        val site = pageUrl?.let(TrackerBlocker::hostOf)
        return when (action) {
            is BrowserAction.ScrollTo, is BrowserAction.Highlight, is BrowserAction.ReadAloud, BrowserAction.GoBack, is BrowserAction.Search -> ActionVerdict.Allow
            is BrowserAction.OpenLink -> {
                val host = TrackerBlocker.hostOf(action.url)
                when {
                    host == null || !(action.url.startsWith("https://") || action.url.startsWith("http://")) -> ActionVerdict.Refuse("Mylo only opens web links.")
                    site != null && TrackerBlocker.sameSite(site, host) && !mentionsConsequence(action.label) -> ActionVerdict.Allow
                    else -> ActionVerdict.Preview("Mylo is about to open ${action.label.ifBlank { host }} on $host.", listOf("Leaves ${site ?: "this page"} for $host."), host)
                }
            }
            is BrowserAction.Click -> click(action.element, site)
            is BrowserAction.Submit -> submit(action.element, site)
            is BrowserAction.Fill -> {
                val kind = fieldKind(action.field)
                if (action.field.type.equals("password", true) || action.field.autocomplete.lowercase() in neverTyped) {
                    ActionVerdict.Refuse("Mylo never types passwords, card numbers or security codes. You can enter them yourself.")
                } else {
                    val to = action.formAction?.let(TrackerBlocker::hostOf) ?: site
                    ActionVerdict.Preview("Mylo is about to fill in your $kind${to?.let { " for $it" }.orEmpty()}.", listOf("It will not be sent until you or Mylo submits the form."), to)
                }
            }
            is BrowserAction.Download -> ActionVerdict.Preview("Mylo is about to download a file from ${TrackerBlocker.hostOf(action.url) ?: "this site"}.", listOf(action.url.substringAfterLast('/').take(80)), TrackerBlocker.hostOf(action.url))
            is BrowserAction.Upload -> ActionVerdict.Preview("Mylo is about to upload ${action.what}${site?.let { " to $it" }.orEmpty()}.", emptyList(), site)
            is BrowserAction.GrantPermission -> ActionVerdict.Preview("Mylo is about to let ${site ?: "this site"} use your ${action.permission}.", listOf("You can change this later in Site settings."), site)
        }
    }

    private fun click(element: ElementInfo, site: String?): ActionVerdict {
        val words = element.text.trim()
        val isSubmit = element.type.equals("submit", true) || (element.tag.equals("button", true) && element.inForm && element.type == null)
        return when {
            isSubmit -> submit(element, site)
            element.download -> ActionVerdict.Preview("Mylo is about to download a file${site?.let { " from $it" }.orEmpty()}.", listOf("“$words”"), site)
            mentionsConsequence(words) -> ActionVerdict.Preview("Mylo is about to press “${words.take(60)}”${site?.let { " on $it" }.orEmpty()}.",
                listOf("This button may buy, subscribe, send, delete or change something."), site)
            element.href != null -> review(BrowserAction.OpenLink(element.href, words), site?.let { "https://$it/" })
            else -> ActionVerdict.Allow
        }
    }

    private fun submit(element: ElementInfo, site: String?): ActionVerdict {
        val to = element.formAction?.let(TrackerBlocker::hostOf) ?: site
        val shared = element.formFields.filter { it.filled }.map(::fieldKind).distinct()
        val what = when {
            shared.isEmpty() -> "this form"
            shared.size == 1 -> "your ${shared[0]}"
            else -> "your " + shared.dropLast(1).joinToString(", ") + " and " + shared.last()
        }
        val details = buildList {
            if (element.text.isNotBlank()) add("Button: “${element.text.trim().take(60)}”")
            if (to != null && site != null && !TrackerBlocker.sameSite(to, site)) add("The form sends to a different site than the page ($site).")
            if (element.formMethod.equals("get", true)) add("The details will appear in the page address.")
        }
        return ActionVerdict.Preview("Mylo is about to submit $what${to?.let { " to $it" }.orEmpty()}.", details, to)
    }

    fun mentionsConsequence(text: String): Boolean {
        val t = " " + text.lowercase().replace(Regex("[^a-z ]"), " ") + " "
        return consequentialWords.any { word -> t.contains(" $word ") }
    }

    /** A plain name for what a field holds. */
    fun fieldKind(field: FieldInfo): String {
        val hint = (field.autocomplete + " " + field.name + " " + field.label + " " + field.type).lowercase()
        return when {
            "email" in hint -> "email"
            "postal" in hint || "zip" in hint || "postcode" in hint -> "ZIP code"
            "tel" in hint || "phone" in hint || "mobile" in hint -> "phone number"
            "cc-" in hint || "card" in hint -> "card details"
            "password" in hint -> "password"
            "address" in hint || "street" in hint -> "address"
            "bday" in hint || "birth" in hint -> "date of birth"
            "name" in hint -> "name"
            "search" in hint || field.type.equals("search", true) -> "search words"
            else -> field.label.ifBlank { field.name }.ifBlank { "details" }.lowercase()
        }
    }
}
