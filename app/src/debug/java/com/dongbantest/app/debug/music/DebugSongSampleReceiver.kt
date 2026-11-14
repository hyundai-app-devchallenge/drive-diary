package com.dongbantest.app.debug.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.data.local.TrackPlayEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 디버그 빌드 전용: 이미 있는 여행에 예시 노래 기록을 넣는다. 기록 상세 지도 탭(M03)을 실제 지도로 확인할 때 쓴다.
 * 곡들은 여행 시간 안에 고르게 흩어 놓는다. 위치 기록이 있는 여행(예: 예시 여행)에 넣어야 지도에 핀이 보인다.
 *
 *    adb shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_SAMPLE_SONGS -p com.dongbantest.app --el trip 12
 */
class DebugSongSampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tripId = intent.getLongExtra("trip", -1L).takeIf { it > 0 } ?: return
        val app = context.applicationContext as DongbanApplication
        val pending = goAsync()
        scope.launch {
            try {
                val data = app.container.tripRepository.observeTripWithMemories(tripId).first() ?: return@launch
                val points = data.drive.path.flatten().map { it.recordedAt }.sorted()
                val a = points.firstOrNull() ?: data.trip.startedAt
                val b = points.lastOrNull() ?: data.trip.endedAt ?: return@launch
                val dao = app.container.database.trackPlayDao()
                SAMPLES.forEachIndexed { i, (title, artist, source) ->
                    val start = a + (b - a) * (i * 2 + 1) / (SAMPLES.size * 2 + 1)
                    val length = minOf(4 * 60_000L, (b - a) / (SAMPLES.size * 2 + 1))
                    dao.insert(
                        TrackPlayEntity(
                            tripId = tripId, startedAt = start, endedAt = start + length, playedMs = length,
                            title = title, artist = artist, album = null, durationMs = length,
                            sourcePackage = "com.dongbantest.debug", sourceLabel = source,
                        )
                    )
                }
                Log.i(TAG, "trip=$tripId 에 예시 노래 ${SAMPLES.size}곡을 넣었어요")
            } catch (e: Exception) {
                Log.e(TAG, "예시 노래 넣기 실패", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MusicProbe"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** 제목, 가수(없음 가능), 튼 앱. 실제 곡이 아니라 화면 확인용 가짜 이름. */
        val SAMPLES = listOf(
            Triple("아침 드라이브", "동반차 밴드", "멜론"),
            Triple("휴게소 가는 길", "동반차 밴드", "멜론"),
            Triple("창밖의 바다", null, "블루투스"),
            Triple("느린 오후", "여행 합창단", "FM 라디오"),
            Triple("집으로", "동반차 밴드", "멜론"),
        )
    }
}
