package com.drivediary.talkrec.capture

import com.drivediary.talkrec.block.*
import com.drivediary.talkrec.sink.*
import com.drivediary.talkrec.policy.PolicyGate
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptureRunsTest {
    private class Sink : TalkSink {
        val closed = mutableListOf<String>()
        val saved = mutableListOf<KeptBlock>()
        var accept = true
        override suspend fun onKeptBlock(block: KeptBlock): Boolean {
            if (accept) saved += block
            return accept
        }
        override suspend fun onRunClosed(tripId: Long, runId: String) { closed += runId }
        override suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String): Boolean =
            if (saved.isEmpty()) false else { saved.removeAt(saved.lastIndex); true }
    }
    private fun block(start: Long, reason: CloseReason = CloseReason.LENGTH) = ConversationBlock(
        listOf(FinalLine("종이별 이야기", start + 1)), start, start + 90_000, CaptureSource.AUTO, reason)

    @Test fun lengthChainTerminalReasonsGapAndTwelveClipLimit() = runBlocking {
        val sink = Sink(); val runs = CaptureRuns(1, sink)
        val ids = (0..12).map { i -> val b = block(i * 90_000L); runs.prepare(b).also { runs.saved("$i", b) } }
        assertEquals(1, ids.take(12).distinct().size)
        assertNotEquals(ids[11], ids[12]); assertEquals(listOf(ids[0]), sink.closed)
        runs.close(); runs.close() // pause then stop, idempotent
        assertEquals(listOf(ids[0], ids[12]), sink.closed)
        for (reason in listOf(CloseReason.SILENCE, CloseReason.MANUAL)) {
            val b = block(2_000_000, reason); val id = runs.prepare(b); runs.saved(reason.name, b)
            assertEquals(id, sink.closed.last())
        }
        val a = block(3_000_000); val first = runs.prepare(a); runs.saved("a", a)
        val b = block(a.endMs + 2_001); assertNotEquals(first, runs.prepare(b))
        assertEquals(first, sink.closed.last())
        runs.dropped(); assertEquals(5, sink.closed.size) // empty candidate has no close callback
    }

    @Test fun droppedAndRetroDeletedClipsEndRunWithoutSendingEmptyRun() = runBlocking {
        val sink = Sink(); val runs = CaptureRuns(1, sink)
        val b = block(0); val id = runs.prepare(b); runs.saved("a", b)
        runs.prepare(block(90_000)); runs.dropped(true)
        assertTrue(sink.closed.isEmpty())
        val next = runs.prepare(block(180_000)); assertNotEquals(id, next)
        runs.saved("b", block(180_000)); runs.dropped()
        assertEquals(listOf(next), sink.closed)
        runs.close(); assertEquals(1, sink.closed.size)
    }

    @Test fun processorClosesOnlyAfterSaveAndSinkRejectionBreaksChain() = runBlocking {
        val sink = Sink()
        val policy = PolicyGate.fromAssets { File(System.getProperty("talkrec.assetsDir"), it).inputStream() }
        val processor = CaptureProcessor(1, policy, sink, CaptureBudget(), null)
        suspend fun save(b: ConversationBlock) = processor.process(b, PcmSlice(b.startMs, b.endMs, ByteArray(320)))
        assertEquals(1, save(block(0))); assertTrue(sink.closed.isEmpty())
        sink.accept = false; assertEquals(0, save(block(90_000)))
        assertEquals(listOf(sink.saved.single().runId), sink.closed)
        sink.accept = true; save(block(180_000, CloseReason.MANUAL))
        assertEquals(2, sink.closed.size)
        assertNotEquals(sink.saved[0].runId, sink.saved[1].runId)
        assertEquals(CloseReason.MANUAL, sink.saved[1].closeReason)
        processor.closeRun(); processor.closeRun(); assertEquals(2, sink.closed.size)
    }

    @Test fun retroDeleteAfterGapRemovesOnlyMemberBeforeClosing() = runBlocking {
        val sink = Sink()
        val policy = PolicyGate.fromAssets { File(System.getProperty("talkrec.assetsDir"), it).inputStream() }
        val processor = CaptureProcessor(1, policy, sink, CaptureBudget(), null)
        processor.process(block(0), PcmSlice(0, 90_000, ByteArray(320)))
        val refusal = ConversationBlock(listOf(FinalLine("녹음하지 마", 300_000)),
            300_000, 301_000, CaptureSource.AUTO, CloseReason.SILENCE)
        assertEquals(-1, processor.process(refusal, PcmSlice(300_000, 301_000, ByteArray(320))))
        assertTrue(sink.saved.isEmpty()); assertTrue(sink.closed.isEmpty())
        processor.closeRun(); assertTrue(sink.closed.isEmpty())
    }
}
