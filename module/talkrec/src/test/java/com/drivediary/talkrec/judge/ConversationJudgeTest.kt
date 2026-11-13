package com.drivediary.talkrec.judge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationJudgeTest {
    private val threshold = 0.9573729634284973

    @Test
    fun thresholdIsInclusiveAndIndependentOfAuxiliaryNone() {
        var probability = threshold
        var logits = floatArrayOf(9f, 1f, 2f)
        val model = object : ConversationModel {
            override fun run(input: TokenizedInput) = ModelScores(probability, logits)
            override fun close() = Unit
        }
        ConversationJudge(JudgeTestAssets.tokenizer(), threshold, model).use { judge ->
            assertTrue(judge.judge(emptyList()).keep)
            assertNull(judge.judge(emptyList()).kind)
            probability = Math.nextDown(threshold)
            logits = floatArrayOf(0f, 3f, 1f)
            val drop = judge.judge(emptyList())
            assertFalse(drop.keep)
            assertEquals(ConversationKind.STORY, drop.kind)
            probability = Math.nextUp(threshold)
            logits = floatArrayOf(0f, 1f, 3f)
            assertEquals(ConversationKind.MOMENT, judge.judge(emptyList()).kind)
        }
    }

    @Test
    fun closeWaitsForInferenceAndIsIdempotent() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        var closedCount = 0
        val model = object : ConversationModel {
            override fun run(input: TokenizedInput): ModelScores {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                check(closedCount == 0)
                return ModelScores(0.5, floatArrayOf(0f, 1f, 2f))
            }
            override fun close() { closedCount++ }
        }
        val judge = ConversationJudge(JudgeTestAssets.tokenizer(), threshold, model)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val inference = pool.submit<ConversationJudgment> { judge.judge(emptyList()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = pool.submit { closing.countDown(); judge.close() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(close.isDone)
            release.countDown()
            assertFalse(inference.get(5, TimeUnit.SECONDS).keep)
            close.get(5, TimeUnit.SECONDS)
            judge.close()
            assertEquals(1, closedCount)
            assertThrows(IllegalStateException::class.java) { judge.judge(emptyList()) }
        } finally {
            release.countDown()
            pool.shutdownNow()
            judge.close()
        }
    }
}
