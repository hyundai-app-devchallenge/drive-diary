package com.dongbantest.app.navi

import android.util.Log
import com.dongbantest.app.data.repository.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 여행의 출발지·도착지 이름을 저장한다 (PRD S14 소요 시간: 출발지·출발 시각, 도착지·도착 시각).
 * 이름은 내비(NaviHelper)가 알려주는 지금 위치의 주소를 쓴다. 내비가 답하지 않으면 비워 둔다.
 */
class TripPlaceRecorder(
    private val naviMonitor: NaviMonitor,
    private val tripRepository: TripRepository,
    private val scope: CoroutineScope,
) {
    /** 출발지가 아직 없으면 지금 위치 주소로 채운다. 여행 화면에 들어올 때마다 불러도 된다. */
    fun recordStart(tripId: Long) = scope.launch {
        if (tripRepository.startPlaceOf(tripId) != null) return@launch
        val address = naviMonitor.currentAddress() ?: return@launch
        tripRepository.setStartPlace(tripId, address)
        Log.i(TAG, "출발지: $address (tripId=$tripId)")
    }

    /**
     * 도착지를 저장한다. [knownName] 이 있으면(내비 목적지) 그 이름을, 없으면 지금 위치 주소를 쓴다.
     * 내비 답을 기다리는 동안 화면이 멈추지 않도록 앱 범위에서 따로 실행한다.
     */
    fun recordEnd(tripId: Long, knownName: String? = null) = scope.launch {
        val place = knownName?.takeIf { it.isNotBlank() } ?: naviMonitor.currentAddress() ?: return@launch
        tripRepository.setEndPlace(tripId, place)
        Log.i(TAG, "도착지: $place (tripId=$tripId)")
    }

    private companion object {
        const val TAG = "TripPlace"
    }
}
