package com.mylo.browser.voice

import com.mylo.browser.ai.ActionGate
import com.mylo.browser.ai.ActionVerdict
import com.mylo.browser.ai.BrowserAction
import org.json.JSONObject

/** What happens to a voice tool call, after Action Preview's rules. */
sealed interface ToolPlan {
    /** Ordinary browsing the person asked for: done directly. */
    data class Run(val action: BrowserAction) : ToolPlan
    /** Consequential: shown to the person first; runs only after Allow. */
    data class Ask(val action: BrowserAction, val preview: ActionVerdict.Preview) : ToolPlan
    data class Refuse(val reason: String) : ToolPlan
}

/**
 * The browser tools a realtime voice model may call (the same names the Mylo AI service declares), mapped onto
 * [BrowserAction]s and judged by [ActionGate]. The model never touches the page itself.
 */
object BrowserTools {
    fun action(name: String, args: JSONObject): BrowserAction? {
        fun arg(key: String) = args.optString(key).trim().take(300)
        return when (name) {
            "scroll_to" -> BrowserAction.ScrollTo(arg("target"))
            "highlight" -> BrowserAction.Highlight(arg("target"))
            "find" -> BrowserAction.Highlight(arg("query"))
            "read_aloud" -> BrowserAction.ReadAloud(arg("target"))
            "go_back" -> BrowserAction.GoBack
            "search" -> BrowserAction.Search(arg("query"))
            "open_link" -> BrowserAction.OpenLink(args.optString("target").trim(), arg("label"))
            else -> null
        }
    }

    fun plan(name: String, args: JSONObject, pageUrl: String?): ToolPlan {
        val action = action(name, args) ?: return ToolPlan.Refuse(
            if (name == "translate") "Translating pages isn't available in this version yet." else "Mylo can't do that from voice yet.")
        val empty = when (action) {
            is BrowserAction.ScrollTo -> action.what.isEmpty()
            is BrowserAction.Highlight -> action.what.isEmpty()
            is BrowserAction.Search -> action.query.isEmpty()
            is BrowserAction.OpenLink -> action.url.isEmpty()
            else -> false
        }
        if (empty) return ToolPlan.Refuse("The request didn't say what to look for.")
        val needsPage = action !is BrowserAction.Search && action !is BrowserAction.ReadAloud
        if (needsPage && pageUrl == null) return ToolPlan.Refuse("No web page is open.")
        return when (val verdict = ActionGate.review(action, pageUrl)) {
            ActionVerdict.Allow -> ToolPlan.Run(action)
            is ActionVerdict.Preview -> ToolPlan.Ask(action, verdict)
            is ActionVerdict.Refuse -> ToolPlan.Refuse(verdict.reason)
        }
    }

    /** The arguments of a suggestion from a typed answer, in the voice tools' shape. */
    fun arguments(proposal: com.mylo.browser.ai.AiEvent.Proposal): JSONObject = JSONObject().apply {
        put("target", proposal.target); put("label", proposal.target)
        proposal.query?.let { put("query", it); put("language", it) }
        if (proposal.query == null && proposal.type in setOf("find", "search")) put("query", proposal.target)
    }

    /** A button label for a suggested action. */
    fun label(proposal: com.mylo.browser.ai.AiEvent.Proposal): String {
        val what = proposal.target.ifBlank { proposal.query.orEmpty() }.take(40)
        return when (proposal.type) {
            "scroll_to" -> "Show me “$what”"
            "highlight", "find" -> "Mark “$what” on the page"
            "read_aloud" -> "Show “$what”"
            "go_back" -> "Go back"
            "search" -> "Search for “${proposal.query ?: what}”"
            "open_link" -> "Open the link"
            "translate" -> "Translate this page"
            else -> "Do this"
        }
    }

    /** What the model is told happened. */
    fun result(ok: Boolean, detail: String): JSONObject = JSONObject().put("ok", ok).put("detail", detail)
}
