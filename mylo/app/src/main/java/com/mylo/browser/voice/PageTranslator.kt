package com.mylo.browser.voice

import android.webkit.WebView
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray

/**
 * Translates the page on this phone with ML Kit's on-device translator: the page's text never leaves the phone.
 * A language pack (about 30 MB) is downloaded once per language, only after the person agrees. The page's own
 * text nodes are replaced in place, and the original comes back with [restore].
 */
object PageTranslator {
    /** Languages offered as targets, by ML Kit code. */
    val choices = listOf("en", "es", "fr", "de", "it", "pt", "nl", "zh", "ja", "ko", "hi", "ar", "ru", "tr", "pl", "vi", "id")

    fun name(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /** The phone's language as an ML Kit code, or English. */
    fun deviceLanguage(): String = TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

    /** The page's language (ML Kit code), or null when it can't be told. */
    suspend fun detect(sample: String): String? {
        if (sample.isBlank()) return null
        val tag = runCatching { LanguageIdentification.getClient().identifyLanguage(sample.take(2_000)).await() }.getOrNull()
        return tag?.takeIf { it != "und" }?.let { TranslateLanguage.fromLanguageTag(it) }
    }

    /** Whether translating [source] → [target] needs a language pack download first. */
    suspend fun needsDownload(source: String, target: String): Boolean {
        val manager = RemoteModelManager.getInstance()
        return listOf(source, target).filter { it != TranslateLanguage.ENGLISH }.any { code ->
            !runCatching { manager.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await() }.getOrDefault(false)
        }
    }

    /**
     * Translates the page in [view] from [source] to [target] (downloading the language pack if needed, which the
     * person has agreed to). Returns how many pieces of text were translated. Main thread.
     */
    suspend fun translate(view: WebView, source: String, target: String, onProgress: (done: Int, total: Int) -> Unit): Int {
        val texts = collect(view)
        if (texts.isEmpty()) return 0
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val out = JSONArray()
            texts.forEachIndexed { i, text ->
                val lead = text.takeWhile { it.isWhitespace() }
                val trail = text.takeLastWhile { it.isWhitespace() }
                val core = text.trim()
                out.put(if (core.isEmpty()) text else lead + runCatching { translator.translate(core).await() }.getOrDefault(core) + trail)
                if (i % 10 == 9 || i == texts.lastIndex) onProgress(i + 1, texts.size)
            }
            view.evaluate("(function(t){var n=window.__myloNodes||[];for(var i=0;i<n.length&&i<t.length;i++){n[i].nodeValue=t[i];}" +
                "document.documentElement.setAttribute('data-mylo-translated','1');return n.length;})(${out})")
            return texts.size
        } finally {
            translator.close()
        }
    }

    /** Puts the page's original text back. */
    suspend fun restore(view: WebView) {
        view.evaluate("(function(){var n=window.__myloNodes||[],o=window.__myloOriginal||[];for(var i=0;i<n.length&&i<o.length;i++){n[i].nodeValue=o[i];}" +
            "document.documentElement.removeAttribute('data-mylo-translated');return n.length;})()")
    }

    /** The page's visible text pieces (not scripts, styles or editable fields), at most 800. */
    private suspend fun collect(view: WebView): List<String> {
        val raw = view.evaluate("""(function(){
  var nodes = [], texts = [];
  if (!document.body) return '[]';
  var walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {acceptNode: function(n) {
    var p = n.parentElement; if (!p) return NodeFilter.FILTER_REJECT;
    var tag = p.tagName; if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'TEXTAREA' || p.isContentEditable) return NodeFilter.FILTER_REJECT;
    return n.nodeValue.trim().length > 1 ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
  }});
  var n; while ((n = walk.nextNode()) && nodes.length < 800) { nodes.push(n); texts.push(n.nodeValue); }
  window.__myloNodes = nodes; window.__myloOriginal = texts.slice();
  return JSON.stringify(texts);
})()""") ?: return emptyList()
        return runCatching {
            val array = JSONArray(JSONArray("[$raw]").getString(0))
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }
}

private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { done ->
    evaluateJavascript(script) { if (done.isActive) done.resume(it) }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { done ->
    addOnSuccessListener { if (done.isActive) done.resume(it) }
    addOnFailureListener { if (done.isActive) done.resumeWithException(it) }
    addOnCanceledListener { done.cancel() }
}
