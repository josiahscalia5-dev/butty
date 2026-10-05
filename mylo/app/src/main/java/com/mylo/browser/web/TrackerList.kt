package com.mylo.browser.web

/**
 * Mylo's tracker list: third-party domains whose main job is advertising, cross-site analytics, social
 * tracking or identity matching, compiled by the Mylo project from the companies' own documentation of
 * these services. Subdomains are included (`stats.g.doubleclick.net` matches `doubleclick.net`).
 *
 * Deliberately left out, because blocking them breaks ordinary sites: content and script CDNs (gstatic.com,
 * googleapis.com, cloudflare, jsDelivr), sign-in, CAPTCHA and payment services (accounts.google.com,
 * recaptcha, hCaptcha, Stripe, PayPal), and video/media hosts (YouTube, Vimeo). Social networks' sign-in
 * SDK (connect.facebook.net) *is* listed, so "Log in with Facebook" buttons on other sites need Block
 * trackers turned off for the session, which Mylo explains in Private Mode.
 */
object TrackerList {
    private val advertising = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com", "adnxs.com", "adsrvr.org", "criteo.com",
        "criteo.net", "taboola.com", "outbrain.com", "rubiconproject.com", "pubmatic.com", "openx.net",
        "casalemedia.com", "amazon-adsystem.com", "advertising.com", "adform.net", "smartadserver.com",
        "moatads.com", "doubleverify.com", "adsafeprotected.com", "yieldmo.com", "sharethrough.com", "teads.tv",
        "33across.com", "indexww.com", "contextweb.com", "media.net", "bidswitch.net", "everesttech.net",
        "mathtag.com", "zemanta.com", "adroll.com", "serving-sys.com", "revcontent.com", "mgid.com",
        "popads.net", "propellerads.com", "ads.yahoo.com", "ads.linkedin.com", "ads-twitter.com",
        "bat.bing.com", "ads.tiktok.com", "ct.pinterest.com", "tr.snapchat.com", "sc-static.net",
        "alb.reddit.com", "adcolony.com", "applovin.com", "inmobi.com", "3lift.com", "gumgum.com",
        "lijit.com", "sovrn.com", "spotxchange.com", "springserve.com", "smaato.net", "adition.com",
    )
    private val analytics = listOf(
        "google-analytics.com", "analytics.google.com", "googletagmanager.com", "app-measurement.com",
        "hotjar.com", "hotjar.io", "mouseflow.com", "fullstory.com", "crazyegg.com", "mixpanel.com",
        "segment.io", "cdn.segment.com", "amplitude.com", "heapanalytics.com", "nr-data.net", "clarity.ms",
        "statcounter.com", "chartbeat.com", "chartbeat.net", "scorecardresearch.com", "quantserve.com",
        "quantcount.com", "omtrdc.net", "2o7.net", "demdex.net", "mc.yandex.ru", "mc.yandex.com",
        "hm.baidu.com", "kissmetrics.io", "luckyorange.com", "inspectlet.com", "smartlook.com",
        "analytics.tiktok.com", "analytics.twitter.com", "analytics.yahoo.com", "parsely.com",
    )
    private val social = listOf(
        "connect.facebook.net", "pixel.facebook.com", "addthis.com", "sharethis.com", "platform-api.sharethis.com",
    )
    private val fingerprinting = listOf(
        "fpjs.io", "openfpcdn.io", "krxd.net", "bluekai.com", "exelator.com", "agkn.com", "rlcdn.com",
        "tapad.com", "liadm.com", "crwdcntrl.net", "adsymptotic.com", "id5-sync.com", "33across.net",
    )

    /**
     * Sites owned by the same company as a listed domain. On those sites the company's own trackers are
     * first-party and load normally (Facebook's SDK on facebook.com, Google Analytics on youtube.com).
     */
    private val entities: List<Pair<List<String>, List<String>>> = listOf(
        listOf("connect.facebook.net", "pixel.facebook.com") to listOf("facebook.com", "instagram.com", "messenger.com", "whatsapp.com", "meta.com"),
        listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com", "adservice.google.com",
            "pagead2.googlesyndication.com", "google-analytics.com", "analytics.google.com", "googletagmanager.com", "app-measurement.com")
            to listOf("google.com", "youtube.com", "blogger.com", "android.com", "withgoogle.com"),
        listOf("ads-twitter.com", "analytics.twitter.com") to listOf("twitter.com", "x.com"),
        listOf("bat.bing.com", "clarity.ms") to listOf("bing.com", "microsoft.com", "msn.com", "live.com", "linkedin.com"),
        listOf("ads.linkedin.com") to listOf("linkedin.com", "microsoft.com"),
        listOf("ct.pinterest.com") to listOf("pinterest.com"),
        listOf("tr.snapchat.com", "sc-static.net") to listOf("snapchat.com"),
        listOf("alb.reddit.com") to listOf("reddit.com"),
        listOf("ads.tiktok.com", "analytics.tiktok.com") to listOf("tiktok.com"),
        listOf("amazon-adsystem.com") to listOf("amazon.com", "amazon.co.uk", "amazon.de", "amazon.in", "twitch.tv", "imdb.com"),
        listOf("ads.yahoo.com", "analytics.yahoo.com") to listOf("yahoo.com", "aol.com"),
        listOf("mc.yandex.ru", "mc.yandex.com") to listOf("yandex.ru", "yandex.com"),
        listOf("hm.baidu.com") to listOf("baidu.com"),
        listOf("omtrdc.net", "2o7.net", "demdex.net", "everesttech.net") to listOf("adobe.com"),
        listOf("bluekai.com") to listOf("oracle.com"),
        listOf("krxd.net") to listOf("salesforce.com"),
    )

    /** For each listed domain, the sites where it is first-party. */
    val owners: Map<String, Set<String>> = buildMap {
        entities.forEach { (trackers, sites) -> trackers.forEach { put(it, sites.toSet()) } }
    }

    val domains: Map<String, TrackerCategory> = buildMap {
        advertising.forEach { put(it, TrackerCategory.Advertising) }
        analytics.forEach { put(it, TrackerCategory.Analytics) }
        social.forEach { put(it, TrackerCategory.Social) }
        fingerprinting.forEach { put(it, TrackerCategory.Fingerprinting) }
    }
}
