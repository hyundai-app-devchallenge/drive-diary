package com.dongbantest.app.data.talk

import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.data.repository.TripRepository
import com.drivediary.talkrec.clip.ClipWriter
import com.drivediary.talkrec.sink.KeptBlock
import com.drivediary.talkrec.sink.TalkSink

/** The repository holds the storage lock until PCM has been encoded and both rows committed. */
class TalkSinkImpl(
    private val repository: TripRepository,
    private val consent: RecordingConsentStore,
    private val writer: ClipWriter = ClipWriter(),
    private val onPendingSaved: (Long) -> Unit = {},
) : TalkSink {
    override suspend fun onKeptBlock(block: KeptBlock): Boolean {
        repository.saveTalkClip(
            block, canSave = { consent.get(block.tripId) == true },
            sendToAi = { consent.getSendToAi(block.tripId) },
            writeAudio = { writer.write(block.audio.pcm, it) },
        ) ?: return false
        return true
    }

    override suspend fun onRunClosed(tripId: Long, runId: String) {
        repository.closeTalkRun(tripId, runId)?.let { id ->
            try { onPendingSaved(id) } catch (_: IllegalStateException) { /* Recovered at startup. */ }
        }
    }

    override suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String): Boolean =
        repository.deletePreviousSavedClip(tripId, refusalId)

    override suspend fun savedAutoDurationMs(sinceMs: Long, untilMs: Long): Long =
        repository.savedAutoDurationMs(sinceMs, untilMs)
}
