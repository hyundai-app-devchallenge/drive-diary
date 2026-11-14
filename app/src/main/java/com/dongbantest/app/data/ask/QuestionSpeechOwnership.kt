package com.dongbantest.app.data.ask

/** Main-thread leases survive state/phase cleanup until the speech coroutine's finally runs. */
internal class QuestionSpeechOwnership {
    class Lease internal constructor(val tripId: Long, val onDemand: Boolean, val pauseNotice: Boolean)
    private val leases = mutableSetOf<Lease>()

    val onDemandSpeaking: Boolean get() = leases.any { it.onDemand }
    val pauseTripId: Long? get() = leases.firstOrNull { it.pauseNotice }?.tripId

    fun acquire(tripId: Long, onDemand: Boolean, pauseNotice: Boolean = false): Lease =
        Lease(tripId, onDemand, pauseNotice).also { leases += it }

    fun release(lease: Lease) { leases -= lease }

    fun canCancel(force: Boolean = false): Boolean = force || pauseTripId == null
}
