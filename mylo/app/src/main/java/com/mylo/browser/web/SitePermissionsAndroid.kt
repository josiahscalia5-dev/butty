package com.mylo.browser.web

import android.content.Context

/** Normal browsing's remembered site choices, in the app's private preferences. */
fun SitePermissionStore.Companion.from(context: Context): SitePermissionStore {
    val prefs = context.getSharedPreferences("mylo_site_permissions", Context.MODE_PRIVATE)
    return SitePermissionStore(object : KeyValues {
        override fun get(key: String) = prefs.getString(key, null)
        override fun put(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
        override fun keys(): Set<String> = prefs.all.keys
    })
}
