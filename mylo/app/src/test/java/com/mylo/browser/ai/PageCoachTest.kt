package com.mylo.browser.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCoachTest {
    @Test fun readsPricesWithTheirPeriods() {
        val prices = PageCoach.prices(listOf("Basic: \$5 per month, one device.", "Family: \$12 per month, up to five devices.", "Pro: €99/year", "Gift: 1,299.00 USD"))
        assertEquals(listOf(5.0, 12.0, 99.0, 1299.0), prices.map { it.amount })
        assertEquals(listOf(PriceSeen.Period.Month, PriceSeen.Period.Month, PriceSeen.Period.Year, PriceSeen.Period.Once), prices.map { it.period })
        assertEquals(listOf("USD", "USD", "EUR", "USD"), prices.map { it.currency })
        assertEquals("\$5 a month", PageCoach.format(prices[0]))
    }

    @Test fun comparesTabsOnlyWhenItCanDoSoFairly() {
        val a = TabPrices("Mylo Plans", "localhost", PageCoach.prices(listOf("Basic: \$5 per month", "Family: \$12 per month")))
        val b = TabPrices("Other", "other.example", PageCoach.prices(listOf("Starter \$48 per year")))
        assertEquals("The lowest price is on “Other” (other.example): \$48 a year, compared per month.", PageCoach.compare(listOf(a, b)))
        assertNull(PageCoach.compare(listOf(a)))
        val euros = TabPrices("Euro", "eu.example", PageCoach.prices(listOf("Basic €4 per month")))
        assertTrue(PageCoach.compare(listOf(a, euros))!!.startsWith("These tabs show prices in different currencies"))
    }

    @Test fun turnsInstructionsIntoSteps() {
        val fromPage = PageCoach.steps("Open Account, choose Plan, then Cancel plan. Your plan stays active until the end of the month.")
        assertEquals(listOf("Account", "Plan", "Cancel plan"), fromPage.map { it.target })
        assertEquals("Open Account", fromPage[0].text)
        val fromAi = PageCoach.steps("Here's how:\n1. Tap “Settings” at the top.\n2. Open Billing\n3. Press \"Cancel subscription\"\nDone!")
        assertEquals(listOf("Settings", "Billing", "Cancel subscription"), fromAi.map { it.target })
        assertTrue(PageCoach.steps("Nothing to do here.").isEmpty())
        val section = PageCoach.steps("Cancel your plan\nOpen Account, choose Plan, then Cancel plan. Your plan stays active until the end of the month.")
        assertEquals(listOf("Open Account", "Choose Plan", "Cancel plan"), section.map { it.text })
    }
}
