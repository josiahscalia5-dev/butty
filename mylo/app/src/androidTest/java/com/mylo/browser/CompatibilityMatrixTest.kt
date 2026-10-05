package com.mylo.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.mylo.browser.web.PageNotice
import com.mylo.browser.web.SiteDecision
import com.mylo.browser.web.SitePermission
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The website-compatibility matrix against Mylo's local test site (compat-site/, two origins reached
 * through `adb reverse`). Every case uses the same shared engine as any other website; the site is only
 * a deterministic example of each web feature. Run one case per app process (ci-emulator-smoke.sh).
 */
@RunWith(AndroidJUnit4::class)
class CompatibilityMatrixTest : CompatHarness() {

    /** 1. A plain static page: loads, title, favicon, history. */
    @Test fun staticSite() = case("01-static-site") {
        openFromHome("$APP/index.html")
        waitForPage { it.endsWith("/index.html") }
        waitFor("the page title and icon") { main { engine.page(visibleTab()!!).let { it.title == "Mylo static test page" && it.favicon != null } } }
        note("title", main { engine.page(visibleTab()!!).title })
        note("favicon", true)
        waitFor("the visit in Mylo history") { main { store.history.firstOrNull()?.url == "$APP/index.html" } }
        shot("static-page")
    }

    /** 2. A JavaScript single-page app: pushState routing, fetch(), localStorage, Back/Forward, reload. */
    @Test fun singlePageApp() = case("02-single-page-app") {
        openFromHome("$APP/spa.html")
        waitForPage { it.contains("/spa.html") }
        tap("nav-list"); waitForText("view") { it == "view: list (5 items from fetch)" }
        tap("nav-item-2"); waitForText("view") { it == "view: item Item 2" }
        tap("add"); tap("add")
        val saved = waitForText("saved") { it.startsWith("saved items: ") && it.removePrefix("saved items: ").toInt() >= 2 }
        shot("spa-item-2")
        tapDesc("Back"); waitForText("view") { it.startsWith("view: list") }
        tapDesc("Back"); waitForText("view") { it == "view: home" }
        tapDesc("Forward"); waitForText("view") { it.startsWith("view: list") }
        step("Back, Back, Forward followed pushState history")
        tapDesc("Reload"); waitForPage { it.contains("view=list") }
        assertEquals("localStorage survives a reload", saved, waitForText("saved") { it.startsWith("saved items") })
        note("savedAfterReload", saved)
        shot("spa-after-reload")
    }

    /** 3. target="_blank" and window.open() open Mylo tabs; blocked automatic pop-ups say so. */
    @Test fun newWindows() = case("03-new-windows") {
        main { engine.permissions.clear(APP) }
        openFromHome("$APP/popup.html")
        waitForPage { it.endsWith("/popup.html") }
        val opener = visibleTab()!!
        tap("blank-link")
        waitFor("a new tab from target=_blank") { tabIds().size == 2 && visibleTab() != opener }
        val blankTab = visibleTab()!!
        assertEquals("The new tab remembers which tab opened it", opener, openerOf(blankTab))
        waitForPage { it.contains("via=target-blank") }
        note("targetBlank", textOf("info"))
        shot("target-blank-new-tab")
        tapDesc("Back")
        waitFor("the opener after closing the new tab") { visibleTab() == opener && tabIds() == listOf(opener) }
        step("target=_blank opened a new tab; Back closed it and returned to the page that opened it")

        tap("open-window")
        waitFor("a new tab from window.open") { tabIds().size == 2 && visibleTab() != opener }
        waitForPage { it.contains("via=window.open") }
        assertTrue("window.open keeps window.opener", textOf("info").contains("opener: present"))
        tap("reply")
        waitForText("status", tabId = opener) { it == "message from new window: hello from window.open" }
        shot("window-open-new-tab")
        tap("close")
        waitFor("window.close() to return to the opener") { visibleTab() == opener && tabIds() == listOf(opener) }
        note("openerAfterClose", textOf("status"))
        shot("back-on-opener")

        go("$APP/popup.html?auto=1")
        waitFor("the blocked pop-up message") { notice() is PageNotice.PopupBlocked }
        assertEquals("No tab opens without a tap", 1, tabIds().size)
        shot("automatic-popup-blocked")
        tapText("Always allow")
        assertEquals(SiteDecision.Allow, main { engine.permissions.decision(APP, SitePermission.Popups) })
        go("$APP/popup.html?auto=1")
        waitFor("the allowed pop-up") { tabIds().size == 2 }
        step("After 'Always allow', the same automatic pop-up opened as a tab")
        main { engine.permissions.clear(APP) }
    }

