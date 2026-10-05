package com.mylo.browser.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Hands an [ExternalTarget] to another app with an explicit, browsable-only intent. */
object ExternalApps {
    sealed interface Outcome {
        data object Launched : Outcome
        /** Nothing could open it, but there is a web page to show in Mylo instead. */
        data class LoadInMylo(val url: String) : Outcome
        data object NoApp : Outcome
    }

    fun launch(context: Context, target: ExternalTarget): Outcome {
        // An intent: link that is really a web page with no app named stays in Mylo.
        if (target.scheme in setOf("http", "https") && target.packageName == null) return Outcome.LoadInMylo(target.uri)
        val uri = Uri.parse(target.uri)
        val intent = when (target.kind) {
            ExternalKind.Phone -> Intent(Intent.ACTION_DIAL, uri)
            ExternalKind.Email, ExternalKind.Message -> Intent(Intent.ACTION_SENDTO, uri)
            else -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }.apply {
            component = null
            selector = null
            target.packageName?.let { setPackage(it) }
        }
        if (start(context, intent)) return Outcome.Launched
        target.fallbackUrl?.let { return Outcome.LoadInMylo(it) }
        val listing = target.packageName ?: storeId(target.uri)
        if (listing != null) {
            if (target.kind != ExternalKind.Store &&
                start(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$listing")).addCategory(Intent.CATEGORY_BROWSABLE))) {
                return Outcome.Launched
            }
            return Outcome.LoadInMylo("https://play.google.com/store/apps/details?id=$listing")
        }
        return Outcome.NoApp
    }

    /** Opens [url] in whichever other browser the user picks (the fallback for pages that refuse WebView). */
    fun openInOtherBrowser(context: Context, url: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        return start(context, Intent.createChooser(view, "Open with"))
    }

    private fun storeId(uri: String): String? =
        if (uri.startsWith("market://", ignoreCase = true)) Uri.parse(uri).getQueryParameter("id") else null

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
