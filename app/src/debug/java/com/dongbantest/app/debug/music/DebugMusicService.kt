package com.dongbantest.app.debug.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.dongbantest.app.music.MusicSessionFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 별도 :musicprobe 프로세스에서 직접 만든 두 WAV를 재생한다. 출시 APK에는 없다. */
class DebugMusicService : Service() {
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sequenceJob: Job? = null
    private var stopJob: Job? = null
    private var player: MediaPlayer? = null
    private var track: String? = null
    private var metadata: MediaMetadata? = null
    private var state = PlaybackState.STATE_STOPPED
    private var lastPosition = 0L
    private var hasFocus = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "노래 기록 실험", NotificationManager.IMPORTANCE_LOW),
        )
        audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes())
            .setOnAudioFocusChangeListener({ change ->
                Log.i(TAG, "audioFocus callback change=$change")
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    hasFocus = false
                    sequenceJob?.cancel()
                    pause()
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        session = MediaSession(this, MusicSessionFilter.DEBUG_SESSION_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.i(TAG, "MediaSession.onPlay")
                    command(MUSIC_PLAY_A)
                }
                override fun onPause() {
                    Log.i(TAG, "MediaSession.onPause")
                    command(MUSIC_PAUSE)
                }
                override fun onStop() {
                    Log.i(TAG, "MediaSession.onStop")
                    command(MUSIC_STOP)
                }
            }, Handler(Looper.getMainLooper()))
            isActive = true
        }
        publishState(PlaybackState.STATE_STOPPED)
        Log.i(TAG, "DebugMusicService created pid=${android.os.Process.myPid()} process=:musicprobe")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8+의 시작 시간 제한 및 Android 15+의 오디오 포커스 조건을 먼저 충족한다.
        foreground()
        command(intent?.action ?: MUSIC_STOP)
        return START_NOT_STICKY
    }

    private fun command(action: String) {
        Log.i(TAG, "command $action")
        if (action == MUSIC_PLAY_A || action == MUSIC_PLAY_B || action == MUSIC_SEQUENCE) stopJob?.cancel()
        if (action != MUSIC_RESEND && action != MUSIC_RESEND_PLAYING) sequenceJob?.cancel()
        try {
            when (action) {
                MUSIC_PLAY_A -> play("A")
                MUSIC_PLAY_B -> play("B")
                MUSIC_PAUSE -> pause()
                MUSIC_STOP -> stop()
                MUSIC_RESEND -> metadata?.let {
                    // 새 Builder로 같은 값을 보낸다. 수신 측은 중복 콜백도 기록해야 한다.
                    session.setMetadata(MediaMetadata.Builder(it).build())
                    Log.i(TAG, "resent same metadata track=$track")
                }
                MUSIC_RESEND_PLAYING -> {
                    if (state == PlaybackState.STATE_PLAYING && player != null) {
                        publishState(PlaybackState.STATE_PLAYING)
                        Log.i(TAG, "resent PLAYING track=$track")
                    } else {
                        Log.i(TAG, "PLAYING resend skipped: A or B must be playing")
                    }
                }
                MUSIC_SEQUENCE -> {
                    sequenceJob = scope.launch {
                        try {
                            if (!play("A")) return@launch
                            delay(15_000)
                            pause()
                            delay(5_000)
                            if (!play("B")) return@launch
                            delay(15_000)
                            stop()
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            failure(e)
                        }
                    }
                }
                else -> stop()
            }
        } catch (e: Exception) {
            failure(e)
        }
    }

    private fun play(nextTrack: String): Boolean {
        if (!requestFocus()) return false
        releasePlayer()
        track = nextTrack
        lastPosition = 0L
        val nextPlayer = MediaPlayer()
        player = nextPlayer
        nextPlayer.setAudioAttributes(attributes())
        assets.openFd("music/tone_${nextTrack.lowercase()}.wav").use { fd ->
            nextPlayer.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        }
        nextPlayer.setOnCompletionListener {
            Log.i(TAG, "MediaPlayer.onCompletion track=$track")
            stop()
        }
        nextPlayer.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer.onError what=$what extra=$extra")
            sequenceJob?.cancel()
            stop()
            true
        }
        nextPlayer.prepare()
        metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "디버그 곡 $nextTrack")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "동반차 테스트")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "동반차 노래 기록 실험")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, nextPlayer.duration.toLong())
            .build()
        session.isActive = true
        session.setMetadata(metadata)
        nextPlayer.start()
        publishState(PlaybackState.STATE_PLAYING)
        return true
    }

    private fun pause() {
        player?.let { if (it.isPlaying) it.pause() }
        publishState(PlaybackState.STATE_PAUSED)
    }

    private fun stop() {
        stopJob?.cancel()
        lastPosition = currentPosition()
        releasePlayer()
        publishState(PlaybackState.STATE_STOPPED)
        if (hasFocus) audio.abandonAudioFocusRequest(focus)
        hasFocus = false
        // STOPPED 콜백을 보낸 뒤 세션 파괴도 관찰할 수 있게 짧게 기다린다.
        stopJob = scope.launch {
            delay(750)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun publishState(nextState: Int) {
        lastPosition = currentPosition()
        state = nextState
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(state, lastPosition, if (state == PlaybackState.STATE_PLAYING) 1f else 0f,
                SystemClock.elapsedRealtime())
            .build())
        Log.i(TAG, "published state=${stateName(state)} positionMs=$lastPosition track=$track")
        foreground()
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) {
            Log.w(TAG, "audio focus denied; sequence cannot prove real playback")
            stop()
        }
        return hasFocus
    }

    private fun foreground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MusicLabActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("노래 기록 실험: ${track ?: "대기"}")
            .setContentText(stateName(state))
            .setContentIntent(open)
            .setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun currentPosition(): Long =
        runCatching { player?.currentPosition?.toLong() ?: lastPosition }.getOrDefault(lastPosition)

    private fun failure(e: Exception) {
        Log.e(TAG, "player failure", e)
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        releasePlayer()
        if (hasFocus) audio.abandonAudioFocusRequest(focus)
        session.isActive = false
        session.release()
        Log.i(TAG, "DebugMusicService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MusicProbe"
        private const val CHANNEL = "debug_music_probe"
        private const val NOTIFICATION_ID = 7301
        const val MUSIC_PLAY_A = "com.dongbantest.app.debug.MUSIC_PLAY_A"
        const val MUSIC_PAUSE = "com.dongbantest.app.debug.MUSIC_PAUSE"
        const val MUSIC_PLAY_B = "com.dongbantest.app.debug.MUSIC_PLAY_B"
        const val MUSIC_STOP = "com.dongbantest.app.debug.MUSIC_STOP"
        const val MUSIC_RESEND = "com.dongbantest.app.debug.MUSIC_RESEND"
        const val MUSIC_RESEND_PLAYING = "com.dongbantest.app.debug.MUSIC_RESEND_PLAYING"
        const val MUSIC_SEQUENCE = "com.dongbantest.app.debug.MUSIC_SEQUENCE"
        val actions = setOf(MUSIC_PLAY_A, MUSIC_PAUSE, MUSIC_PLAY_B, MUSIC_STOP,
            MUSIC_RESEND, MUSIC_RESEND_PLAYING, MUSIC_SEQUENCE)

        fun send(context: Context, action: String) {
            require(action in actions)
            ContextCompat.startForegroundService(context, Intent(context, DebugMusicService::class.java).setAction(action))
        }

        private fun attributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        fun stateName(state: Int): String = when (state) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_NONE -> "NONE"
            else -> "STATE($state)"
        }
    }
}
