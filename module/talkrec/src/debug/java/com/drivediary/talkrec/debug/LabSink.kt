package com.drivediary.talkrec.debug

import com.drivediary.talkrec.sink.KeptBlock
import com.drivediary.talkrec.sink.TalkSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** IDs/counters only. Never owns PCM, transcript, a repository, or a file. */
data class LabSinkState(
    val keptCount: Int = 0, val previousBlockId: String? = null,
    val deletedBlockId: String? = null, val refusalId: String? = null,
)

class LabSink : TalkSink {
    private val mutableState = MutableStateFlow(LabSinkState())
    val state = mutableState.asStateFlow()
    private var cleared = false
    private val keptIds = ArrayDeque<String>()
    private val refusals = mutableSetOf<String>()

    override suspend fun onKeptBlock(block: KeptBlock): Boolean = synchronized(this) {
        if (!cleared) {
            keptIds.addLast(block.id)
            mutableState.value = mutableState.value.copy(keptCount = keptIds.size, previousBlockId = block.id)
        }
        true
    }

    override suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String): Boolean = synchronized(this) {
        val current = mutableState.value
        if (cleared || !refusals.add(refusalId) || keptIds.isEmpty()) return@synchronized false
        val deleted = keptIds.removeLast()
        mutableState.value = current.copy(keptCount = keptIds.size,
            previousBlockId = keptIds.lastOrNull(), deletedBlockId = deleted, refusalId = refusalId)
        true
    }

    @Synchronized fun clear() {
        cleared = true; keptIds.clear(); refusals.clear(); mutableState.value = LabSinkState()
    }
}
