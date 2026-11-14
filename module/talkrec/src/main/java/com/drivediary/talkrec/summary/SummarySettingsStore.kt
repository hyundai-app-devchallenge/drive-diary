package com.drivediary.talkrec.summary

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The key and its ciphertext are deliberately absent from the observable settings. */
data class SummarySettings(
    val provider: SummaryProviderType,
    val modelName: String,
    val cloudflareAccountId: String = "",
    val showTitlesWhileDriving: Boolean = true,
) {
    override fun toString(): String = "SummarySettings(redacted)"
}

/**
 * Application-scoped store. UI must use the app's existing parked-only gate for edits/tests.
 * No logging, plaintext preference, plaintext key accessor, or key in the settings Flow.
 */
class SummarySettingsStore internal constructor(
    internal val prefs: SharedPreferences,
    private val loadPrompt: (String) -> SummaryPrompt,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("talkrec_summary", Context.MODE_PRIVATE),
        { name -> SummaryPrompt(context.applicationContext.assets.open("prompts/$name.md")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }) },
    ) {
        // The old wrapping key contains no API credential; remove it as well when accessible.
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("talkrec.summary.api-key.v1") } }
        catch (_: Exception) { /* Old ciphertext has already been removed; never reuse it. */ }
    }

    init { migrateSummarySettings(prefs) }
    private val prompt by lazy { loadPrompt("summary") }
    private val storyPrompt by lazy { loadPrompt("story_split") }
    private val mutex = Mutex()
    private val state = MutableStateFlow(readSettings())
    val settings: StateFlow<SummarySettings> = state.asStateFlow()
    // Hold a strong reference: SharedPreferences keeps its listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = readSettings() }

    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    suspend fun setProvider(provider: SummaryProviderType) {
        edit { putString("provider", provider.name) }
    }

    /** Each provider retains its own edited model across provider changes. Blank resets its default. */
    suspend fun setModelName(modelName: String) {
        val value = modelName.trim()
        if (value.isNotEmpty()) checkedModelName(value)
        edit {
            val provider = readSettings().provider
            putString("model_${provider.name}", value.ifEmpty { SummaryDefaults.modelName(provider) })
        }
    }

    suspend fun setCloudflareAccountId(accountId: String) {
        val value = accountId.trim()
        if (value.isNotEmpty()) checkedCloudflareAccountId(value)
        edit { putString("cf_account_id", value) }
    }

    suspend fun setShowTitlesWhileDriving(show: Boolean) {
        edit { putBoolean("show_titles_driving", show) }
    }

    /** Empty input removes the encrypted key. Encryption always uses a fresh GCM IV. */
    suspend fun setCloudflareToken(apiToken: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val value = apiToken.trim()
            if (value.any { it <= ' ' || it >= '\u007f' }) throw SummaryException(SummaryErrorCode.INVALID_SETTINGS)
            try {
                val editor = prefs.edit()
                if (value.isEmpty()) {
                    editor.remove("cf_token_ciphertext").remove("cf_token_iv")
                } else {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                    cipher.updateAAD(AAD)
                    editor.putString("cf_token_ciphertext", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                    editor.putString("cf_token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                }
                if (!editor.commit()) throw SummaryException(SummaryErrorCode.STORAGE)
            } catch (safe: SummaryException) {
                throw safe
            } catch (_: Exception) {
                throw SummaryException(SummaryErrorCode.KEY_UNAVAILABLE)
            }
        }
    }

    suspend fun cloudflareTokenLast4(): String? = withContext(Dispatchers.IO) {
        mutex.withLock { readCloudflareToken()?.takeLast(4) }
    }

    /** Uses only this fixed invented dialogue, never a saved clip. */
    suspend fun testConnection(): ConnectionTestResult = trySummarize(TEST_DIALOGUE)

    /** Explicit user try-out only. Uses current settings and retains/logs none of the input. */
    suspend fun trySummarize(transcript: String): ConnectionTestResult =
        runSummaryTryOut(transcript, { SummaryValidator(prompt.tags) }, ::createProvider)

    suspend fun tryStorySplit(segments: List<String>): StorySplitResult {
        val selected = createProvider()
        return validatedStorySplit(StorySplitValidator(storyPrompt.categories), segments.size) { selected.storySplit(segments) }
    }

    /** Internal DI entry points for SummaryWorker; not a plaintext-key API. */
    internal fun validator(): SummaryValidator = SummaryValidator(prompt.tags)

    internal suspend fun createProvider(): SummaryProvider = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readSettings()
            when (current.provider) {
                SummaryProviderType.CLOUDFLARE -> {
                    if (current.cloudflareAccountId.isBlank()) throw SummaryException(SummaryErrorCode.KEY_UNAVAILABLE)
                    CloudflareSummaryProvider(prompt, current.cloudflareAccountId,
                        readCloudflareToken() ?: throw SummaryException(SummaryErrorCode.KEY_UNAVAILABLE),
                        current.modelName, storyPrompt = storyPrompt)
                }
                SummaryProviderType.FAKE -> FakeSummaryProvider(SummaryValidator(prompt.tags), current.modelName)
                else -> SummaryBuildProviders.createExtra(current.provider, current.modelName, SummaryValidator(prompt.tags), prefs)
            }
        }
    }

    /**
     * Question generation (ask/) shares the summary provider choice and Cloudflare key.
     * Null when the selected provider is not Cloudflare or the key is not set.
     */
    internal suspend fun cloudflareCredentials(): CloudflareCredentials? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readSettings()
            if (current.provider != SummaryProviderType.CLOUDFLARE || current.cloudflareAccountId.isBlank()) return@withLock null
            val token = try { readCloudflareToken() } catch (_: SummaryException) { null } ?: return@withLock null
            CloudflareCredentials(current.cloudflareAccountId, token, current.modelName)
        }
    }

    /** True when summaries use the offline fake provider (questions then use the fake generator too). */
    internal fun usesFakeProvider(): Boolean = readSettings().provider == SummaryProviderType.FAKE

    private fun readSettings(): SummarySettings {
        val selected = storedSummaryProvider(prefs.getString("provider", null))
        return SummarySettings(
            selected,
            prefs.getString("model_${selected.name}", null) ?: SummaryDefaults.modelName(selected),
            prefs.getString("cf_account_id", null).orEmpty(),
            prefs.getBoolean("show_titles_driving", true),
        )
    }

    internal suspend fun edit(change: SharedPreferences.Editor.() -> Unit): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!prefs.edit().apply(change).commit()) throw SummaryException(SummaryErrorCode.STORAGE)
                // commit listeners can be dispatched to main; publish the complete snapshot now too.
                state.value = readSettings()
            } catch (safe: SummaryException) {
                throw safe
            } catch (_: Exception) {
                throw SummaryException(SummaryErrorCode.STORAGE)
            }
        }
    }

    private fun readCloudflareToken(): String? {
        val ciphertext = prefs.getString("cf_token_ciphertext", null) ?: return null
        val iv = prefs.getString("cf_token_iv", null) ?: throw SummaryException(SummaryErrorCode.KEY_UNAVAILABLE)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(create = false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.updateAAD(AAD)
            return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // Restored ciphertext without its device Keystore key must fail closed.
            throw SummaryException(SummaryErrorCode.KEY_UNAVAILABLE)
        }
    }

    private fun secretKey(create: Boolean = true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw SummaryException(SummaryErrorCode.KEY_UNAVAILABLE)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "talkrec.summary.cloudflare-token.v1"
        val AAD = "talkrec_summary/cloudflare_token/v1".toByteArray(Charsets.UTF_8)
        const val TEST_DIALOGUE = "오늘 학교 화단에서 달팽이를 봤어\n비가 와서 나왔나 봐"
    }
}
