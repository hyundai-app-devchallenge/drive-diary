package com.drivediary.talkrec.summary

import android.content.SharedPreferences

enum class SummaryProviderType { CLOUDFLARE, AGY, FAKE }

internal object SummaryBuildProviders {
    fun modelName(provider: SummaryProviderType): String = when (provider) {
        SummaryProviderType.CLOUDFLARE -> SummaryDefaults.CLOUDFLARE_MODEL
        SummaryProviderType.AGY -> AgySummaryDefaults.MODEL
        SummaryProviderType.FAKE -> SummaryDefaults.FAKE_MODEL
    }
    fun createExtra(provider: SummaryProviderType, model: String, validator: SummaryValidator,
        prefs: SharedPreferences): SummaryProvider {
        if (provider != SummaryProviderType.AGY) throw SummaryException(SummaryErrorCode.INVALID_SETTINGS)
        return AgyBridgeSummaryProvider(validator,
            prefs.getString("relay_base", null) ?: AgySummaryDefaults.RELAY_BASE_URL, model)
    }
}

/** Relay settings API is available only to debug clients. */
fun SummarySettingsStore.relayBaseUrl(): String =
    prefs.getString("relay_base", null) ?: AgySummaryDefaults.RELAY_BASE_URL

suspend fun SummarySettingsStore.setRelayBaseUrl(value: String) {
    val checked = checkedRelayBase(value.trim())
    edit { putString("relay_base", checked) }
}
