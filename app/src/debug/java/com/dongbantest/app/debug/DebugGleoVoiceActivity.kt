package com.dongbantest.app.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log

/** One Activity token per request. Android keeps a rejected start pending on its original token. */
class DebugGleoVoiceActivity : Activity() {
    private var requested = false
    private val startTimeout = Runnable {
        Log.w(TAG, "Gleo did not open a local voice session")
        setResult(NO_SESSION)
        finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || requested || isFinishing) return
        if (!isLocalVoiceInteractionSupported) {
            setResult(UNSUPPORTED)
            finish()
            return
        }
        try {
            requested = true
            startLocalVoiceInteraction(Bundle())
            window.decorView.postDelayed(startTimeout, 4_000)
        } catch (_: SecurityException) {
            requested = false
            setResult(DENIED)
            finish()
        }
    }

    override fun onLocalVoiceInteractionStarted() {
        super.onLocalVoiceInteractionStarted()
        window.decorView.removeCallbacks(startTimeout)
        Log.i(TAG, "Gleo local voice session started")
    }

    override fun onLocalVoiceInteractionStopped() {
        super.onLocalVoiceInteractionStopped()
        requested = false
        Log.i(TAG, "Gleo local voice session stopped")
        finish()
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(startTimeout)
        if (requested) stopLocalVoiceInteraction()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DebugGleoVoice"
        const val NO_SESSION = Activity.RESULT_FIRST_USER
        const val UNSUPPORTED = Activity.RESULT_FIRST_USER + 1
        const val DENIED = Activity.RESULT_FIRST_USER + 2
    }
}
