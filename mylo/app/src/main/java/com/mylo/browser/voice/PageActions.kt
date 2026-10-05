package com.mylo.browser.voice

import android.webkit.WebView
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, reversible things Mylo may do on a page when asked: find a section (a heading first, then any visible
 * text), scroll it into view and mark it so the person sees where to look. Nothing is clicked, typed or sent.
 */
object PageActions {
    private const val FIND = """(function(q){
  q = String(q).toLowerCase();
  function shown(el){ var r = el.getBoundingClientRect(); var s = getComputedStyle(el); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden'; }
  var best = null;
  var heads = document.querySelectorAll('h1,h2,h3,h4,h5,h6,[id],[aria-label],summary,legend,th,dt');
  for (var i = 0; i < heads.length && !best; i++) {
    var h = heads[i], t = (h.innerText || '').trim().toLowerCase(), id = (h.id || '').toLowerCase(), label = (h.getAttribute('aria-label') || '').toLowerCase();
    if (((t && t.length < 120 && t.indexOf(q) >= 0) || id.indexOf(q) >= 0 || label.indexOf(q) >= 0) && shown(h)) best = h;
  }
  if (!best && document.body) {
    var walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), n;
    while ((n = walk.nextNode())) { if (n.nodeValue.toLowerCase().indexOf(q) >= 0 && n.parentElement && shown(n.parentElement)) { best = n.parentElement; break; } }
  }
  if (!best) return JSON.stringify({found: false});
  document.querySelectorAll('[data-mylo-highlight]').forEach(function(el){ el.style.outline = ''; el.style.background = ''; el.removeAttribute('data-mylo-highlight'); });
  var area = (best.tagName && /^H[1-6]$/.test(best.tagName) && best.parentElement && best.parentElement.tagName === 'SECTION') ? best.parentElement : best;
  area.scrollIntoView({behavior: 'smooth', block: 'center'});
  area.style.outline = '3px solid #8C7BFF'; area.style.background = 'rgba(140,123,255,0.16)'; area.setAttribute('data-mylo-highlight', '1');
  return JSON.stringify({found: true, text: (area.innerText || '').trim().slice(0, 600)});
})(%s)"""

    /** Finds [what] on the page in [view], scrolls to it and marks it; the text found, or null. Main thread only. */
    suspend fun show(view: WebView, what: String): String? {
        val raw = withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine<String?> { done -> view.evaluateJavascript(FIND.format(JSONObject.quote(what))) { if (done.isActive) done.resume(it) } }
        } ?: return null
        return runCatching {
            val json = JSONObject(JSONArray("[$raw]").getString(0))
            if (json.optBoolean("found")) json.optString("text") else null
        }.getOrNull()
    }
}
