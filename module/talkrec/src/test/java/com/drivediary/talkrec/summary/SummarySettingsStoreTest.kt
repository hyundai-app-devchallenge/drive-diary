package com.drivediary.talkrec.summary

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SummarySettingsStoreTest {
    @Test fun upgradeMapsGeminiAndDeletesItsKeyWithoutDecryptingOrChangingConsent() = runBlocking {
        val values = mutableMapOf<String, Any?>("provider" to "GEMINI", "model_GEMINI" to "old-model",
            "api_ciphertext" to "unreadable-private-key", "api_iv" to "unreadable-iv",
            "show_titles_driving" to false, "send_to_ai" to false)
        val store = SummarySettingsStore(memoryPreferences(values)) { summaryPrompt() }
        assertEquals("CLOUDFLARE", values["provider"])
        assertEquals(SummaryProviderType.CLOUDFLARE, store.settings.value.provider)
        assertEquals(SummaryDefaults.CLOUDFLARE_MODEL, store.settings.value.modelName)
        assertFalse(store.settings.value.showTitlesWhileDriving)
        assertEquals(false, values["send_to_ai"])
        assertFalse(values.containsKey("api_ciphertext"))
        assertFalse(values.containsKey("api_iv"))
        assertFalse(values.containsKey("model_GEMINI"))
        assertNull(store.cloudflareTokenLast4())
        val connection = store.testConnection()
        assertFalse(connection.ok)
        assertEquals("key_unavailable", connection.errorCode)
        store.setCloudflareAccountId(TEST_ACCOUNT_ID)
        assertEquals("key_unavailable", store.testConnection().errorCode)
    }

    @Test fun migrationIsIdempotentAndPreservesNewCredentialsAndEditedModels() {
        val values = mutableMapOf<String, Any?>("provider" to "CLOUDFLARE", "cf_account_id" to TEST_ACCOUNT_ID,
            "cf_token_ciphertext" to "encrypted", "cf_token_iv" to "iv", "model_CLOUDFLARE" to "@cf/test/edited")
        val prefs = memoryPreferences(values)
        val before = values.toMap()
        repeat(2) { migrateSummarySettings(prefs) }
        assertEquals(before, values)
        assertFalse(SummarySettingsStore(prefs) { summaryPrompt() }.settings.value.toString().contains(TEST_ACCOUNT_ID))
    }

    @Test fun selectionRetainsSeparateModelsAndFakeRemainsLocal() = runBlocking {
        val values = mutableMapOf<String, Any?>()
        val store = SummarySettingsStore(memoryPreferences(values)) { summaryPrompt() }
        assertEquals(SummaryProviderType.CLOUDFLARE, store.settings.value.provider)
        store.setModelName("@cf/test/edited")
        store.setProvider(SummaryProviderType.FAKE)
        assertTrue(store.testConnection().ok)
        store.setProvider(SummaryProviderType.CLOUDFLARE)
        assertEquals("@cf/test/edited", store.settings.value.modelName)
        store.setModelName("")
        assertEquals(SummaryDefaults.CLOUDFLARE_MODEL, store.settings.value.modelName)
    }

    @Test fun invalidAccountAndTokenAreRejectedAndEmptyTokenDeletesCiphertext() = runBlocking {
        val values = mutableMapOf<String, Any?>("cf_token_ciphertext" to "encrypted", "cf_token_iv" to "iv")
        val store = SummarySettingsStore(memoryPreferences(values)) { summaryPrompt() }
        try { store.setCloudflareAccountId("bad/path"); fail() }
        catch (e: SummaryException) { assertEquals(SummaryErrorCode.INVALID_SETTINGS, e.code) }
        try { store.setCloudflareToken("token with space"); fail() }
        catch (e: SummaryException) { assertEquals(SummaryErrorCode.INVALID_SETTINGS, e.code) }
        store.setCloudflareToken("")
        assertFalse(values.containsKey("cf_token_ciphertext"))
        assertFalse(values.containsKey("cf_token_iv"))
        assertNull(store.cloudflareTokenLast4())
    }
}

/** Interface proxy works with Android's local test jar and the cached host API stand-ins. */
internal fun memoryPreferences(values: MutableMap<String, Any?>): SharedPreferences {
    val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    lateinit var prefs: SharedPreferences
    prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString", "getBoolean" -> values[args!![0]] ?: args[1]
            "contains" -> values.containsKey(args!![0])
            "registerOnSharedPreferenceChangeListener" -> { listeners += args!![0] as SharedPreferences.OnSharedPreferenceChangeListener; null }
            "edit" -> {
                val changes = mutableMapOf<String, Any?>()
                lateinit var editor: SharedPreferences.Editor
                editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, edit, fields ->
                    when (edit.name) {
                        "putString", "putBoolean" -> { changes[fields!![0] as String] = fields[1]; editor }
                        "remove" -> { changes[fields!![0] as String] = null; editor }
                        "commit" -> {
                            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                            changes.keys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(prefs, key) } }
                            true
                        }
                        else -> error("unsupported_editor_method")
                    }
                } as SharedPreferences.Editor
                editor
            }
            else -> error("unsupported_preferences_method")
        }
    } as SharedPreferences
    return prefs
}
