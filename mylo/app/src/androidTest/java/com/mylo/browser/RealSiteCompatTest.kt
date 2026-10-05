package com.mylo.browser

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real, unrelated websites on the live internet, reached the way people reach them: a search on each
 * provider, then a real tap on the result. Nothing here is site-specific in Mylo; these sites are only
 * examples proving the shared engine. Anti-bot pages (CAPTCHA, "unusual traffic") are recorded as
 * blocked by that site, never bypassed or counted as a pass.
 */
@RunWith(AndroidJUnit4::class)
class RealSiteCompatTest : CompatHarness() {

    /** One provider per run (instrumentation argument `provider`, e.g. GOOGLE), two unrelated sites each. */
    @Test fun searchResultsOpenInTheSharedEngine() {
        val provider = SearchProvider.valueOf(InstrumentationRegistry.getArguments().getString("provider") ?: "DUCKDUCKGO")
        case("provider-${provider.name.lowercase()}") {
            main { store.setProvider(provider) }
            note("provider", provider.displayName)
            val results = JSONArray()
            val problems = mutableListOf<String>()
            for ((query, host) in DESTINATIONS.getValue(provider)) {
                val record = JSONObject().put("query", query).put("expectedHost", host)
                results.put(record)
                runCatching { openResult(this, provider, query, host, record) }
                    .onFailure { record.put("outcome", it.message); problems += "$query: ${it.message}" }
            }
            note("results", results)
            assertTrue("Not every destination opened:\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }

    private fun openResult(case: Case, provider: SearchProvider, query: String, host: String, record: JSONObject) {
        val tag = host.substringBefore('.')
        openFromHome(query)
        val base = provider.domain.split('.').takeLast(2).joinToString(".")
        val resultsUrl = waitForPage(40_000) { url -> hostOf(url).let { h -> h == base || h.endsWith(".$base") } }
        record.put("resultsPage", resultsUrl)
        blockedBy(resultsUrl)?.let { case.shot("$tag-blocked"); throw AssertionError("${provider.displayName} answered with $it") }
        case.shot("$tag-results")
        val link = findResult(host) ?: throw AssertionError("No visible result for $host on ${provider.displayName}")
        record.put("tappedResult", link)
        val tabsBefore = tabIds()
        tapFound()
        val destination = waitForPage(40_000) { hostOf(it) == host || hostOf(it).endsWith(".$host") }
        record.put("destination", destination)
            .put("title", main { engine.page(visibleTab()!!).title })
            .put("openedInNewTab", tabIds().size > tabsBefore.size)
        blockedBy(destination)?.let { record.put("note", "destination showed $it") }
        record.put("fingerprint", JSONObject(js(CompatibilityMatrixTest.FINGERPRINT)!!).apply { remove("host") })
        record.put("outcome", "opened in Mylo")
        case.shot("$tag-destination")
        // The next search starts from a single tab, as a person returning Home would.
        if (tabIds().size > 1) main { tabIds().drop(1).forEach(engine::closeTab) }
    }

    /** Emergent.sh, as reported: tap "Get Started" and record what the site opens. */
    @Test fun emergentGetStarted() = case("emergent-get-started") {
        openFromHome("emergent.sh")
        waitForPage(45_000) { hostOf(it).endsWith("emergent.sh") }
        pump(2_500)
        shot("emergent-home")
        val label = findByText("^\\s*get started") ?: findByText("get started|start building|sign ?up|try (it )?free")
            ?: throw AssertionError("No Get Started button on ${pageUrl()}")
        note("tapped", label)
        observeAfterTap(this, "get-started")
        // If the site now offers sign-in options, follow the first common one to see the login window.
        findByText("continue with google|sign in with google|continue with github|sign in with github|log ?in|sign ?in")?.let {
            note("secondTap", it)
            observeAfterTap(this, "sign-in")
        }
    }

    /** A second, unrelated modern site whose sign-in opens a pop-up window. */
    @Test fun popupLoginSite() = case("popup-login-site") {
        val tried = JSONArray()
        for ((address, steps) in POPUP_SITES) {
            val attempt = JSONObject().put("site", address)
            tried.put(attempt)
            val outcome = runCatching {
                openFromHome(address)
                waitForPage(45_000) { hostOf(it).isNotEmpty() && !it.startsWith("about:") }
                pump(2_500)
                shot("${hostOf(pageUrl()).substringBefore('.')}-home")
                val opener = visibleTab()!!
                for (step in steps) {
                    if (tabIds().size > 1) break
                    val label = findByText(step) ?: continue
                    attempt.append("tapped", label)
                    tapFound()
                    pump(2_500)
                }
                waitFor("a sign-in pop-up tab", 15_000) { tabIds().size > 1 && openerOf(visibleTab()!!) == opener }
                val popupUrl = waitForPage(30_000) { it.startsWith("http") }
                attempt.put("popup", popupUrl).put("popupTitle", main { engine.page(visibleTab()!!).title })
                shot("${hostOf(popupUrl).substringBefore('.')}-popup")
                // Cancelling: Back closes the pop-up and the site's own tab is still there, intact.
                tapDesc("Back")
                waitFor("the original tab after closing the pop-up") { visibleTab() == opener && tabIds().size == 1 }
                attempt.put("afterClose", pageUrl())
                shot("back-on-${hostOf(pageUrl()).substringBefore('.')}")
            }
            attempt.put("outcome", outcome.exceptionOrNull()?.message ?: "pop-up opened as a Mylo tab and closed back to the site")
            if (outcome.isSuccess) break
            main { tabIds().drop(1).forEach(engine::closeTab) }
        }
        note("attempts", tried)
        assertTrue("No site produced a sign-in pop-up: $tried",
            (0 until tried.length()).any { tried.getJSONObject(it).has("popup") })
    }

    private fun observeAfterTap(case: Case, label: String) {
        val beforeUrl = pageUrl()
        val beforeTabs = tabIds()
        val beforeText = js("document.body?document.body.innerText.length:0")
        tapFound()
        val timeline = JSONArray()
        val start = SystemClock.elapsedRealtime()
        var shots = 0
        while (SystemClock.elapsedRealtime() - start < 20_000) {
            pump(2_000)
            timeline.put(tabsState().put("t", (SystemClock.elapsedRealtime() - start) / 1000))
            if (shots < 4 && (SystemClock.elapsedRealtime() - start) > shots * 5_000L) case.shot("$label-after-${++shots}")
        }
        case.note("$label-timeline", timeline)
        val changed = tabIds() != beforeTabs || pageUrl() != beforeUrl ||
            js("document.body?document.body.innerText.length:0") != beforeText || main { engine.prompts.isNotEmpty() }
        case.note("$label-changed", changed)
        assertTrue("Nothing happened after tapping $label", changed)
    }

    /** The visible result whose target (direct, or inside the provider's redirect link) is [host]. */
    private fun findResult(host: String): String? = js("""(function(){
        var want=${JSONObject.quote(host)};
        function hostOk(h){h=(h||'').toLowerCase();return h==want||h.slice(-want.length-1)=='.'+want;}
        function targets(a){
          var out=[a.href];
          try{out.push(decodeURIComponent(a.href));}catch(e){}
          try{var u=new URL(a.href);u.searchParams.forEach(function(v){
            if(/^a1[A-Za-z0-9_-]+/.test(v)){try{out.push(atob(v.slice(2).replace(/-/g,'+').replace(/_/g,'/')));}catch(e){}}
            out.push(v);});}catch(e){}
          return out;
        }
        function matches(a){return targets(a).some(function(t){var m=(t||'').match(/https?:\/\/[a-z0-9.-]+/ig)||[];
          return m.some(function(x){return hostOk(x.replace(/^https?:\/\//i,''));});});}
        function visible(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>30&&r.height>10&&s.visibility!='hidden'&&s.display!='none';}
        document.querySelectorAll('[data-mylo-target]').forEach(function(e){e.removeAttribute('data-mylo-target');});
        var hits=[].slice.call(document.querySelectorAll('a[href]')).filter(function(a){return visible(a)&&matches(a)&&(a.innerText||'').trim().length>2;});
        if(!hits.length)return null;
        hits[0].setAttribute('data-mylo-target','1');
        return (hits[0].innerText||'').replace(/\s+/g,' ').trim().slice(0,100)+' -> '+hits[0].href.slice(0,160);
    })()""")

    private fun blockedBy(url: String): String? {
        val text = js("(document.title+' '+(document.body?document.body.innerText.slice(0,3000):'')).toLowerCase()").orEmpty()
        return when {
            url.contains("/sorry/") || text.contains("unusual traffic") -> "an unusual-traffic CAPTCHA"
            text.contains("captcha") || text.contains("are you a robot") || text.contains("verify you are human") -> "a CAPTCHA / bot check"
            text.contains("access denied") && text.length < 600 -> "an access-denied page"
            else -> null
        }
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull().orEmpty()

    companion object {
        /** Unrelated sites; each is reached through at least two different providers. */
        val DESTINATIONS = mapOf(
            SearchProvider.GOOGLE to listOf("Wikipedia" to "wikipedia.org", "OpenStreetMap" to "openstreetmap.org"),
            SearchProvider.YAHOO to listOf("Wikipedia" to "wikipedia.org", "GitHub" to "github.com"),
            SearchProvider.BING to listOf("GitHub" to "github.com", "MDN Web Docs" to "developer.mozilla.org"),
            SearchProvider.BRAVE to listOf("MDN Web Docs" to "developer.mozilla.org", "OpenStreetMap" to "openstreetmap.org"),
            SearchProvider.DUCKDUCKGO to listOf("Python programming language" to "python.org", "Wikipedia" to "wikipedia.org"),
            SearchProvider.STARTPAGE to listOf("Python programming language" to "python.org", "GitHub" to "github.com"),
        )

        /** Sites whose sign-in opens a pop-up window; the first that does is recorded. Words to tap, in order. */
        val POPUP_SITES = listOf(
            "https://stackblitz.com" to listOf("^\\s*sign in\\s*$", "github"),
            "https://codesandbox.io/signin" to listOf("github"),
        )
    }
}
