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

    val domains: Map<String, TrackerCategory> = buildMap {
        advertising.forEach { put(it, TrackerCategory.Advertising) }
        analytics.forEach { put(it, TrackerCategory.Analytics) }
        social.forEach { put(it, TrackerCategory.Social) }
        fingerprinting.forEach { put(it, TrackerCategory.Fingerprinting) }
    }
}
