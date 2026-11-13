package com.drivediary.talkrec.summary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SummaryProcessorTest {
    private val validator = SummaryValidator(summaryPrompt().categories)

    private class Target : SummaryTarget {
        val statuses = linkedMapOf(1L to "PENDING", 2L to "SKIPPED")
        val saved = mutableMapOf<Long, Pair<SummaryResult, String>>()
        var consent = true
        override suspend fun pending() = statuses.filterValues { it == "PENDING" }.keys.map { PendingSummary(it, "지어낸 첫 줄\n둘째 줄") }
        override suspend fun canSend(id: Long): Boolean {
            if (statuses[id] != "PENDING") return false
            if (!consent) { statuses[id] = "SKIPPED"; return false }
            return true
        }
        override suspend fun save(id: Long, result: SummaryResult, model: String) {
            if (canSend(id)) { statuses[id] = "DONE"; saved[id] = result to model }
        }
        override suspend fun fail(id: Long) { if (canSend(id)) statuses[id] = "FAILED" }
    }

    private fun provider(call: suspend (String) -> SummaryResult) = object : SummaryProvider {
        override val modelName = "test-model"
        override suspend fun summarize(transcript: String) = call(transcript)
    }

    @Test fun fakeCompletesPendingAndNeverTouchesSkipped() = runBlocking {
        val target = Target()
        val processor = SummaryProcessor(target, validator) { FakeSummaryProvider(validator) }
        assertEquals(SummaryRunResult.COMPLETE, processor.run(0))
        assertEquals("DONE", target.statuses[1])
        assertEquals("SKIPPED", target.statuses[2])
        assertEquals(SummaryDefaults.FAKE_MODEL, target.saved.getValue(1).second)
        assertEquals(SummaryRunResult.COMPLETE, processor.run(0))
        assertEquals(1, target.saved.size)
    }

    @Test fun transientFailuresRetryAtMostFiveAttemptsThenFail() = runBlocking {
        for (code in listOf(SummaryErrorCode.NETWORK, SummaryErrorCode.TIMEOUT, SummaryErrorCode.SERVER_ERROR, SummaryErrorCode.RATE_LIMIT)) {
            val target = Target()
            var calls = 0
            val processor = SummaryProcessor(target, validator) { provider { calls++; throw SummaryException(code) } }
            for (attempt in 0..3) {
                assertEquals(SummaryRunResult.RETRY, processor.run(attempt))
                assertEquals("PENDING", target.statuses[1])
            }
            assertEquals(SummaryRunResult.COMPLETE, processor.run(4))
            assertEquals("FAILED", target.statuses[1])
            assertEquals("SKIPPED", target.statuses[2])
            assertTrue(target.saved.isEmpty())
            assertEquals(5, calls)
        }
    }

    @Test fun transientFailureCanRecover() = runBlocking {
        val target = Target()
        var calls = 0
        val processor = SummaryProcessor(target, validator) { provider {
            if (calls++ == 0) throw SummaryException(SummaryErrorCode.NETWORK)
            FakeSummaryProvider(validator).summarize(it)
        } }
        assertEquals(SummaryRunResult.RETRY, processor.run(0))
        assertEquals(SummaryRunResult.COMPLETE, processor.run(1))
        assertEquals("DONE", target.statuses[1])
    }

    @Test fun invalidOutputHasExactlyOneRetryThenFails() = runBlocking {
        val target = Target()
        var calls = 0
        val processor = SummaryProcessor(target, validator) { provider {
            calls++
            SummaryResult("x".repeat(26), validator.allowedCategories.first(), emptyList(), "story")
        } }
        assertEquals(SummaryRunResult.COMPLETE, processor.run(0))
        assertEquals(2, calls)
        assertEquals("FAILED", target.statuses[1])
    }

    @Test fun malformedOutputCanRecoverOnTheSingleRetry() = runBlocking {
        val target = Target()
        var calls = 0
        val processor = SummaryProcessor(target, validator) { provider {
            if (calls++ == 0) throw InvalidSummaryOutput()
            FakeSummaryProvider(validator).summarize(it)
        } }
        processor.run(0)
        assertEquals(2, calls)
        assertEquals("DONE", target.statuses[1])
    }

    @Test fun invalidKeywordsRetryOnceThenFailWithoutSaving() = runBlocking {
        val target = Target()
        var calls = 0
        SummaryProcessor(target, validator) { provider {
            calls++
            SummaryResult("지어낸 장면", validator.allowedCategories.first(), listOf("ABC", "abc"), "story")
        } }.run(0)
        assertEquals(2, calls)
        assertEquals("FAILED", target.statuses[1])
        assertTrue(target.saved.isEmpty())
    }

    @Test fun savesNormalizedCategoryFirstTagsAndEffectiveModel() = runBlocking {
        val target = Target()
        SummaryProcessor(target, validator) { provider {
            SummaryResult("지어낸 장면", "친구 이야기", listOf(" 탁구 ", "라켓"), "story")
        } }.run(0)
        val (result, model) = target.saved.getValue(1)
        assertEquals("DONE", target.statuses[1])
        assertEquals(listOf("친구 이야기", "탁구", "라켓"), result.tags)
        assertEquals("test-model", model)
    }

    @Test fun relayExhaustedValidationAndAuthFailImmediately() = runBlocking {
        for (code in listOf(SummaryErrorCode.INVALID_OUTPUT, SummaryErrorCode.AUTH, SummaryErrorCode.KEY_UNAVAILABLE)) {
            val target = Target()
            var calls = 0
            SummaryProcessor(target, validator) { provider { calls++; throw SummaryException(code) } }.run(0)
            assertEquals(1, calls)
            assertEquals("FAILED", target.statuses[1])
        }
    }

    @Test fun rechecksConsentBeforeRetryAndBeforeSaving() = runBlocking {
        val target = Target()
        var calls = 0
        SummaryProcessor(target, validator) { provider {
            calls++; target.consent = false; throw InvalidSummaryOutput()
        } }.run(0)
        assertEquals(1, calls)
        assertEquals("SKIPPED", target.statuses[1])
        val second = Target()
        SummaryProcessor(second, validator) { provider {
            second.consent = false
            FakeSummaryProvider(validator).summarize(it)
        } }.run(0)
        assertEquals("SKIPPED", second.statuses[1])
        assertTrue(second.saved.isEmpty())
    }

    @Test fun cancellationKeepsPendingAndPropagates() = runBlocking {
        val target = Target()
        try {
            SummaryProcessor(target, validator) { provider { throw CancellationException() } }.run(0)
            fail()
        } catch (_: CancellationException) { }
        assertEquals("PENDING", target.statuses[1])
    }

    @Test fun oneRetryingClipDoesNotBlockOtherClips() = runBlocking {
        val target = Target().apply { statuses[3] = "PENDING" }
        var selected = 0
        val processor = SummaryProcessor(target, validator) {
            if (selected++ == 0) provider { throw SummaryException(SummaryErrorCode.RATE_LIMIT) }
            else FakeSummaryProvider(validator)
        }
        assertEquals(SummaryRunResult.RETRY, processor.run(0))
        assertEquals("PENDING", target.statuses[1])
        assertEquals("DONE", target.statuses[3])
    }

    @Test fun alreadySkippedOrDeletedTargetsAreNeverSent() = runBlocking {
        val target = Target().apply { statuses[1] = "SKIPPED" }
        var calls = 0
        val processor = SummaryProcessor(target, validator) { calls++; FakeSummaryProvider(validator) }
        assertEquals(SummaryRunResult.COMPLETE, processor.run(0))
        assertEquals(0, calls)
    }

    @Test fun clipsAddedDuringBackoffGetTheirOwnFreshAttemptBudget() = runBlocking {
        val target = Target()
        val processor = SummaryProcessor(target, validator) { provider { throw SummaryException(SummaryErrorCode.NETWORK) } }
        assertEquals(SummaryRunResult.RETRY, processor.run(0, throughMemoryId = 1))
        target.statuses[3] = "PENDING"
        assertEquals(SummaryRunResult.COMPLETE, processor.run(4, throughMemoryId = 1))
        assertEquals("FAILED", target.statuses[1])
        assertEquals("PENDING", target.statuses[3])
        assertEquals(SummaryRunResult.RETRY, processor.run(0, throughMemoryId = 3))
        assertEquals("PENDING", target.statuses[3])
    }
}
