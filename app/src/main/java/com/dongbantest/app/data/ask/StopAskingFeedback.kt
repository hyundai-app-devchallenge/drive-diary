package com.dongbantest.app.data.ask

import kotlinx.coroutines.CancellationException

/** Applies mute first; optional voice acknowledgement is excluded from capture until it really ends. */
internal class StopAskingFeedback(
    private val clock: () -> Long,
    private val beginNoRecord: (Long) -> ((Long) -> Unit),
) {
    suspend fun stop(source: String, applyMute: () -> Boolean, stopQuestion: () -> Unit, speak: suspend () -> Unit) {
        val applied = applyMute()
        stopQuestion()
        if (!applied || source != "voice") return
        val start = clock()
        val finish = beginNoRecord(start)
        try {
            speak()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // TTS is optional. Never log the sentence, keys or exception messages.
        } catch (_: LinkageError) {
            // A device without TTS must still remain muted.
        } finally {
            finish(maxOf(start, clock()) + AUDIO_GRACE_MS)
        }
    }

    companion object {
        const val AUDIO_GRACE_MS = 1_000L
    }
}
