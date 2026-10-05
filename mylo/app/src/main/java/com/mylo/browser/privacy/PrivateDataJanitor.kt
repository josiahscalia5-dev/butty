package com.mylo.browser.privacy

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Removes the private WebView profile's files (cookies, storage, cache) whenever no private session can be
 * using them: at the start of every private process, before any WebView exists, and when the main app starts
 * while no private process is running. This covers sessions Android ended without a burn (the app was swiped
 * away or killed): their data is gone the next time Mylo starts.
 */
object PrivateDataJanitor {
    /** WebView data directory suffix of the private process (`app_webview_private`). */
    const val SUFFIX = "private"
    private const val PROCESS = ":private"

    /** Call only from the private process before it creates a WebView, or when that process isn't running. */
    fun wipe(context: Context): List<String> {
        val removed = mutableListOf<String>()
        candidates(context).forEach { dir -> if (dir.exists() && dir.deleteRecursively()) removed += dir.name }
        return removed
    }

    fun wipeIfPrivateProcessGone(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val running = manager.runningAppProcesses.orEmpty().any { it.processName == context.packageName + PROCESS }
        if (!running) wipe(context)
    }

    /** The private profile's directories: `app_webview_private` and any WebView cache folder for it. */
    internal fun candidates(context: Context): List<File> {
        val data = context.applicationInfo.dataDir?.let { File(it) } ?: return emptyList()
        val dirs = mutableListOf(File(data, "app_webview_$SUFFIX"), File(data, "app_textures_$SUFFIX"))
        context.cacheDir?.listFiles().orEmpty().forEach { entry ->
            val name = entry.name.lowercase()
            if ("webview" in name && SUFFIX in name) dirs += entry
            // Some WebView versions keep a per-suffix folder inside cache/WebView.
            if (name == "webview") entry.listFiles().orEmpty().filter { it.name.lowercase() == SUFFIX }.forEach { dirs += it }
        }
        return dirs
    }
}
