package com.mylo.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPolicyTest {
    private fun decide(url: String, mainFrame: Boolean = true, gesture: Boolean = true, redirect: Boolean = false) =
        WebPolicy.decide(url, mainFrame, gesture, redirect)

    @Test fun webPagesStayInMylo() {
        listOf("https://example.com/a?b=c", "http://example.com", "HTTPS://Example.com", "about:blank").forEach {
            assertEquals(it, NavigationDecision.LoadInMylo, decide(it, gesture = false))
        }
    }

    @Test fun unsafeSchemesAreNeverFollowed() {
        listOf("javascript:alert(1)", "file:///sdcard/x", "content://contacts/1", "data:text/html,hi", "blob:https://a/b",
            "chrome://settings", "view-source:https://a", "about:config", "intent:#Intent;scheme=intent;end").forEach {
            assertTrue(it, decide(it) is NavigationDecision.Blocked)
        }
    }

    @Test fun otherAppsOpenOnlyAfterATapOrARedirect() {
        assertTrue(decide("tel:+15550100", gesture = false) is NavigationDecision.Blocked)
        assertTrue("Frames can't open apps on their own", decide("mailto:a@example.com", mainFrame = false, gesture = false, redirect = true) is NavigationDecision.Blocked)
        val tap = decide("tel:+15550100") as NavigationDecision.OpenExternal
        assertEquals(ExternalKind.Phone, tap.target.kind)
        assertFalse("The dialer only pre-fills; no confirmation needed", tap.target.needsConfirmation)
        val oauthReturn = decide("com.example.app:/oauth2redirect?code=1", gesture = false, redirect = true) as NavigationDecision.OpenExternal
        assertEquals(ExternalKind.App, oauthReturn.target.kind)
        assertTrue("Arbitrary apps are confirmed first", oauthReturn.target.needsConfirmation)
        mapOf("mailto:a@example.com" to ExternalKind.Email, "sms:+15550100" to ExternalKind.Message, "geo:0,0?q=cafe" to ExternalKind.Map,
            "market://details?id=com.example" to ExternalKind.Store).forEach { (url, kind) ->
            assertEquals(url, kind, (decide(url) as NavigationDecision.OpenExternal).target.kind)
        }
    }

    @Test fun intentLinksKeepOnlyTheirTargetPackageAndFallback() {
        val target = WebPolicy.intentTarget(
            "intent://maps.example.com/place?id=7#Intent;scheme=https;package=com.example.maps;" +
                "component=com.example.maps/.Internal;S.secret=x;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fplace;end")!!
        assertEquals("https://maps.example.com/place?id=7", target.uri)
        assertEquals("com.example.maps", target.packageName)
        assertEquals("https://example.com/place", target.fallbackUrl)
        assertTrue(target.needsConfirmation)
        assertNull("Non-VIEW actions are refused", WebPolicy.intentTarget("intent:#Intent;action=android.intent.action.CALL;scheme=tel;end"))
        assertNull("Fallbacks must be web pages", WebPolicy.intentTarget("intent://x#Intent;S.browser_fallback_url=javascript%3Aalert(1);end")?.fallbackUrl)
        val storeOnly = WebPolicy.intentTarget("intent:#Intent;package=com.example.app;end")!!
        assertEquals(ExternalKind.Store, storeOnly.kind)
        assertEquals("market://details?id=com.example.app", storeOnly.uri)
        assertNull(WebPolicy.intentTarget("intent://x#Intent;scheme=javascript;end"))
    }

    @Test fun originsAndHosts() {
        assertEquals("https://example.com", Origins.of("https://Example.com:443/path?q=1"))
        assertEquals("http://localhost:8080", Origins.of("http://localhost:8080/a"))
        assertNull(Origins.of("mailto:a@example.com"))
        assertEquals("example.com", Origins.host("https://www.example.com/x"))
        assertTrue(Origins.rememberable("https://example.com"))
        assertTrue(Origins.rememberable("http://localhost:8080"))
        assertFalse("Plain-HTTP sites are asked every time", Origins.rememberable("http://example.com"))
    }

    @Test fun downloadNamesAreSafe() {
        assertEquals("passwd", DownloadNames.sanitize("../../etc/passwd"))
        assertEquals("report.pdf", DownloadNames.sanitize("C:\\temp\\report.pdf"))
        assertEquals("download", DownloadNames.sanitize("..."))
        assertEquals("ab.txt", DownloadNames.sanitize("a\u0000b.txt"))
    }

    @Test fun onlyListedRefusalsAreDetected() {
        assertTrue(WebViewRefusals.hostNeedsCheck("https://accounts.google.com/signin/oauth/error?authError=x"))
        assertFalse(WebViewRefusals.hostNeedsCheck("https://example.com/"))
        assertTrue(WebViewRefusals.detect("https://accounts.google.com/signin/oauth/error", "Error 403: disallowed_useragent") != null)
        assertNull(WebViewRefusals.detect("https://example.com/", "disallowed_useragent"))
    }

    @Test fun sitePermissionsAreRememberedOnlyForSecureOrigins() {
        val map = mutableMapOf<String, String>()
        val store = SitePermissionStore(object : KeyValues {
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
            override fun keys() = map.keys
        })
        store.remember("https://meet.example", SitePermission.Camera, SiteDecision.Allow)
        store.remember("https://meet.example", SitePermission.Location, SiteDecision.Block)
        store.remember("http://plain.example", SitePermission.Camera, SiteDecision.Allow)
        assertEquals(SiteDecision.Allow, store.decision("https://meet.example", SitePermission.Camera))
        assertNull(store.decision("http://plain.example", SitePermission.Camera))
        assertEquals(mapOf(SitePermission.Camera to SiteDecision.Allow, SitePermission.Location to SiteDecision.Block), store.forOrigin("https://meet.example"))
        store.clear("https://meet.example")
        assertTrue(store.forOrigin("https://meet.example").isEmpty())
    }
}
