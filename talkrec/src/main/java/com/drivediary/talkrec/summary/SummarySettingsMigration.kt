package com.drivediary.talkrec.summary

import android.content.SharedPreferences

internal fun storedSummaryProvider(value: String?): SummaryProviderType =
    SummaryProviderType.entries.firstOrNull { it.name == value } ?: SummaryProviderType.CLOUDFLARE

/** Delete legacy credentials without decrypting them, including restored/unreadable ciphertext.
 * Idempotent and atomic; all unrelated preferences (especially consent/display) are retained. */
internal fun migrateSummarySettings(prefs: SharedPreferences) {
    val oldProvider = prefs.getString("provider", null)
    val editor = prefs.edit().remove("api_ciphertext").remove("api_iv").remove("model_GEMINI")
    if (oldProvider != storedSummaryProvider(oldProvider).name)
        editor.putString("provider", storedSummaryProvider(oldProvider).name)
    if (!editor.commit()) throw SummaryException(SummaryErrorCode.STORAGE)
}
