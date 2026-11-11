package com.dongbantest.app.location

import android.util.Log
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.model.LocationPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 진행 중인 여행의 위치를 앱 범위(appScope)에서 계속 모아 DB 에 저장한다.
 * 화면을 옮겨 다녀도(여행 화면 ↔ 추억 기록 화면) 수집이 끊기지 않게 하려고 ViewModel 이 아닌 여기서 관리한다.
 * 백그라운드(앱이 화면에서 사라진 상태) 수집은 아직 지원하지 않는다.
 */
class LocationTracker(
    private val reader: LocationReader,
    private val repository: TripRepository,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var trackingTripId: Long? = null

    fun start(tripId: Long) {
        if (trackingTripId == tripId && job?.isActive == true) return
        if (!reader.hasPermission()) return
        stop()
        trackingTripId = tripId
        job = scope.launch {
            reader.locationUpdates()
                .catch { e -> Log.w(TAG, "위치 수집 실패", e) }
                .collect { location ->
                    repository.addLocationPoint(
                        LocationPoint(
                            tripId = tripId,
                            recordedAt = location.time,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        )
                    )
                }
        }
    }

    /** [tripId] 를 넘기면 그 여행을 기록 중일 때만 멈춘다. */
    fun stop(tripId: Long? = null) {
        if (tripId != null && tripId != trackingTripId) return
        job?.cancel()
        job = null
        trackingTripId = null
    }

    private companion object {
        const val TAG = "LocationTracker"
    }
}
