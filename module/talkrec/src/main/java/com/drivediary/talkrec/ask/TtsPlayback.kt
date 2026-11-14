package com.drivediary.talkrec.ask

import kotlinx.coroutines.CompletableDeferred

/** One speak call's callback state. Holds timing/status only, never the spoken text. */
class TtsPlayback {
    val finished = CompletableDeferred<String>()
    @Volatile var firstSoundAtMs: Long? = null
        private set

    fun started(atMs: Long) {
        if (!finished.isCompleted && firstSoundAtMs == null) firstSoundAtMs = atMs
    }

    fun engineStopped(): Boolean {
        // A new speak request can deliver the previous request's onStop before its own onStart.
        // App stop/release completes explicitly, so ignoring this stale SDK callback cannot block it.
        if (firstSoundAtMs == null) return false
        return finished.complete("STOPPED")
    }

    /** Explicit app stop/release/error must also finish a call that has not started playing yet. */
    fun finish(result: String) { finished.complete(result) }
}
