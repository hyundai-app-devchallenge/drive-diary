package com.dongbantest.app.data.ask

/** Monotonic clock and playback signals are supplied by the caller; no Android dependency. */
internal class AssistantSpeechWait(
    private val nowMs: () -> Long,
    private val playing: () -> Boolean?,
    private val awaitChange: suspend (timeoutMs: Long) -> Unit,
    private val minimumMs: Long = 1_200,
    private val maximumMs: Long = 8_000,
    private val quietMs: Long = 350,
) {
    init { require(minimumMs >= 0 && maximumMs >= minimumMs && quietMs >= 0) }

    suspend fun awaitQuiet() {
        val start = nowMs()
        var quietSince: Long? = null
        while (true) {
            val now = nowMs()
            val elapsed = now - start
            if (elapsed >= maximumMs) return
            // null means playback visibility is unavailable. Use the bounded fallback then.
            if (playing() == false) {
                if (quietSince == null) quietSince = now
                if (elapsed >= minimumMs && now - quietSince >= quietMs) return
            } else quietSince = null
            val remainingQuiet = quietSince?.let { (quietMs - (now - it)).coerceAtLeast(0) } ?: 0
            val untilReady = maxOf(minimumMs - elapsed, remainingQuiet)
            awaitChange(minOf(250, maximumMs - elapsed, untilReady.takeIf { it > 0 } ?: 250))
        }
    }
}
