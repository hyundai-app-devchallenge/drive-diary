package com.dongbantest.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 저장된 음성 메모 재생기. 한 번에 하나의 파일만 재생한다. */
class VoicePlayer {

    private var player: MediaPlayer? = null
    private var playlist = emptyList<String>()
    private var index = 0
    private val _playingPath = MutableStateFlow<String?>(null)
    val playingPath: StateFlow<String?> = _playingPath.asStateFlow()

    /** @return 재생 시작 성공 여부 */
    fun toggle(path: String): Boolean = toggle(listOf(path))

    fun toggle(paths: List<String>): Boolean {
        if (paths.isEmpty()) return false
        if (_playingPath.value != null && playlist == paths) {
            stop()
            return true
        }
        stop()
        playlist = paths.toList()
        return playCurrent()
    }

    private fun playCurrent(): Boolean {
        val path = playlist.getOrNull(index) ?: return false
        return try {
            val p = MediaPlayer().also { player = it }.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(path)
                setOnCompletionListener { completed ->
                    if (player === completed) {
                        completed.release(); player = null
                        index++
                        if (index < playlist.size) playCurrent() else stop()
                    }
                }
                setOnErrorListener { _, _, _ -> stop(); true }
                prepare()
                start()
            }
            player = p
            _playingPath.value = path
            true
        } catch (_: Exception) {
            Log.w("VoicePlayer", "play failed")
            stop()
            false
        }
    }

    fun stop() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
        playlist = emptyList(); index = 0
        _playingPath.value = null
    }
}
