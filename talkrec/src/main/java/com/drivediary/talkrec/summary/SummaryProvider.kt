package com.drivediary.talkrec.summary

/** One request; SummaryProcessor/testConnection own the single invalid-output retry. */
interface SummaryProvider {
    /** Effective model after a successful call (including the relay's effort suffix). */
    val modelName: String
    suspend fun summarize(transcript: String): SummaryResult
}
