package com.dongbantest.app.audio

import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VoicePlayback(
    val path: String,
    val isPlaying: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** 화면의 메인 스레드에서 한 번에 하나의 대화를 재생한다. */
class VoicePlayer(
    private val scope: CoroutineScope,
    private val onError: () -> Unit,
) {
    private data class Clip(val path: String, val startMs: Long, val endMs: Long)

    private val _playback = MutableStateFlow<VoicePlayback?>(null)
    val playback = _playback.asStateFlow()
    private var clips = emptyList<Clip>()
    private var clipIndex = 0
    private var player: MediaPlayer? = null
    private var prepared = false
    private var started = false
    private var seekingToMs: Long? = null
    private var loadJob: Job? = null
    private var progressJob: Job? = null

    fun toggle(paths: List<String>) {
        val firstPath = paths.firstOrNull() ?: return onError()
        val current = _playback.value
        if (current != null && current.path in paths) {
            if (current.isPlaying) {
                mediaAction {
                    if (prepared && seekingToMs == null) updatePosition()
                    if (started) player?.pause()
                    started = false
                    _playback.value = _playback.value?.copy(isPlaying = false)
                    stopProgressUpdates()
                }
            } else {
                _playback.value = current.copy(isPlaying = true)
                if (current.durationMs > 0 && current.positionMs == current.durationMs) {
                    seekTo(0)
                } else {
                    mediaAction { startIfReady() }
                }
            }
            return
        }

        stop()
        _playback.value = VoicePlayback(path = firstPath)
        loadJob = scope.launch {
            clips = withContext(Dispatchers.IO) {
                var offset = 0L
                paths.mapNotNull { path ->
                    ensureActive()
                    val duration = readDuration(path) ?: return@mapNotNull null
                    Clip(path, offset, offset + duration).also { offset = it.endMs }
                }
            }
            val duration = clips.lastOrNull()?.endMs
            if (duration == null) {
                fail()
            } else {
                _playback.value = _playback.value?.copy(durationMs = duration)
                openClip(0)
            }
        }
    }

    fun seek(paths: List<String>, positionMs: Long) {
        if (_playback.value?.path in paths) seekTo(positionMs)
    }

    private fun seekTo(positionMs: Long) {
        val current = _playback.value ?: return
        if (clips.isEmpty()) return
        val position = positionMs.coerceIn(0, current.durationMs)
        if (position == current.durationMs) {
            finish()
            return
        }
        _playback.value = current.copy(positionMs = position)
        val targetIndex = clips.indexOfFirst { position < it.endMs }
        if (player == null || clipIndex != targetIndex) {
            openClip(targetIndex)
        } else if (prepared && seekingToMs == null) {
            mediaAction { player?.let { requestSeek(it, position) } }
        }
    }

    private fun openClip(index: Int): Unit = mediaAction {
        releasePlayer()
        clipIndex = index
        val clip = clips[index]
        val p = MediaPlayer()
        player = p
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        p.setDataSource(clip.path)
        p.setOnPreparedListener { preparedPlayer ->
            if (player === preparedPlayer) mediaAction {
                prepared = true
                val position = _playback.value?.positionMs ?: return@mediaAction
                if (position > clip.startMs) requestSeek(preparedPlayer, position) else startIfReady()
            }
        }
        p.setOnSeekCompleteListener { seeked ->
            if (player === seeked) mediaAction {
                val position = _playback.value?.positionMs ?: return@mediaAction
                if (position != seekingToMs) {
                    requestSeek(seeked, position)
                } else {
                    seekingToMs = null
                    startIfReady()
                }
            }
        }
        p.setOnCompletionListener { completed ->
            if (player === completed && seekingToMs == null) {
                val nextIndex = index + 1
                if (nextIndex == clips.size) {
                    finish()
                } else {
                    _playback.value = _playback.value?.copy(positionMs = clip.endMs)
                    openClip(nextIndex)
                }
            }
        }
        p.setOnErrorListener { failed, _, _ ->
            if (player === failed) fail()
            true
        }
        p.prepareAsync()
    }

    private fun requestSeek(p: MediaPlayer, positionMs: Long) {
        seekingToMs = positionMs
        p.seekTo(positionMs - clips[clipIndex].startMs, MediaPlayer.SEEK_CLOSEST)
    }

    private fun startIfReady() {
        val p = player ?: return
        if (!prepared || seekingToMs != null || _playback.value?.isPlaying != true) return
        p.start()
        started = true
        if (progressJob != null) return
        progressJob = scope.launch {
            while (true) {
                delay(PROGRESS_UPDATE_MS)
                if (prepared && seekingToMs == null) mediaAction { updatePosition() }
            }
        }
    }

    private fun updatePosition() {
        val p = player ?: return
        val clip = clips[clipIndex]
        val position = (clip.startMs + p.currentPosition).coerceIn(clip.startMs, clip.endMs)
        _playback.value = _playback.value?.copy(positionMs = position)
    }

    private fun finish() {
        releasePlayer()
        stopProgressUpdates()
        _playback.value = _playback.value?.let { it.copy(positionMs = it.durationMs, isPlaying = false) }
    }

    fun stop() {
        loadJob?.cancel()
        loadJob = null
        stopProgressUpdates()
        releasePlayer()
        clips = emptyList()
        _playback.value = null
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun releasePlayer() {
        val previous = player
        player = null
        prepared = false
        started = false
        seekingToMs = null
        previous?.release()
    }

    private inline fun mediaAction(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            fail()
        }
    }

    private fun fail() {
        Log.w("VoicePlayer", "playback failed")
        stop()
        onError()
    }

    private fun readDuration(path: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val PROGRESS_UPDATE_MS = 200L
    }
}