    /** 4. A sign-in pop-up on another site: approve, cancel, and closing it all return to the app tab. */
    @Test fun signInPopup() = case("04-sign-in-popup") {
        openFromHome("$APP/oauth.html")
        waitForPage { it.endsWith("/oauth.html") }
        val app = visibleTab()!!
        tap("signin")
        waitFor("the sign-in pop-up tab") { tabIds().size == 2 && visibleTab() != app }
        waitForPage { it.startsWith("$SIGN_IN_SERVER/authorize.html") }
        assertTrue(textOf("info").contains("opener: present"))
        shot("sign-in-popup")
        tap("approve")
        waitFor("the pop-up to close itself after signing in") { visibleTab() == app && tabIds() == listOf(app) }
        val signedIn = waitForText("status") { it.startsWith("signed in") }
        assertEquals("signed in (code mylo-code-123)", signedIn)
        assertTrue("The sign-in server kept its own session cookie",
            main { CookieManager.getInstance().getCookie(SIGN_IN_SERVER).orEmpty() }.contains("idp_session=signed-in"))
        note("approve", signedIn)
        shot("back-in-app-signed-in")

        tap("signin")
        waitFor("the sign-in pop-up again") { tabIds().size == 2 }
        waitForPage { it.startsWith("$SIGN_IN_SERVER/authorize.html") }
        tap("deny")
        waitFor("the pop-up to close after cancelling") { visibleTab() == app && tabIds() == listOf(app) }
        note("cancel", waitForText("status") { it.startsWith("sign-in cancelled") })

        tap("signin")
        waitFor("the sign-in pop-up a third time") { tabIds().size == 2 }
        waitForPage { it.startsWith("$SIGN_IN_SERVER/authorize.html") }
        tapDesc("Back")
        waitFor("Back to close the pop-up") { visibleTab() == app && tabIds() == listOf(app) }
        waitFor("the app to notice the pop-up closed") { js("document.body.dataset.popupClosed") == "1" }
        step("Approve, cancel and closing the pop-up with Back each returned to the intact app tab")
        shot("app-after-closing-popup")
    }

    /** 5. Cookies (server HttpOnly session + page cookie), localStorage, sessionStorage, IndexedDB. */
    @Test fun cookiesAndStorage() = case("05-cookies-and-storage") {
        openFromHome("$APP/cookies.html")
        waitForPage { it.endsWith("/cookies.html") }
        waitForText("report") { it.contains("IndexedDB") }
        tap("login")
        waitForText("report") { it.contains("server session: Mylo tester") }
        tap("reload")
        val report = waitForText("report") { it.contains("sessionStorage visits: 2") }
        listOf("cookies enabled: true", "page cookie: set", "server session: Mylo tester", "IndexedDB: works").forEach {
            assertTrue("Expected '$it' in:\n$report", report.contains(it))
        }
        assertTrue(main { CookieManager.getInstance().getCookie(APP).orEmpty() }.contains("mylo_session=tester"))
        note("report", report)
        shot("cookies-and-storage")
    }

