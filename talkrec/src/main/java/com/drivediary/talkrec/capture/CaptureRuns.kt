package com.drivediary.talkrec.capture

import com.drivediary.talkrec.block.CloseReason
import com.drivediary.talkrec.block.ConversationBlock
import com.drivediary.talkrec.sink.TalkSink
import java.util.UUID

/** Owned by the ordered save consumer. Only acknowledged saves become run members. */
internal class CaptureRuns(private val tripId: Long, private val sink: TalkSink) {
    private var id: String? = null
    private val members = mutableListOf<String>()
    private var endMs = 0L
    private var reason = CloseReason.LENGTH

    suspend fun prepare(block: ConversationBlock): String {
        if (id != null && (reason != CloseReason.LENGTH || block.startMs - endMs !in 0..2_000L)) close()
        return id ?: UUID.randomUUID().toString().also { id = it }
    }

    suspend fun saved(blockId: String, block: ConversationBlock) {
        members += blockId
        endMs = block.endMs
        reason = block.reason
        if (reason != CloseReason.LENGTH || members.size == 12) close()
    }

    suspend fun dropped(retroDeleted: Boolean = false) {
        if (retroDeleted && members.isNotEmpty()) members.removeAt(members.lastIndex)
        close()
    }

    suspend fun close() {
        val closed = id ?: return
        id = null
        val nonempty = members.isNotEmpty()
        members.clear()
        if (nonempty) sink.onRunClosed(tripId, closed)
        if (nonempty) com.drivediary.talkrec.TalkRecDebug.runClosed(closed)
    }
}
