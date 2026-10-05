package com.mylo.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteCheckTest {
    private fun titles(report: SiteReport) = report.findings.filter { it.concern != Concern.Good }.map { it.title }

    @Test fun anOrdinarySecurePageHasNoRedFlags() {
        val report = SiteCheck.check(PageSignals("https://www.paypal.com/signin", "Log in to your account", passwordFields = 1, formTargets = listOf("www.paypal.com")))
        assertEquals(Concern.Good, report.level)
        assertEquals("paypal.com", report.host)
        assertTrue(report.findings.any { it.title == "What Mylo can't check" })
        assertEquals(Concern.Good, SiteCheck.check(PageSignals("https://groups.google.com/g/news")).level)
        assertEquals(Concern.Good, SiteCheck.check(PageSignals("https://www.bankofamerica.com/")).level)
    }

    @Test fun aLookalikePhishingPageIsFlagged() {
        val report = SiteCheck.check(PageSignals("http://paypal.com.secure-login.xyz/verify",
            "Your account has been suspended. Verify your account within 24 hours or pay with a gift card.",
            passwordFields = 1, cardFields = 1, formTargets = listOf("collect.example.net")))
        assertEquals(Concern.Warning, report.level)
        val found = titles(report)
        listOf("A familiar name inside someone else’s address", "Not encrypted", "Password field without encryption", "Asks for card details",
            "A form sends to another site", "Pressure to act quickly", "Unusual ways to pay or recover accounts").forEach {
            assertTrue("$it in $found", it in found)
        }
        assertTrue(report.summary.startsWith("Be careful on secure-login.xyz"))
    }

    @Test fun disguisedAddressesAreFlagged() {
        assertTrue("Unusual letters in the address" in titles(SiteCheck.check(PageSignals("https://xn--pypal-4ve.com/"))))
        assertTrue("A bare number as the address" in titles(SiteCheck.check(PageSignals("https://203.0.113.9/login"))))
        assertTrue("A disguised address" in titles(SiteCheck.check(PageSignals("https://www.apple.com@evil.example/"))))
        assertEquals(Concern.Caution, SiteCheck.check(PageSignals("https://free-prizes.top/")).level)
    }
}
