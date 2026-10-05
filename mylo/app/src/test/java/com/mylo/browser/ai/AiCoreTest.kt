package com.mylo.browser.ai

import com.mylo.browser.web.SessionValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSwitchboardTest {
    @Test fun defaultsToTheCurrentPageOnly() {
        val board = AiSwitchboard()
        assertEquals(AiGrant.Always, board.grant(AiDataSource.CurrentPage))
        AiDataSource.entries.filter { it != AiDataSource.CurrentPage }.forEach { assertEquals(it.name, AiGrant.Off, board.grant(it)) }
        assertEquals(setOf(AiDataSource.CurrentPage), board.authorize(AiDataSource.entries.toSet()))
    }

    @Test fun allowOnceIsUsedUpByOneRequestAndNeverSaved() {
        val store = SessionValues()
        val board = AiSwitchboard(store)
        board.set(AiDataSource.OtherTabs, AiGrant.Once)
        assertEquals(setOf(AiDataSource.CurrentPage, AiDataSource.OtherTabs), board.authorize(setOf(AiDataSource.CurrentPage, AiDataSource.OtherTabs)))
        assertEquals(AiGrant.Off, board.grant(AiDataSource.OtherTabs))
        assertEquals(setOf(AiDataSource.CurrentPage), board.authorize(setOf(AiDataSource.CurrentPage, AiDataSource.OtherTabs)))
        assertEquals(null, store.get("ai_grant_OtherTabs"))
    }

    @Test fun persistentChoicesSurviveAndSensitiveSourcesAreNeverAlways() {
        val store = SessionValues()
        AiSwitchboard(store).apply {
            set(AiDataSource.History, AiGrant.Always)
            set(AiDataSource.CurrentPage, AiGrant.Off)
            set(AiDataSource.Location, AiGrant.Always)
            set(AiDataSource.Screenshot, AiGrant.Always)
        }
        val again = AiSwitchboard(store)
        assertEquals(AiGrant.Always, again.grant(AiDataSource.History))
        assertEquals(AiGrant.Off, again.grant(AiDataSource.CurrentPage))
        assertEquals("location is one request at a time", AiGrant.Off, again.grant(AiDataSource.Location))
        assertEquals(AiGrant.Off, again.grant(AiDataSource.Screenshot))
    }

    @Test fun toggleUsesTheSourcesOwnOnState() {
        val board = AiSwitchboard()
        board.toggle(AiDataSource.OtherTabs); assertEquals(AiGrant.Always, board.grant(AiDataSource.OtherTabs))
        board.toggle(AiDataSource.Location); assertEquals(AiGrant.Once, board.grant(AiDataSource.Location))
        board.toggle(AiDataSource.CurrentPage); assertFalse(board.allowed(AiDataSource.CurrentPage))
    }
}

class RedactorTest {
    @Test fun findsCommonPersonalData() {
        val text = "Write to jane.doe+news@example.co.uk or call (415) 555-0134. Card 4111 1111 1111 1111, SSN 123-45-6789, " +
            "IBAN GB82 WEST 1234 5698 7654 32, ship to 221 Baker Street, Apt 2B. https://shop.example.com/reset?token=abc123&x=1"
        val kinds = Redactor.find(text).map { it.kind }
        listOf(SensitiveKind.Email, SensitiveKind.Phone, SensitiveKind.Card, SensitiveKind.NationalId, SensitiveKind.Iban, SensitiveKind.Address, SensitiveKind.SecretInLink)
            .forEach { assertTrue("$it in $kinds", it in kinds) }
        val redacted = Redactor.redact(text)
        listOf("jane.doe", "555-0134", "4111", "123-45-6789", "WEST", "Baker", "abc123").forEach { assertFalse("$it leaked: $redacted", redacted.contains(it)) }
        assertTrue(redacted.contains("[email]") && redacted.contains("[card number]"))
    }

