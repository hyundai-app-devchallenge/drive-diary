package com.drivediary.talkrec.summary

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

interface SummaryWorkerDependencies {
    val summaryTarget: SummaryTarget
    val summarySettingsStore: SummarySettingsStore
}

/** Implemented by the host Application; avoids a global singleton or custom WorkerFactory. */
interface SummaryWorkerOwner {
    val summaryWorkerDependencies: SummaryWorkerDependencies
}

class SummaryWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        return try {
            val dependencies = (applicationContext as? SummaryWorkerOwner)?.summaryWorkerDependencies
                ?: return Result.failure()
            val settings = dependencies.summarySettingsStore
            val throughMemoryId = inputData.getLong(SummaryWorkScheduler.THROUGH_MEMORY_ID, 0L)
            when (SummaryProcessor(dependencies.summaryTarget, settings.validator(), settings::createProvider)
                .run(runAttemptCount, throughMemoryId)) {
                SummaryRunResult.COMPLETE -> Result.success()
                SummaryRunResult.RETRY -> Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Storage/initialization failures are bounded too, with no sensitive error output.
            if (runAttemptCount < SummaryProcessor.MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }

}

/** A facade without WorkManager supertypes so hosts need only the talkrec module dependency. */
object SummaryWorkScheduler {
    const val UNIQUE_WORK_NAME = "talkrec.saved-clip-summaries"
    internal const val THROUGH_MEMORY_ID = "through_memory_id"

    fun enqueue(context: Context, throughMemoryId: Long) {
        val request = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInputData(workDataOf(THROUGH_MEMORY_ID to throughMemoryId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP can lose an enqueue between the running worker's final query and completion.
        // A successor drains anything saved during that window; completed rows are never resent.
        // The fixed ID ceiling keeps new clips out of an older worker's retry budget.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
