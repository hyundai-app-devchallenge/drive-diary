package com.drivediary.talkrec.summary

import android.content.SharedPreferences

/** Release has no lab provider or relay settings API. */
enum class SummaryProviderType { CLOUDFLARE, FAKE }

internal object SummaryBuildProviders {
    fun modelName(provider: SummaryProviderType): String = when (provider) {
        SummaryProviderType.CLOUDFLARE -> SummaryDefaults.CLOUDFLARE_MODEL
        SummaryProviderType.FAKE -> SummaryDefaults.FAKE_MODEL
    }
    fun createExtra(provider: SummaryProviderType, model: String, validator: SummaryValidator,
        prefs: SharedPreferences): SummaryProvider = throw SummaryException(SummaryErrorCode.INVALID_SETTINGS)
}