    @Test fun avoidsFalseAlarms() {
        val text = "Order #1234567890123 shipped on 2026-10-05. Plans start at $9.99 per month; 1,250,000 users. Version 4.2.1. Call us 24/7."
        val found = Redactor.find(text)
        assertTrue("no card for a non-Luhn order number, no phone for a date: $found", found.none { it.kind == SensitiveKind.Card || it.kind == SensitiveKind.Phone })
        assertFalse(Redactor.luhn("1234567890123"))
        assertTrue(Redactor.luhn("4111111111111111"))
        assertFalse("all-same digits", Redactor.luhn("0000000000000000"))
        assertTrue(Redactor.ibanValid("GB82WEST12345698765432"))
        assertFalse(Redactor.ibanValid("GB82WEST12345698765433"))
    }

    @Test fun redactsOnlyChosenKinds() {
        val text = "mail me at a@b.io or 415-555-0134"
        assertEquals("mail me at [email] or 415-555-0134", Redactor.redact(text, setOf(SensitiveKind.Email)))
    }
}

class ActionGateTest {
    private val page = "https://shop.example.com/plans"

    @Test fun ordinaryBrowsingIsDirect() {
        listOf(BrowserAction.ScrollTo("pricing"), BrowserAction.Highlight("reviews"), BrowserAction.ReadAloud("summary"), BrowserAction.GoBack,
            BrowserAction.Search("best laptop"), BrowserAction.OpenLink("https://shop.example.com/pricing", "Pricing"),
            BrowserAction.Click(ElementInfo("a", "Reviews", href = "https://www.example.com/reviews")))
            .forEach { assertEquals(it.toString(), ActionVerdict.Allow, ActionGate.review(it, page)) }
    }

    @Test fun formsArePreviewedInPlainWords() {
        val form = ElementInfo("button", "Get my quote", type = "submit", inForm = true, formAction = "https://quotes.example.com/send", formMethod = "post",
            formFields = listOf(FieldInfo("email", "email", "Email", "email", filled = true), FieldInfo("text", "zip", "ZIP", "postal-code", filled = true), FieldInfo("text", "note", filled = false)))
        val verdict = ActionGate.review(BrowserAction.Click(form), page) as ActionVerdict.Preview
        assertEquals("Mylo is about to submit your email and ZIP code to quotes.example.com.", verdict.summary)
        assertEquals("quotes.example.com", verdict.destination)
    }

    @Test fun consequentialButtonsAndOtherSitesNeedApproval() {
        listOf("Subscribe now", "Place order", "Delete account", "Cancel subscription", "Add to cart", "Allow").forEach {
            assertTrue(it, ActionGate.review(BrowserAction.Click(ElementInfo("button", it)), page) is ActionVerdict.Preview)
        }
        assertTrue(ActionGate.review(BrowserAction.OpenLink("https://elsewhere.net/offer", "Offer"), page) is ActionVerdict.Preview)
        assertTrue(ActionGate.review(BrowserAction.Download("https://shop.example.com/file.apk"), page) is ActionVerdict.Preview)
        assertTrue(ActionGate.review(BrowserAction.GrantPermission("location"), page) is ActionVerdict.Preview)
        assertTrue(ActionGate.review(BrowserAction.Upload("a photo"), page) is ActionVerdict.Preview)
    }

    @Test fun secretsAreNeverTyped() {
        assertTrue(ActionGate.review(BrowserAction.Fill(FieldInfo("password", "pw"), null), page) is ActionVerdict.Refuse)
        assertTrue(ActionGate.review(BrowserAction.Fill(FieldInfo("text", "cc", autocomplete = "cc-number"), null), page) is ActionVerdict.Refuse)
        assertTrue(ActionGate.review(BrowserAction.OpenLink("javascript:alert(1)", "x"), page) is ActionVerdict.Refuse)
        val fill = ActionGate.review(BrowserAction.Fill(FieldInfo("email", "email"), "https://shop.example.com/signup"), page) as ActionVerdict.Preview
        assertEquals("Mylo is about to fill in your email for shop.example.com.", fill.summary)
    }
}
