package com.drivediary.talkrec.capture

import com.drivediary.talkrec.*
import com.drivediary.talkrec.block.*
import com.drivediary.talkrec.judge.ConversationJudgment
import com.drivediary.talkrec.policy.AppSignals
import com.drivediary.talkrec.policy.PolicyGate
import com.drivediary.talkrec.sink.KeptBlock
import com.drivediary.talkrec.sink.TalkSink
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptureProcessorDebugTest {
    // Run subscriptions/cancellation to quiescence, including collectLatest's child jobs.
    // A wall-clock delay is not a subscription barrier on the Gradle test runtime.
    private class EventDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runCurrent() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Sink : TalkSink {
        var accept = true
        var previous = false
        override suspend fun onKeptBlock(block: KeptBlock): Boolean { previous = accept; return accept }
        override suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String): Boolean =
            previous.also { previous = false }
    }
    private fun block(vararg lines: String) = ConversationBlock(lines.map { FinalLine(it, 1000) },
        1000, 2000, CaptureSource.AUTO, CloseReason.SILENCE)
    private fun audio(empty: Boolean = false) = PcmSlice(1000, 2000, if (empty) byteArrayOf() else ByteArray(32000) { 1 })
    private fun policy() = PolicyGate.fromAssets { File(System.getProperty("talkrec.assetsDir"), it).inputStream() }

    @Test fun everyClosedBlockHasExactlyOneMatchingDecisionAcrossAllOutcomes() = runBlocking {
        val dispatcher = EventDispatcher()
        val received = mutableListOf<TalkDebugEvent>()
        TalkRecDebug.enabled = true
        val collector = launch(dispatcher) { TalkRecDebug.events.collect { received += it } }
        try {
            dispatcher.runCurrent()
            val sink = Sink()
            val processor = CaptureProcessor(0, policy(), sink, CaptureBudget(2000), { ConversationJudgment(.9, true, null) })
            suspend fun check(outcome: DebugOutcome, block: ConversationBlock, signals: AppSignals? = null,
                              processorToUse: CaptureProcessor = processor, empty: Boolean = false, delta: Int = 0) {
                val before = received.size
                assertEquals(delta, processorToUse.process(block, audio(empty), signals))
                dispatcher.runCurrent()
                val events = received.drop(before)
                val closed = events.filterIsInstance<TalkDebugEvent.BlockClosed>().single()
                val decision = events.filterIsInstance<TalkDebugEvent.Decision>().single()
                assertEquals(closed.blockId, decision.blockId)
                assertEquals(block.lines.map { it.text }, closed.lines)
                assertEquals(block.source, closed.source); assertEquals(block.reason, closed.reason)
                assertEquals(1000L, closed.durationMs)
                assertEquals(outcome, decision.outcome)
                assertEquals(delta, decision.savedCountDelta)
                if (outcome in listOf(DebugOutcome.POLICY_BLOCK, DebugOutcome.RETRO_DELETE, DebugOutcome.COMMAND_LINES_DROPPED)) {
                    assertTrue(decision.policyReasons.isNotEmpty()); assertNull(decision.judgeMs)
                }
                if (outcome == DebugOutcome.JUDGE_DROP) assertEquals(.01, decision.keepProbability!!, 0.0)
                if (outcome == DebugOutcome.SAVED) assertNotNull(decision.judgeMs)
            }
            check(DebugOutcome.POLICY_BLOCK, block("별 이야기"), AppSignals(externalAudioDominant = true))
            val drop = CaptureProcessor(0, policy(), sink, CaptureBudget(), { ConversationJudgment(.01, false, null) })
            check(DebugOutcome.JUDGE_DROP, block("별 이야기"), processorToUse = drop)
            check(DebugOutcome.SAVED, block("종이별이 반짝여"), delta = 1)
            check(DebugOutcome.RETRO_DELETE, block("녹음하지 마"), delta = -1)
            check(DebugOutcome.POLICY_BLOCK, block("녹음하지 마")) // Nothing left to delete.
            check(DebugOutcome.COMMAND_LINES_DROPPED, block("별을 찾았어", "볼륨 올려", "다음에도 찾자"))
            check(DebugOutcome.EMPTY, block(""), empty = true)
            sink.accept = false
            check(DebugOutcome.SINK_REJECTED, block("별 이야기"))
            sink.accept = true
            val failed = CaptureProcessor(0, policy(), sink, CaptureBudget(), { error("synthetic") })
            check(DebugOutcome.JUDGE_FAILED, block("별 이야기"), processorToUse = failed, delta = 1)
            check(DebugOutcome.SAVED, block("별 이야기"), delta = 1)
            check(DebugOutcome.BUDGET, block("별 이야기"))
            assertEquals(received.filterIsInstance<TalkDebugEvent.BlockClosed>().map { it.blockId }.toSet(),
                received.filterIsInstance<TalkDebugEvent.Decision>().map { it.blockId }.toSet())
        } finally { TalkRecDebug.enabled = false; collector.cancel(); dispatcher.runCurrent(); collector.join() }
    }

    @Test fun cancellationAndUndeliveredFallbackShareTheSameDecisionLatch() = runBlocking {
        val dispatcher = EventDispatcher()
        val received = mutableListOf<TalkDebugEvent>()
        TalkRecDebug.enabled = true
        val collector = launch(dispatcher) { TalkRecDebug.events.collect { received += it } }
        try {
            dispatcher.runCurrent()
            val closed = block("별 이야기")
            val trace = TalkRecDebug.blockClosed("cancelled", closed)!!
            val enteringSink = CompletableDeferred<Unit>()
            val sink = object : TalkSink {
                override suspend fun onKeptBlock(block: KeptBlock): Boolean { enteringSink.complete(Unit); awaitCancellation() }
                override suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String) = false
            }
            val pcm = audio()
            val worker = launch { CaptureProcessor(0, policy(), sink, CaptureBudget(), null)
                .process(closed, pcm, blockId = "cancelled", trace = trace) }
            enteringSink.await(); worker.cancelAndJoin()
            trace.finish(); trace.finish()
            dispatcher.runCurrent()
            assertEquals(DebugOutcome.SINK_REJECTED, received.filterIsInstance<TalkDebugEvent.Decision>().single().outcome)
            assertTrue(pcm.pcm.all { it == 0.toByte() })
        } finally { TalkRecDebug.enabled = false; collector.cancel(); dispatcher.runCurrent(); collector.join() }
    }

    @Test fun disabledEmitsNothingAndRestartHasNoReplayOrOldDecisions() = runBlocking {
        val dispatcher = EventDispatcher()
        val received = mutableListOf<TalkDebugEvent>()
        TalkRecDebug.enabled = false
        val collector = launch(dispatcher) { TalkRecDebug.events.collect { received += it } }
        try {
            dispatcher.runCurrent()
            TalkRecDebug.emit(TalkDebugEvent.Partial("disabled"))
            CaptureProcessor(0, policy(), Sink(), CaptureBudget(), null).process(block("별 이야기"), audio())
            dispatcher.runCurrent(); assertTrue(received.isEmpty())
            TalkRecDebug.enabled = true
            dispatcher.runCurrent()
            val old = TalkRecDebug.blockClosed("old", block("old"))!!
            dispatcher.runCurrent()
            assertEquals("old", received.filterIsInstance<TalkDebugEvent.BlockClosed>().single().blockId)
            received.clear()
            TalkRecDebug.enabled = false
            // Deliberately coalesce off/on before the collector can run.
            TalkRecDebug.enabled = true
            TalkRecDebug.emit(TalkDebugEvent.Partial("before subscription"))
            dispatcher.runCurrent()
            assertTrue(received.isEmpty()) // replay=0 also applies during resubscription.
            old.finish()
            TalkRecDebug.level(100.0, 1000, 0); TalkRecDebug.level(200.0, 1100, 100); TalkRecDebug.level(300.0, 1200, 200)
            TalkRecDebug.level(400.0, 999999, 201) // Backlogged capture timestamps cannot cause a meter burst.
            dispatcher.runCurrent()
            assertEquals(listOf(TalkDebugEvent.Level(100.0, 1000), TalkDebugEvent.Level(300.0, 1200)), received)
            assertTrue(TalkRecDebug.events.replayCache.isEmpty())
        } finally { TalkRecDebug.enabled = false; collector.cancel(); dispatcher.runCurrent(); collector.join() }
    }

    @Test fun disablingCancelsSlowCollectorAndDiscardsItsBufferedTranscript() = runBlocking {
        val dispatcher = EventDispatcher()
        TalkRecDebug.enabled = true
        val entered = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        val collector = launch(dispatcher) {
            TalkRecDebug.events.collect {
                if (it is TalkDebugEvent.Partial) {
                    if (it.text == "blocked") { entered.complete(Unit); awaitCancellation() }
                    delivered += it.text
                }
            }
        }
        try {
            dispatcher.runCurrent()
            TalkRecDebug.emit(TalkDebugEvent.Partial("blocked")); dispatcher.runCurrent(); entered.await()
            repeat(300) { TalkRecDebug.emit(TalkDebugEvent.Partial("queued")) }
            TalkRecDebug.enabled = false; dispatcher.runCurrent()
            TalkRecDebug.enabled = true; dispatcher.runCurrent()
            TalkRecDebug.emit(TalkDebugEvent.Partial("fresh")); dispatcher.runCurrent()
            assertEquals(listOf("fresh"), delivered)
        } finally { TalkRecDebug.enabled = false; collector.cancel(); dispatcher.runCurrent(); collector.join() }
    }
}