    /** 6. A file input opens Android's picker; the chosen file is read and uploaded. */
    @Test fun fileUpload() = case("06-file-upload") {
        val name = "mylo-upload-${System.currentTimeMillis() % 100000}.txt"
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        })!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write("Hello from the Mylo upload test\n".toByteArray()) }
        openFromHome("$APP/upload.html")
        waitForPage { it.endsWith("/upload.html") }
        tap("file")
        waitFor("Android's file picker", 15_000) { device.currentPackageName?.contains("documentsui") == true }
        note("picker", device.currentPackageName)
        shot("android-file-picker")
        var file = findNative(By.text(name), 6_000)
        if (file == null) {
            // Open the picker's Downloads root.
            findNative(By.desc("Show roots"), 3_000)?.click(); pump(800)
            findNative(By.text("Downloads"), 3_000)?.click(); pump(1_200)
            file = findNative(By.text(name), 8_000)
        }
        assertNotNull("The test file is listed in Android's picker", file)
        file!!.click()
        waitFor("Mylo to come back from the picker") { device.currentPackageName == context.packageName }
        val result = waitForText("result") { it.startsWith("chosen: $name") && it.contains("uploaded:") }
        assertTrue(result, result.contains("content: Hello from the Mylo upload test"))
        note("result", result)
        shot("file-uploaded")
        context.contentResolver.delete(uri, null, null)
    }

    /** 7. A server attachment is confirmed, then saved to Downloads with the page's session. */
    @Test fun fileDownload() = case("07-file-download") {
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query()).use { c -> while (c.moveToNext()) manager.remove(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))) }
        openFromHome("$APP/cookies.html")
        waitForPage { it.endsWith("/cookies.html") }
        tap("login"); waitForText("report") { it.contains("server session: Mylo tester") }
        go("$APP/download.html")
        waitForPage { it.endsWith("/download.html") }
        tap("download")
        assertNotNull("Mylo asks before downloading", findNative(By.text("Download file?")))
        shot("download-prompt")
        tapText("Download")
        var content = ""
        waitFor("the download to finish", 30_000) {
            manager.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)).use { c ->
                if (!c.moveToFirst()) return@waitFor false
                val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                content = manager.openDownloadedFile(id).use { java.io.FileInputStream(it.fileDescriptor).readBytes().decodeToString() }
                true
            }
        }
        assertTrue("The download carried the signed-in session:\n$content", content.contains("session-cookie: yes"))
        note("downloaded", content)
        note("folder", Environment.DIRECTORY_DOWNLOADS)
        shot("download-started")
        tap("blob-download")
        waitFor("an honest message about page-made files") { (notice() as? PageNotice.Info)?.offerOtherBrowser == true }
        note("blobDownload", (notice() as PageNotice.Info).message)
        shot("blob-download-explained")
    }

    /** 8. Camera: Mylo asks, then Android asks; the page gets a live camera. */
    @Test fun camera() = case("08-camera") {
        main { engine.permissions.clear(APP) }
        openFromHome("$APP/media.html")
        waitForPage { it.endsWith("/media.html") }
        tap("camera")
        assertNotNull(findNative(By.textContains("to use your camera")))
        shot("mylo-camera-prompt")
        tapText("Allow")
        allowInAndroidDialog(this, "camera")
        val result = waitForText("camera-result", 20_000) { it.startsWith("camera: ") }
        note("result", result)
        shot("camera-result")
        assertTrue(result, result.startsWith("camera: live"))
    }

    /** 9. Microphone, remembered for the site: the second request needs no question from Mylo. */
    @Test fun microphone() = case("09-microphone") {
        main { engine.permissions.clear(APP) }
        openFromHome("$APP/media.html")
        waitForPage { it.endsWith("/media.html") }
        tap("microphone")
        assertNotNull(findNative(By.textContains("to use your microphone")))
        tapText("Remember my choice for this site")
        shot("mylo-microphone-prompt")
        tapText("Allow")
        allowInAndroidDialog(this, "microphone")
        val first = waitForText("microphone-result", 20_000) { it.startsWith("microphone: ") }
        note("first", first)
        shot("microphone-result")
        assertTrue(first, first.startsWith("microphone: live"))
        assertEquals(SiteDecision.Allow, main { engine.permissions.decision(APP, SitePermission.Microphone) })
        tapDesc("Reload"); waitForPage { it.endsWith("/media.html") }
        tap("microphone")
        val second = waitForText("microphone-result", 15_000) { it.startsWith("microphone: ") && it != "microphone: asking…" }
        note("rememberedWithoutAsking", second)
        tapDesc("More page options"); tapText("Site settings")
        assertNotNull(findNative(By.text("Allowed")))
        shot("site-settings-remembered")
        tapText("Reset permissions for this site")
        assertNull(main { engine.permissions.decision(APP, SitePermission.Microphone) })
    }

    /** 10. Location: "Don't allow" is honoured; allowing returns the device position. */
    @Test fun location() = case("10-location") {
        main { engine.permissions.clear(APP) }
        openFromHome("$APP/media.html")
        waitForPage { it.endsWith("/media.html") }
        tap("location")
        assertNotNull(findNative(By.textContains("to use your location")))
        shot("mylo-location-prompt")
        tapText("Don't allow")
        val denied = waitForText("location-result") { it.startsWith("location: error 1") }
        note("denied", denied)
        tap("location")
        tapText("Allow")
        allowInAndroidDialog(this, "location")
        val result = waitForText("location-result", 40_000) { it.startsWith("location: ") && !it.contains("asking") }
        note("result", result)
        shot("location-result")
        assertTrue(result, Regex("location: -?\\d+\\.\\d{4}, -?\\d+\\.\\d{4}").matches(result))
    }

    /** 11. HTML5 video goes full screen and Back returns to the page. */
    @Test fun fullscreenVideo() = case("11-fullscreen-video") {
        openFromHome("$APP/video.html")
        waitForPage { it.endsWith("/video.html") }
        pump(1_000)
        tap("fullscreen")
        waitFor("full-screen video") { main { engine.fullscreen != null } && textOf("status") == "full screen" }
        shot("video-full-screen")
        device.pressBack()
        waitFor("Back to leave full screen") { main { engine.fullscreen == null } && textOf("status") == "inline" }
        assertTrue("Still on the video page", pageUrl().endsWith("/video.html"))
        shot("video-inline-again")
    }

    /** 12. Links for other apps: dialer, email, app links with a web fallback; unsafe links never run. */
    @Test fun appLinks() = case("12-app-links") {
        openFromHome("$APP/links.html")
        waitForPage { it.endsWith("/links.html") }
        val links = pageUrl()
        tap("tel")
        waitFor("the phone app", 10_000) { device.currentPackageName != context.packageName }
        note("telOpened", device.currentPackageName)
        shot("dialer-opened")
        device.pressBack(); pump(800)
        if (device.currentPackageName != context.packageName) { device.pressBack(); pump(800) }
        waitFor("Mylo again") { device.currentPackageName == context.packageName }

        tap("mailto")
        pump(2_000)
        note("mailto", if (device.currentPackageName != context.packageName) "opened ${device.currentPackageName}" else (notice() as? PageNotice.Info)?.message)
        if (device.currentPackageName != context.packageName) { device.pressBack(); pump(800) }

        tap("intent-fallback")
        assertNotNull("Arbitrary app links are confirmed", findNative(By.text("Open another app?")))
        shot("app-link-confirmation")
        tapText("Open")
        waitForPage { it == "$APP/fallback.html" }
        step("intent: link with a missing app opened the site's own web fallback in Mylo")
        tapDesc("Back"); waitForPage { it == links }

        tap("custom")
        tapText("Cancel")
        assertEquals(links, pageUrl())
        tap("custom")
        tapText("Open")
        waitFor("an honest 'no app' message") { (notice() as? PageNotice.Info)?.message?.contains("No app") == true }
        note("customScheme", (notice() as PageNotice.Info).message)

        tap("file")
        pump(1_500)
        assertEquals("file: links from web pages never load", links, pageUrl())

        go("$APP/links.html?auto=1")
        waitForPage { it.endsWith("auto=1") }
        pump(2_000)
        assertTrue("No app opens without a tap", main { engine.prompts.isEmpty() } && device.currentPackageName == context.packageName)
        step("A page redirecting to an app without a tap was blocked silently")
        shot("links-page-after-checks")
    }

    /** 13. Back/Forward across static pages, pushState routes and a server redirect. */
    @Test fun backForwardAfterComplexNavigation() = case("13-back-forward") {
        openFromHome("$APP/index.html")
        waitForPage { it.endsWith("/index.html") }
        tap("to-spa"); waitForPage { it.endsWith("/spa.html") }
        tap("nav-list"); waitForText("view") { it.startsWith("view: list") }
        tap("nav-item-2"); waitForText("view") { it == "view: item Item 2" }
        tap("nav-static"); waitForPage { it.endsWith("/index.html") }
        tap("to-redirect"); waitForPage { it.endsWith("/cookies.html") }
        val trail = mutableListOf(pageUrl())
        fun move(button: String, expected: String) {
            tapDesc(button)
            trail += waitForPage { it.endsWith(expected) }
        }
        move("Back", "/index.html")
        move("Back", "/spa.html?view=item&id=2"); waitForText("view") { it == "view: item Item 2" }
        move("Back", "/spa.html?view=list")
        move("Forward", "/spa.html?view=item&id=2")
        move("Forward", "/index.html")
        move("Forward", "/cookies.html")
        note("trail", org.json.JSONArray(trail))
        shot("back-forward-end")
    }

    /** 14. Several tabs keep their own live pages and history. */
    @Test fun multipleTabs() = case("14-multiple-tabs") {
        openFromHome("$APP/spa.html")
        waitForPage { it.endsWith("/spa.html") }
        tap("nav-list"); waitForText("view") { it.startsWith("view: list") }
        val first = visibleTab()!!
        js("window.__myloMarker='tab-one'")
        tapNav("Tabs"); tapText("New tab")
        pump(800)
        // A new tab returns to Home with its search box focused.
        compose.onNodeWithTag(SEARCH_INPUT).performTextInput("$APP/index.html")
        compose.onNodeWithTag(SEARCH_INPUT).performImeAction()
        waitForPage { it.endsWith("/index.html") }
        val second = visibleTab()!!
        assertNotEquals(first, second)
        tapNav("Tabs")
        shot("tabs-panel")
        tapText("Mylo SPA · list")
        waitFor("the first tab") { visibleTab() == first }
        assertEquals("The first tab's page stayed alive (not reloaded)", "tab-one", js("window.__myloMarker"))
        tapDesc("Back"); waitForText("view") { it == "view: home" }
        tapNav("Tabs"); tapText("Mylo static test page")
        waitFor("the second tab") { visibleTab() == second && pageUrl().endsWith("/index.html") }
        tapNav("Tabs"); tapDesc("Close Mylo static test page")
        waitFor("one tab left") { tabIds() == listOf(first) }
        note("tabs", tabsState())
        shot("after-closing-second-tab")
    }

    /** 15. The same engine whichever way a page is reached: typed, a link, a new window, a bookmark, History. */
    @Test fun entryPointsShareOneEngine() = case("15-entry-points") {
        val fingerprints = JSONObject()
        openFromHome("$APP/cookies.html")
        waitForPage { it.endsWith("/cookies.html") }
        fingerprints.put("typed address", fingerprint())
        tapDesc("Bookmark this page")
        assertNotNull(findNative(By.text("Mylo cookies and storage")))
        tapDesc("Close Your bookmarks")

        go("$APP/index.html"); waitForPage { it.endsWith("/index.html") }
        tap("to-cookies"); waitForPage { it.endsWith("/cookies.html") }
        fingerprints.put("link on another page", fingerprint())

        go("$APP/popup.html"); waitForPage { it.endsWith("/popup.html") }
        tap("open-window"); waitForPage { it.contains("popup-target.html") }
        fingerprints.put("new window", fingerprint())
        tapDesc("Back"); waitFor("the opener") { tabIds().size == 1 }

        tapNav("Home"); tapText("Bookmarks"); tapText("Mylo cookies and storage")
        waitForPage { it.endsWith("/cookies.html") }
        fingerprints.put("bookmark", fingerprint())

        go("$APP/index.html"); waitForPage { it.endsWith("/index.html") }
        tapNav("Home"); tapText("History"); tapText("Mylo cookies and storage")
        waitForPage { it.endsWith("/cookies.html") }
        fingerprints.put("history", fingerprint())
        shot("opened-from-history")
        main { store.removeBookmark("$APP/cookies.html") }

        note("fingerprints", fingerprints)
        val distinct = fingerprints.keys().asSequence().map { fingerprints.getJSONObject(it).toString() }.toSet()
        assertEquals("Every entry point gets the same engine capabilities: $fingerprints", 1, distinct.size)
    }

    /** JavaScript alert/confirm/prompt appear as Mylo dialogs naming the site. */
    @Test fun dialogs() = case("16-dialogs") {
        openFromHome("$APP/dialogs.html")
        waitForPage { it.endsWith("/dialogs.html") }
        tap("alert")
        assertNotNull(findNative(By.text("localhost says")))
        shot("alert")
        tapText("OK")
        waitForText("result") { it == "alert closed" }
        tap("confirm"); tapText("Cancel")
        waitForText("result") { it == "confirm: false" }
        tap("prompt")
        findNative(By.clazz("android.widget.EditText"))!!.text = "Mylo tester"
        shot("prompt")
        tapText("OK")
        note("prompt", waitForText("result") { it == "prompt: Mylo tester" })
    }

    /** What any page sees of Mylo's engine; identical for every way of arriving. */
    private fun fingerprint(): JSONObject = JSONObject(js(FINGERPRINT)!!).apply { remove("host") }

    companion object {
        /** Engine-level capabilities as a page sees them (no site data). */
        const val FINGERPRINT = """(function(){
            var ls=false,ss=false,ck=false;
            try{localStorage.setItem('__mylo','1');ls=localStorage.getItem('__mylo')=='1';localStorage.removeItem('__mylo');}catch(e){}
            try{sessionStorage.setItem('__mylo','1');ss=sessionStorage.getItem('__mylo')=='1';sessionStorage.removeItem('__mylo');}catch(e){}
            try{document.cookie='__mylo_probe=1; path=/';ck=document.cookie.indexOf('__mylo_probe=1')>=0;document.cookie='__mylo_probe=; path=/; max-age=0';}catch(e){}
            return JSON.stringify({host:location.host,userAgent:navigator.userAgent,javascript:true,cookieEnabled:navigator.cookieEnabled,
              cookieWrite:ck,localStorage:ls,sessionStorage:ss,indexedDB:!!window.indexedDB,serviceWorker:'serviceWorker' in navigator,
              windowOpen:typeof window.open,fullscreenApi:!!document.documentElement.requestFullscreen,
              mediaDevices:!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia),geolocation:'geolocation' in navigator,
              fileInput:(function(){var i=document.createElement('input');i.type='file';return i.type=='file';})()});
        })()"""
    }
}
