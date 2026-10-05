package com.mylo.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerBlockerTest {
    private val blocker = TrackerBlocker()

    @Test fun subdomainsOfListedDomainsMatch() {
        assertEquals("doubleclick.net", blocker.match("stats.g.doubleclick.net"))
        assertEquals("doubleclick.net", blocker.match("DoubleClick.NET."))
        assertEquals("google-analytics.com", blocker.match("www.google-analytics.com"))
        assertNull(blocker.match("notdoubleclick.net"))
        assertNull(blocker.match("doubleclick.net.example.com"))
        assertNull(blocker.match("example.com"))
    }

    @Test fun thirdPartyTrackersAreBlockedAndCounted() {
        val blocked = blocker.shouldBlock("https://www.google-analytics.com/g/collect?v=2", "https://news.example.com/story", isMainFrame = false)
        assertNotNull(blocked)
        assertEquals(TrackerCategory.Analytics, blocked!!.category)
        assertEquals("news.example.com", blocked.pageHost)
        blocker.shouldBlock("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://news.example.com/", false)
        blocker.shouldBlock("https://ad.doubleclick.net/x", "https://news.example.com/", false)
        assertEquals(3, blocker.blockedCount)
        assertEquals("doubleclick.net" to 2, blocker.summary().first())
    }

    @Test fun pagesTheUserAskedForAlwaysLoad() {
        assertNull("main frame", blocker.shouldBlock("https://www.doubleclick.net/", "https://example.com/", isMainFrame = true))
        assertNull("own site", blocker.shouldBlock("https://connect.facebook.net/sdk.js", "https://www.facebook.com/", false))
        assertNull("same tracker domain", blocker.shouldBlock("https://static.hotjar.com/c/hotjar.js", "https://www.hotjar.com/pricing", false))
        assertNull("same company", blocker.shouldBlock("https://www.google-analytics.com/collect", "https://m.youtube.com/watch?v=1", false))
        assertNull("adservice on google.com", blocker.shouldBlock("https://adservice.google.com/ddm/fls", "https://www.google.com/search?q=x", false))
        assertEquals(0, blocker.blockedCount)
    }

    @Test fun functionalServicesAreNeverListed() {
        listOf("www.gstatic.com", "fonts.googleapis.com", "www.google.com", "accounts.google.com", "www.recaptcha.net",
            "js.stripe.com", "www.paypal.com", "i.ytimg.com", "www.youtube.com", "cdnjs.cloudflare.com", "hcaptcha.com",
            "static.xx.fbcdn.net", "abs.twimg.com", "cdn.jsdelivr.net").forEach {
            assertNull(it, blocker.match(it))
        }
    }

    @Test fun nonWebAndDisabledRequestsPass() {
        assertNull(blocker.shouldBlock("data:image/png;base64,AAAA", "https://example.com/", false))
        assertNull(blocker.shouldBlock("blob:https://example.com/123", "https://example.com/", false))
        blocker.enabled = false
        assertNull(blocker.shouldBlock("https://www.google-analytics.com/collect", "https://example.com/", false))
    }

    @Test fun ownersOnlyCoverTheirOwnSites() {
        assertNotNull(blocker.shouldBlock("https://connect.facebook.net/en_US/fbevents.js", "https://shop.example.com/", false))
        assertNotNull(blocker.shouldBlock("https://www.googletagmanager.com/gtag/js", "https://www.facebook.com/", false))
        TrackerList.owners.keys.forEach { assertTrue("$it is listed", it in TrackerList.domains) }
    }

    @Test fun unknownPageStillBlocksListedHosts() {
        assertNotNull(blocker.shouldBlock("https://bat.bing.com/bat.js", null, false))
    }

    @Test fun resetForgetsTheSession() {
        blocker.shouldBlock("https://www.google-analytics.com/collect", "https://example.com/", false)
        blocker.reset()
        assertEquals(0, blocker.blockedCount)
        assertTrue(blocker.summary().isEmpty())
    }

    @Test fun siteApproximation() {
        assertTrue(TrackerBlocker.sameSite("www.bbc.co.uk", "static.bbc.co.uk"))
        assertFalse(TrackerBlocker.sameSite("bbc.co.uk", "itv.co.uk"))
        assertTrue(TrackerBlocker.sameSite("a.example.com", "b.example.com"))
        assertEquals("example.com", TrackerBlocker.site("deep.a.example.com"))
    }

    @Test fun everyListedDomainIsWellFormed() {
        TrackerList.domains.keys.forEach { domain ->
            assertTrue(domain, domain == domain.lowercase() && domain.contains('.') && !domain.startsWith('.') && ' ' !in domain)
        }
    }
}
