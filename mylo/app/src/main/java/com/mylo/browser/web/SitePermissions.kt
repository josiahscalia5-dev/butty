package com.mylo.browser.web

/** What a website may ask for; each is always asked first and never granted automatically. */
enum class SitePermission(val key: String, val label: String) {
    Camera("camera", "Camera"),
    Microphone("microphone", "Microphone"),
    Location("location", "Location"),
    ProtectedMedia("protected_media", "Protected content"),
    Popups("popups", "Pop-ups and new windows"),
}

enum class SiteDecision { Allow, Block }

/** Minimal key/value storage so the store is testable off-device. */
interface KeyValues {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun keys(): Set<String>
}

/**
 * Remembered per-site choices. Normal browsing keeps them on the device ([from]); Private Mode keeps them in
 * [SessionValues] until the session is burned. Only secure origins are remembered; others are asked each time.
 */
class SitePermissionStore(private val values: KeyValues) {
    fun decision(origin: String?, permission: SitePermission): SiteDecision? =
        origin?.let { values.get(key(it, permission)) }?.let { runCatching { SiteDecision.valueOf(it) }.getOrNull() }

    fun remember(origin: String?, permission: SitePermission, decision: SiteDecision?) {
        if (origin == null || !Origins.rememberable(origin)) return
        values.put(key(origin, permission), decision?.name)
    }

    fun forOrigin(origin: String): Map<SitePermission, SiteDecision> =
        SitePermission.entries.mapNotNull { permission -> decision(origin, permission)?.let { permission to it } }.toMap()

    fun clear(origin: String) = SitePermission.entries.forEach { values.put(key(origin, it), null) }

    private fun key(origin: String, permission: SitePermission) = "$origin|${permission.key}"

    companion object
}

/** Choices kept only in memory: Private Mode's "this session" permissions, destroyed with the session. */
class SessionValues : KeyValues {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun keys(): Set<String> = map.keys.toSet()
    fun clear() = map.clear()
}
