package com.mylo.browser.ai

import android.content.Context
import com.mylo.browser.BuildConfig
import com.mylo.browser.web.KeyValues

/** The voices Mylo offers; the Mylo AI service decides which it actually allows. */
enum class MyloVoice(val id: String, val label: String, val description: String) {
    Marin("marin", "Marin", "Warm, bright and friendly"),
    Cedar("cedar", "Cedar", "Calm, grounded and reassuring"),
}

/**
 * Mylo AI's settings on this device. The service address comes from the build (`mylo.ai.apiBaseUrl` /
 * MYLO_AI_API_BASE_URL, never the repository); debug builds may also use a test service entered on the
 * device. No provider key exists anywhere in the app: the service holds those.
 */
class AiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("mylo_ai", Context.MODE_PRIVATE)

    /** Debug builds only: a test Mylo AI service entered on this device. */
    val testService: AiEndpoint?
        get() = if (!BuildConfig.DEBUG) null
        else prefs.getString(TEST_URL, null)?.takeIf { it.isNotBlank() }?.let { AiEndpoint(it, prefs.getString(TEST_TOKEN, null)) }

    fun setTestService(endpoint: AiEndpoint?) {
        prefs.edit().apply {
            if (endpoint == null) { remove(TEST_URL); remove(TEST_TOKEN) }
            else { putString(TEST_URL, endpoint.baseUrl.trim()); putString(TEST_TOKEN, endpoint.accessToken?.trim()) }
        }.commit()
    }

    var voice: MyloVoice
        get() = MyloVoice.entries.firstOrNull { it.id == prefs.getString(VOICE, null) } ?: MyloVoice.Marin
        set(value) { prefs.edit().putString(VOICE, value.id).apply() }

    /** The endpoint to use: a debug test service first, then the build's service; null when neither exists. */
    fun endpoint(): AiEndpoint? = testService ?: BuildConfig.MYLO_AI_API_BASE_URL.takeIf { it.isNotBlank() }
        ?.let { AiEndpoint(it, BuildConfig.MYLO_AI_DEV_TOKEN.ifBlank { null }) }

    companion object {
        const val TEST_URL = "test_service_url"
        const val TEST_TOKEN = "test_service_token"
        private const val VOICE = "voice"
    }
}

object MyloAi {
    fun service(context: Context): MyloAiService {
        val preferences = AiPreferences(context.applicationContext)
        return HttpMyloAiService(preferences::endpoint, allowDevCleartext = BuildConfig.DEBUG)
    }

    /** The switchboard for normal browsing, remembered on this device (Private Mode passes no storage). */
    fun switchboard(context: Context): AiSwitchboard {
        val prefs = context.applicationContext.getSharedPreferences("mylo_ai_access", Context.MODE_PRIVATE)
        return AiSwitchboard(object : KeyValues {
            override fun get(key: String) = prefs.getString(key, null)
            override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
            override fun keys(): Set<String> = prefs.all.keys
        })
    }

    /** The service's host for "Connected to …"; null when Mylo AI isn't connected. */
    fun connectedHost(context: Context): String? = AiPreferences(context.applicationContext).endpoint()
        ?.let { HttpMyloAiService.validatedBase(it.baseUrl.trim(), BuildConfig.DEBUG) }
        ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
}
