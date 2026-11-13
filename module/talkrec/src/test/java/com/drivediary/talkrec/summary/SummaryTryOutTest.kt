package com.drivediary.talkrec.summary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SummaryTryOutTest {
    private val validator = SummaryValidator(listOf("가족"))
    private val valid = SummaryResult("함께 본 별", "가족", emptyList(), "moment")

    @Test fun sendsExactTranscriptAndReportsEffectiveModelWithFakeProvider() = runBlocking {
        val expectedTranscript = "첫 줄\n다음 줄"
        val fake = object : SummaryProvider {
            override val modelName = "fake-effective"
            override suspend fun summarize(transcript: String): SummaryResult {
                assertEquals(expectedTranscript, transcript)
                return valid
            }
        }
        val result = runSummaryTryOut(expectedTranscript, { validator }, { fake })
        assertTrue(result.ok); assertEquals(valid, result.result)
        assertEquals("fake-effective", result.modelName); assertTrue(result.elapsedMs >= 0)
    }

    @Test fun usesSameValidationRetryAndSafeErrorsAsConnectionTest() = runBlocking {
        var calls = 0
        val invalid = object : SummaryProvider {
            override val modelName = "fake"
            override suspend fun summarize(transcript: String): SummaryResult { calls++; return valid.copy(title = "") }
        }
        val result = runSummaryTryOut("invented", { validator }, { invalid })
        assertFalse(result.ok); assertEquals("invalid_output", result.errorCode); assertEquals(2, calls)
        for (code in listOf(SummaryErrorCode.AUTH, SummaryErrorCode.REQUEST_TOO_LARGE, SummaryErrorCode.KEY_UNAVAILABLE)) {
            val failed = runSummaryTryOut("invented", { validator }, { throw SummaryException(code) })
            assertEquals(code.name.lowercase(), failed.errorCode); assertNull(failed.result)
        }
        assertEquals("connection_error", runSummaryTryOut("invented", { validator }, { error("private details") }).errorCode)
    }

    @Test fun cancellationPropagates() = runBlocking {
        try {
            runSummaryTryOut("invented", { validator }, { throw CancellationException() })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
