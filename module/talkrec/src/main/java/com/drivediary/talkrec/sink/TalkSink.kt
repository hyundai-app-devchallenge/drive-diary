package com.drivediary.talkrec.sink

import com.drivediary.talkrec.block.CaptureSource
import com.drivediary.talkrec.block.CloseReason
import com.drivediary.talkrec.block.FinalLine
import com.drivediary.talkrec.capture.PcmSlice
import com.drivediary.talkrec.judge.ConversationKind

/** I4 implements durable storage. Calls are ordered on IO and must support cancellation.
 * Commit clip + metadata atomically and deduplicate by block.id / refusalId.
 */
interface TalkSink {
    /** Return true only after saving. PCM is borrowed until return; copy it if retaining asynchronously. */
    suspend fun onKeptBlock(block: KeptBlock): Boolean

    /** Ordered after the last save acknowledgement; empty runs never reach this callback. */
    suspend fun onRunClosed(tripId: Long, runId: String) {}

    /** Delete exactly the previous saved clip for this trip, once per refusalId. Return whether deleted. */
    suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String): Boolean

    /** Durable AUTO save history across trips. Null selects the process-local budget (debug sinks). */
    suspend fun savedAutoDurationMs(sinceMs: Long, untilMs: Long): Long? = null
}

class KeptBlock(
    val id: String, val tripId: Long, val lines: List<FinalLine>,
    val startMs: Long, val endMs: Long, val keepScore: Double?,
    val kind: ConversationKind?, val source: CaptureSource, val audio: PcmSlice,
    val runId: String = java.util.UUID.randomUUID().toString(),
    val closeReason: CloseReason = CloseReason.SILENCE,
) {
    override fun toString() = "KeptBlock(id=$id, lines=${lines.size}, startMs=$startMs, endMs=$endMs, source=$source)"
}
