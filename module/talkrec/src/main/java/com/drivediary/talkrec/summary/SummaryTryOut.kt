package com.drivediary.talkrec.summary

import kotlinx.coroutines.CancellationException

data class ConnectionTestResult(
    val ok: Boolean,
    val elapsedMs: Long,
    val result: SummaryResult? = null,
    val modelName: String? = null,
    val errorCode: String? = null,
)

/** Shared by the settings connection test and live transcript try-out. No input retention. */
internal suspend fun runSummaryTryOut(
    transcript: String, validator: () -> SummaryValidator, createProvider: suspend () -> SummaryProvider,
): ConnectionTestResult {
    val start = System.nanoTime()
    fun elapsed() = (System.nanoTime() - start) / 1_000_000
    return try {
        val provider = createProvider()
        val result = validatedSummary(validator()) { provider.summarize(transcript) }
        ConnectionTestResult(true, elapsed(), result, provider.modelName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (safe: SummaryException) {
        ConnectionTestResult(false, elapsed(), errorCode = safe.code.name.lowercase())
    } catch (_: Exception) {
        ConnectionTestResult(false, elapsed(), errorCode = "connection_error")
    }
}
