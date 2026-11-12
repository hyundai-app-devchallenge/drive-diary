package com.dongbantest.app.navi

import android.util.Log
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.location.ArrivalGate
import com.dongbantest.app.domain.location.distanceMeters
import com.dongbantest.app.location.LocationTracker
import com.dongbantest.app.vehicle.VehicleRepository
import com.dongbantest.app.vehicle.VehicleSignalTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 자동 종료된 여행. 화면은 이 값을 보고 S11 도착 화면을 띄운다. */
data class ArrivalEvent(val tripId: Long, val destinationName: String)

/**
 * 목적지에 도착하면 진행 중인 여행을 자동으로 끝낸다 (PRD v3.0 S11).
 *
 * 도착 판단 ([ArrivalGate]):
 *  1. 내비(NaviHelper)가 목적지 도착 신호를 보냄
 *  2. 내비 목적지 300m 안에서 기어 P
 * 끝낸 뒤에는 [arrival] 로 화면에 알리고, 앱이 화면에 없으면 알림을 띄운다.
 * "아직 여행 중이에요"를 누르면 [keepTraveling] 으로 여행을 다시 연다.
 */
class ArrivalManager(
    private val tripRepository: TripRepository,
    private val vehicleRepository: VehicleRepository,
    private val locationTracker: LocationTracker,
    private val vehicleSignalTracker: VehicleSignalTracker,
    private val placeRecorder: TripPlaceRecorder,
    private val notifier: ArrivalNotifier,
    private val scope: CoroutineScope,
) {
    private val gate = ArrivalGate()
    private val _arrival = MutableStateFlow<ArrivalEvent?>(null)
    val arrival: StateFlow<ArrivalEvent?> = _arrival.asStateFlow()

    /** 내비에 설정된 목적지. 목적지 반경 판단에 쓴다. */
    @Volatile var destination: NaviDestination? = null

    /** 앱 화면이 보이는지 (MainActivity 가 알려준다). 안 보이면 알림을 띄운다. */
    @Volatile var isAppVisible: Boolean = false

    fun onNaviArrived(destinationName: String) = scope.launch {
        val trip = tripRepository.observeOngoingTrip().first() ?: return@launch
        if (!gate.shouldEndOnNaviArrival(trip.id)) return@launch
        finish(trip.id, destinationName.ifBlank { destination?.name ?: trip.destination })
    }

    fun onParked(tripId: Long) = scope.launch {
        val dest = destination ?: return@launch
        val last = tripRepository.lastLocation(tripId)
        val distance = last?.let { distanceMeters(it.latitude, it.longitude, dest.latitude, dest.longitude) }
        Log.i(TAG, "주차: 목적지(${dest.name})까지 ${distance?.toInt()}m")
        if (gate.shouldEndOnPark(tripId, distance) && tripRepository.isTripOngoing(tripId)) {
            finish(tripId, dest.name)
        }
    }

    /** "아직 여행 중이에요": 여행을 다시 연다. 기록은 여행 화면에 들어가면 다시 시작된다. */
    fun keepTraveling(tripId: Long) = scope.launch {
        gate.keepTraveling(tripId)
        tripRepository.reopenTrip(tripId)
        Log.i(TAG, "여행 다시 열기: tripId=$tripId")
    }

    /** 화면이 도착 이벤트를 받아 갔으면 지운다. */
    fun consume() {
        _arrival.value = null
    }

    private suspend fun finish(tripId: Long, destinationName: String) {
        Log.i(TAG, "목적지 도착 → 여행 자동 종료: tripId=$tripId, 목적지=$destinationName")
        locationTracker.stop(tripId)
        vehicleSignalTracker.stop(tripId)
        tripRepository.endTrip(tripId, vehicleRepository.state.value.odometer)
        placeRecorder.recordEnd(tripId, destinationName)
        _arrival.value = ArrivalEvent(tripId, destinationName)
        if (!isAppVisible) notifier.show(tripId, destinationName)
    }

    private companion object {
        const val TAG = "ArrivalManager"
    }
}
