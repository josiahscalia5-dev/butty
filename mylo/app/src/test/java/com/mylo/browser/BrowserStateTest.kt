package com.mylo.browser

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserStateTest {
    @Test fun domainDefaultsToHttpsAndPreservesPath() {
        assertEquals("https://example.com/a?q=one%20two#top", resolveInput(" example.com/a?q=one%20two#top "))
    }

    @Test fun explicitHttpAddressIsPreserved() {
        assertEquals("http://localhost:8080/test", resolveInput("http://localhost:8080/test"))
        assertEquals("https://example.com:8443/a", resolveInput("example.com:8443/a"))
    }

    @Test fun searchUsesSelectedProviderAndEncodesQuery() {
        assertEquals("https://www.google.com/search?q=night+sky+%26+stars", resolveInput("night sky & stars", SearchProvider.GOOGLE))
        assertEquals("https://duckduckgo.com/?q=mylo", resolveInput("mylo", SearchProvider.DUCKDUCKGO))
        assertEquals("https://www.bing.com/search?q=caf%C3%A9", resolveInput("café", SearchProvider.BING))
    }

    @Test fun floridaSearchUsesEachRealProvider() {
        val expected = mapOf(
            SearchProvider.GOOGLE to "https://www.google.com/search?q=best+beaches+in+Florida",
            SearchProvider.BING to "https://www.bing.com/search?q=best+beaches+in+Florida",
            SearchProvider.DUCKDUCKGO to "https://duckduckgo.com/?q=best+beaches+in+Florida",
            SearchProvider.BRAVE to "https://search.brave.com/search?q=best+beaches+in+Florida",
            SearchProvider.YAHOO to "https://search.yahoo.com/search?p=best+beaches+in+Florida",
            SearchProvider.STARTPAGE to "https://www.startpage.com/sp/search?query=best+beaches+in+Florida"
        )
        assertEquals(SearchProvider.entries.toSet(), expected.keys)
        expected.forEach { (provider, url) ->
            assertEquals(provider.displayName, url, resolveInput(" best beaches in Florida ", provider))
        }
    }

    @Test fun querySpecialCharactersAreEncodedForEveryProvider() {
        SearchProvider.entries.forEach { provider ->
            val result = resolveInput("café & beach + 50% #sun ?", provider)!!
            assertTrue(provider.displayName, result.endsWith("caf%C3%A9+%26+beach+%2B+50%25+%23sun+%3F"))
            assertTrue(provider.displayName, result.startsWith("https://"))
        }
    }

    @Test fun colonPrefixedSearchQueriesUseSelectedProvider() {
        SearchProvider.entries.forEach { provider ->
            listOf("site:florida.com beaches", "site:florida.com", "weather: London", "weather:London", "intitle:Mylo").forEach { query ->
                assertEquals(provider.searchUrl(query), resolveInput(query, provider))
            }
        }
    }

    @Test fun websiteAddressesDoNotUseSelectedProvider() {
        SearchProvider.entries.forEach { provider ->
            assertEquals("https://example.com/a?q=one%20two#top", resolveInput("example.com/a?q=one%20two#top", provider))
            assertEquals("http://localhost:8080/test", resolveInput("http://localhost:8080/test", provider))
            assertEquals("https://example.com:8443/a", resolveInput("EXAMPLE.com:8443/a", provider))
            assertEquals("https://localhost:8080/test", resolveInput("LOCALHOST:8080/test", provider))
        }
    }

    @Test fun unsafeAndMalformedAddressesAreRejected() {
        listOf(
            "javascript:alert(1)", "javascript: alert(1)", "file:///sdcard/private.txt",
            "intent://test", "data:text/html,hello", "data: text/html,hello",
            "content://private/document", "about: blank", "ftp://example.com",
            "unknown://example.com", "unknown://example.com with spaces",
            "https://", "https://good.example@evil.example", "https://example.com:99999",
            "https://[::1]:99999", "https://[::1]:0", "https://[::1]:", "https://[not-an-ipv6-address]"
        ).forEach { input ->
            SearchProvider.entries.forEach { provider -> assertNull(input, resolveInput(input, provider)) }
        }
    }

    @Test fun emptyAndControlCharacterSubmissionsAreRejected() {
        SearchProvider.entries.forEach { provider ->
            assertNull(resolveInput("   ", provider))
            assertNull(resolveInput("exa\nmple.com", provider))
            assertNull(resolveInput("search\u0000query", provider))
        }
    }

    @Test fun internationalizedDomainUsesAsciiHostname() {
        assertEquals("https://xn--bcher-kva.de/lesen", resolveInput("bücher.de/lesen"))
        assertEquals("https://xn--bcher-kva.de:8443/lesen", resolveInput("bücher。de:8443/lesen"))
        assertEquals("https://xn--bcher-kva.de/lesen?q=caf%C3%A9", resolveInput("HTTPS://bücher.de/lesen?q=café"))
    }

    @Test fun ipAddressesOpenDirectlyForEveryProvider() {
        SearchProvider.entries.forEach { provider ->
            assertEquals("https://192.168.1.1:8443/a", resolveInput("192.168.1.1:8443/a", provider))
            assertEquals("https://[2001:db8::1]/a?q=two", resolveInput("[2001:db8::1]/a?q=two", provider))
            assertEquals("https://[::1]:8443/a", resolveInput("[::1]:8443/a", provider))
            assertEquals("http://[::1]:8080/a", resolveInput("HTTP://[::1]:8080/a", provider))
        }
    }

    @Test fun selectedProviderSurvivesStoreRecreation() {
        val context = TestBrowserContext()
        SearchProvider.entries.forEach { provider ->
            BrowserStore(context).setProvider(provider)
            assertEquals(provider, BrowserStore(context).provider)
            assertEquals(provider.name, context.preferences.getString("provider", null))
        }
    }

    @Test fun currentPopulatedTabIsReusedForSearchAndUrl() {
        val store = BrowserStore(TestBrowserContext())
        store.setProvider(SearchProvider.BRAVE)
        val firstTab = store.createTab("https://example.com")
        val otherTab = store.createTab("https://other.example")
        val result = store.navigateInCurrentTab(firstTab.id, "best beaches in Florida")!!
        assertEquals(firstTab.id, result.id)
        assertEquals("https://search.brave.com/search?q=best+beaches+in+Florida", result.url)
        assertEquals(2, store.tabs.size)
        assertEquals(otherTab, store.tabs.last())

        val direct = store.navigateInCurrentTab(firstTab.id, "example.org/travel")!!
        assertEquals(firstTab.id, direct.id)
        assertEquals("https://example.org/travel", direct.url)
        assertEquals(2, store.tabs.size)
    }

    @Test fun blankCurrentTabIsReusedAndRetainsPrivateFlag() {
        val store = BrowserStore(TestBrowserContext())
        val tab = store.createTab(privateMode = true)
        val result = store.navigateInCurrentTab(tab.id, "example.com")!!
        assertEquals(tab.id, result.id)
        assertTrue(result.privateMode)
        assertEquals(1, store.tabs.size)
    }

    @Test fun onlyAMissingCurrentTabCreatesATab() {
        val store = BrowserStore(TestBrowserContext())
        val first = store.navigateInCurrentTab(null, "example.com")!!
        assertEquals(first, store.tabs.single())
        val second = store.navigateInCurrentTab(999L, "example.org")!!
        assertEquals(2, store.tabs.size)
        assertTrue(first.id != second.id)
    }

    @Test fun rejectedSubmissionLeavesTabsUnchanged() {
        val store = BrowserStore(TestBrowserContext())
        assertNull(store.navigateInCurrentTab(null, ""))
        assertTrue(store.tabs.isEmpty())
        val tab = store.createTab("https://example.com")
        assertNull(store.navigateInCurrentTab(tab.id, "javascript:alert(1)"))
        assertEquals(tab, store.tabs.single())
    }
}

/** A real in-memory preference implementation keeps persistence tests off the Android runtime. */
private class TestBrowserContext : ContextWrapper(null) {
    val preferences = InMemoryPreferences()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
}

private class InMemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearFirst = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clearFirst = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearFirst) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
