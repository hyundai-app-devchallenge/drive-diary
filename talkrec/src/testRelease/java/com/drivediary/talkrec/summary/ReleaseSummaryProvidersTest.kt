package com.drivediary.talkrec.summary

import org.junit.Assert.*
import org.junit.Test

class ReleaseSummaryProvidersTest {
    @Test fun releaseCannotSelectOrLoadRelay() {
        assertEquals(listOf("CLOUDFLARE", "FAKE"), SummaryProviderType.entries.map { it.name })
        assertEquals(SummaryProviderType.CLOUDFLARE, storedSummaryProvider("AGY"))
        for (name in listOf("AgyBridgeSummaryProvider", "AgySummaryDefaults", "SummaryBuildProvidersKt", "GeminiSummaryProvider")) {
            try { Class.forName("com.drivediary.talkrec.summary.$name"); fail() }
            catch (_: ClassNotFoundException) { }
        }
        assertFalse(SummarySettingsStore::class.java.methods.any { it.name.contains("Relay", ignoreCase = true) })
        val values = mutableMapOf<String, Any?>("provider" to "AGY")
        migrateSummarySettings(memoryPreferences(values))
        assertEquals("CLOUDFLARE", values["provider"])
    }
}
