package com.dongbantest.app.debug.music

import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dongbantest.app.music.MusicAccess
import com.dongbantest.app.music.MusicListenerService
import com.dongbantest.app.music.NowPlayingWatcher
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.domain.model.TrackPlay
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MusicLabActivity : ComponentActivity() {
    private lateinit var access: MusicAccess
    private lateinit var watcher: NowPlayingWatcher
    private var granted by mutableStateOf(false)
    private var settingsTargets by mutableStateOf<List<String>>(emptyList())
    private var notice by mutableStateOf("")
    private var inspectedTripId by mutableStateOf<Long?>(null)
    private var trackRows by mutableStateOf<List<TrackPlay>>(emptyList())
    private val container get() = (application as DongbanApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        access = MusicAccess(this)
        watcher = MusicLabProbe.watcher(this)
        lifecycleScope.launch {
            container.tripRepository.observeOngoingTrip().collectLatest { trip ->
                // Keep the last inspected trip visible after ending it.
                if (trip != null) inspectedTripId = trip.id
                if (inspectedTripId == null) {
                    inspectedTripId = container.tripRepository.observeTrips().first().firstOrNull { !it.isSample }?.id
                }
                val id = inspectedTripId ?: return@collectLatest
                container.tripRepository.observeTripWithMemories(id)
                    .map { it?.tracks.orEmpty() }.distinctUntilChanged().collect { rows ->
                        trackRows = rows
                        Log.i("MusicProbe", "track_plays dump trip=$id count=${rows.size}")
                        rows.forEach { Log.i("MusicProbe", "track_plays row=$it") }
                    }
            }
        }
        setContent { DongbanchaV3Theme { Lab() } }
    }

    override fun onResume() {
        super.onResume()
        granted = access.isGranted()
        settingsTargets = access.settingsIntents().map {
            "${it.action} → ${it.resolveActivity(packageManager)?.flattenToShortString()}"
        }
        Log.i("MusicProbe", "lab resumed granted=$granted settings=$settingsTargets")
        watcher.refresh()
    }

    override fun onStop() {
        Log.i("MusicProbe", "lab onStop: watcher stays active (background evidence begins)")
        super.onStop()
    }

    private fun openSettings() {
        for (intent in access.settingsIntents()) {
            try {
                startActivity(intent)
                notice = "설정 실행 요청: ${intent.action} (실제로 열린 화면도 확인하세요)"
                Log.i("MusicProbe", notice)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w("MusicProbe", "settings unavailable: ${intent.action}", e)
            } catch (e: SecurityException) {
                Log.w("MusicProbe", "settings blocked: ${intent.action}", e)
            }
        }
        notice = "열 수 있는 알림 접근 설정이 없어요. 아래 adb 명령으로 개발용 허용을 확인하세요."
    }

    private fun send(action: String) {
        try {
            DebugMusicService.send(this, action)
            notice = "보냄: ${action.substringAfterLast('.')}"
        } catch (e: RuntimeException) {
            notice = "플레이어 시작 실패: ${e.message}"
            Log.e("MusicProbe", notice, e)
        }
    }

    @Composable
    private fun Lab() {
        val connected by MusicListenerService.connected.collectAsState()
        val snapshots by watcher.snapshots.collectAsState()
        val callbackLog by watcher.callbackLog.collectAsState()
        val status by watcher.status.collectAsState()
        val musicEnabled by container.musicSettingsStore.enabled.collectAsState()
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("노래 기록 실험실", style = MaterialTheme.typography.headlineMedium)
                        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        Text("알림 접근: ${if (granted) "허용" else "꺼짐"} · 리스너: ${if (connected) "연결됨" else "연결 안 됨"}\n$status")
                        Text("알림 내용은 읽지 않아요. 진행 중인 여행에서만 들은 노래를 DB에 저장해요.")
                        Text("들은 노래 기록: ${if (musicEnabled) "켜짐" else "꺼짐"}")
                        Switch(checked = musicEnabled, onCheckedChange = { container.musicSettingsStore.setEnabled(it) })
                        Button(onClick = { openSettings() }) { Text("설정 열기") }
                        Text(settingsTargets.joinToString("\n").ifEmpty { "resolveActivity: 열 수 있는 설정 없음" })
                        Text("Android 13 이상에서 스위치가 막히면 앱 정보 ⋮ → 제한된 설정 허용을 확인하세요.")
                        Text("adb shell cmd notification allow_listener com.dongbantest.app/com.dongbantest.app.music.MusicListenerService 10")
                        Button(onClick = { granted = access.isGranted(); watcher.refresh() }) { Text("권한·세션 다시 확인") }
                        Text("Pleos에서는 다른 앱(설정 등)을 앞으로 열어 백그라운드를 확인하세요. logcat -v threadtime -s MusicProbe:I 로 확인해요.")
                        if (notice.isNotEmpty()) Text(notice)
                        HorizontalDivider()
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("이 여행의 노래 행", style = MaterialTheme.typography.titleLarge)
                        Text("tripId=${inspectedTripId ?: "없음"} · ${trackRows.size}행 (재생 중에는 30초마다 갱신)")
                        Text("여행 종료 뒤에도 마지막 여행을 표시해요. 10초 미만 행은 곡 전환·기록 종료·10분 초과 쉼 뒤 삭제해요.")
                        if (trackRows.isEmpty()) Text("저장된 노래 없음")
                        trackRows.forEach { row ->
                            Text("id=${row.id} · ${row.title} · ${row.artist}\n" +
                                "startedAt=${row.startedAt} · endedAt=${row.endedAt}\n" +
                                "playedMs=${row.playedMs} · durationMs=${row.durationMs}\n" +
                                "album=${row.album} · source=${row.sourcePackage} (${row.sourceLabel})")
                        }
                        HorizontalDivider()
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("로컬 톤 플레이어 (:musicprobe)", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { send(DebugMusicService.MUSIC_PLAY_A) }) { Text("A 재생") }
                        Button(onClick = { send(DebugMusicService.MUSIC_PAUSE) }) { Text("일시정지") }
                        Button(onClick = { send(DebugMusicService.MUSIC_PLAY_B) }) { Text("B 재생") }
                        Button(onClick = { send(DebugMusicService.MUSIC_STOP) }) { Text("정지") }
                        Button(onClick = { send(DebugMusicService.MUSIC_RESEND) }) { Text("같은 메타데이터 다시 보내기") }
                        Button(onClick = { send(DebugMusicService.MUSIC_RESEND_PLAYING) }) { Text("PLAYING 상태 다시 보내기") }
                        Button(onClick = { send(DebugMusicService.MUSIC_SEQUENCE) }) { Text("순서 자동 실행 (A 15초 → 쉼 5초 → B 15초 → 정지)") }
                        HorizontalDivider()
                        Text("활성 세션 ${snapshots.size}개", style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (snapshots.isEmpty()) item { Text("활성 세션 없음 (권한과 플레이어 상태를 확인하세요)") }
                items(snapshots) { snapshot ->
                    Text("${snapshot.packageName}\ntag=${snapshot.tag}\n${snapshot.title ?: "(제목 없음)"} · ${snapshot.artist ?: "(가수 없음)"}\n" +
                        "${DebugMusicService.stateName(snapshot.state)} · album=${snapshot.album} · durationMs=${snapshot.durationMs} · positionMs=${snapshot.positionMs}\n" +
                        "수신 ${clock.format(Date(snapshot.time))}")
                }
                item {
                    HorizontalDivider()
                    Text("콜백 로그 (최근 200개, 최신순)", style = MaterialTheme.typography.titleLarge)
                }
                items(callbackLog.asReversed(), key = { it.id }) { row ->
                    Text("${clock.format(Date(row.time))} #${row.id} ${row.message}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
