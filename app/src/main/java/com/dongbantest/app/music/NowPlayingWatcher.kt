package com.dongbantest.app.music

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** time은 수신 시각(벽시계 ms), positionMs는 소스가 마지막으로 보낸 위치다. */
data class NowPlaying(
    val packageName: String,
    val tag: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val state: Int,
    val positionMs: Long?,
    val time: Long,
)

data class MusicCallbackLog(val id: Long, val time: Long, val message: String)

/**
 * M01: 메모리의 스냅숏과 콜백 로그만 제공한다. DB/여행에는 연결하지 않는다.
 * start/refresh/stop은 메인 스레드에서 호출한다. 모든 시스템 콜백도 같은 Handler로 받는다.
 * stop을 호출할 때까지 관찰하므로 화면이 백그라운드로 가도 콜백이 계속 온다.
 */
class NowPlayingWatcher(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val access = MusicAccess(this.context)
    private val manager = this.context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val debug = this.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val entries = linkedMapOf<MediaSession.Token, Entry>()
    private var registered = false
    private var logId = 0L

    private val mutableSnapshots = MutableStateFlow<List<NowPlaying>>(emptyList())
    val snapshots: StateFlow<List<NowPlaying>> = mutableSnapshots.asStateFlow()
    private val mutableLog = MutableStateFlow<List<MusicCallbackLog>>(emptyList())
    val callbackLog: StateFlow<List<MusicCallbackLog>> = mutableLog.asStateFlow()
    private val mutableStatus = MutableStateFlow("관찰 전")
    val status: StateFlow<String> = mutableStatus.asStateFlow()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        record("onActiveSessionsChanged count=${controllers?.size ?: 0}")
        if (registered) guarded { replaceSessions(controllers.orEmpty()) }
    }

    @MainThread
    fun start() {
        if (registered) return
        guarded {
            // 일반 앱 경로: 시스템 전용 MEDIA_CONTENT_CONTROL 권한을 요청하지 않는다.
            manager.addOnActiveSessionsChangedListener(sessionsListener, access.component, handler)
            registered = true
            replaceSessions(manager.getActiveSessions(access.component))
            mutableStatus.value = "관찰 중 (getActiveSessions 성공)"
            record("getActiveSessions success count=${entries.size}")
        }
    }

    @MainThread
    fun refresh() {
        if (!registered) {
            start()
        } else {
            guarded {
                replaceSessions(manager.getActiveSessions(access.component))
                mutableStatus.value = "관찰 중 (getActiveSessions 성공)"
                record("getActiveSessions success count=${entries.size}")
            }
        }
    }

    @MainThread
    fun stop() {
        registered = false
        try {
            manager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: SecurityException) {
            // 권한을 끈 직후에도 정리할 수 있어야 한다.
        }
        entries.values.forEach { unregister(it) }
        entries.clear()
        mutableSnapshots.value = emptyList()
        mutableStatus.value = "관찰 종료"
    }

    @MainThread
    override fun close() = stop()

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            stop()
            mutableStatus.value = "알림 접근 권한 없음 (SecurityException)"
            record("SecurityException: ${e.message}")
        }
    }

    private fun replaceSessions(controllers: List<MediaController>) {
        val accepted = controllers.filter {
            MusicSessionFilter.accepts(context.packageName, it.packageName, sessionTag(it), debug)
        }
        val tokens = accepted.map { it.sessionToken }.toSet()
        entries.keys.filter { it !in tokens }.forEach { token ->
            entries.remove(token)?.let { unregister(it) }
        }
        val ordered = accepted.map { controller ->
            val token = controller.sessionToken
            token to (entries[token] ?: Entry(controller).also { entry ->
                entries[token] = entry
                controller.registerCallback(entry.callback, handler)
                entry.metadata = controller.metadata
                entry.playback = controller.playbackState
                record("attached ${entry.snapshot()}")
            })
        }
        entries.clear()
        entries.putAll(ordered)
        publish()
    }

    private fun unregister(entry: Entry) {
        try {
            entry.controller.unregisterCallback(entry.callback)
        } catch (_: SecurityException) {
            // 권한 회수 후의 콜백도 남기지 않는다.
        }
    }

    private fun publish() {
        mutableSnapshots.value = entries.values.map { it.snapshot() }
    }

    private fun sessionTag(controller: MediaController): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) controller.tag else null

    private fun record(message: String) {
        val row = MusicCallbackLog(++logId, System.currentTimeMillis(), message)
        mutableLog.value = (mutableLog.value + row).takeLast(200)
        // 메타데이터를 포함한 logcat 출력은 디버그 빌드에서만 한다. 중복 콜백도 모두 출력한다.
        if (debug) Log.i("MusicProbe", "#${row.id} time=${row.time} $message")
    }

    private inner class Entry(val controller: MediaController) {
        private val tag = sessionTag(controller)
        var metadata: MediaMetadata? = null
        var playback: PlaybackState? = null
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                this@Entry.metadata = metadata
                record("onMetadataChanged ${snapshot()}")
                if (entries[controller.sessionToken] === this@Entry) publish()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                playback = state
                record("onPlaybackStateChanged ${snapshot()}")
                if (entries[controller.sessionToken] === this@Entry) publish()
            }

            override fun onSessionDestroyed() {
                record("onSessionDestroyed ${snapshot()}")
                if (entries[controller.sessionToken] === this@Entry) {
                    entries.remove(controller.sessionToken)
                    unregister(this@Entry)
                    publish()
                }
            }
        }

        fun snapshot(): NowPlaying = NowPlaying(
            packageName = controller.packageName,
            tag = tag,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata?.takeIf { it.containsKey(MediaMetadata.METADATA_KEY_DURATION) }
                ?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            state = playback?.state ?: PlaybackState.STATE_NONE,
            positionMs = playback?.position?.takeIf { it != PlaybackState.PLAYBACK_POSITION_UNKNOWN },
            time = System.currentTimeMillis(),
        )
    }
}
