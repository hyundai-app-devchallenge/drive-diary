package com.dongbantest.app.data.ask

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** The installed Pleos SDKs expose our STT/TTS events, but no Gleo session/utterance event.
 * Observe playback before starting our own TTS. Public Android APIs do not expose player UIDs.
 * No audio focus is requested here: taking focus could interrupt Gleo's acknowledgement.
 */
internal class GleoPlaybackWait(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    suspend fun awaitQuiet() {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                changes.trySend(Unit)
            }
        }
        var registered = false
        try {
            try {
                audio?.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
                registered = audio != null
            } catch (_: RuntimeException) { /* Poll if callback registration is unavailable. */ }
            AssistantSpeechWait(
                nowMs = SystemClock::elapsedRealtime,
                playing = {
                    try {
                        audio?.activePlaybackConfigurations?.any {
                            val attributes = it.audioAttributes
                            attributes.usage in SPEECH_USAGES ||
                                attributes.contentType == AudioAttributes.CONTENT_TYPE_SPEECH
                        }
                    } catch (_: RuntimeException) { null }
                },
                awaitChange = { timeout -> withTimeoutOrNull(timeout) { changes.receive() }; Unit },
            ).awaitQuiet()
        } finally {
            if (registered) runCatching { audio?.unregisterAudioPlaybackCallback(callback) }
            changes.cancel()
        }
    }

    private companion object {
        val SPEECH_USAGES = setOf(
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            // Some devices hide other apps' audio attributes. Conservatively wait for these too.
            AudioAttributes.USAGE_UNKNOWN,
        )
    }
}
