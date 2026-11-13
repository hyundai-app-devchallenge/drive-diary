package com.dongbantest.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.data.talk.TalkRecordingController
import com.dongbantest.app.domain.model.Trip
import com.dongbantest.app.navi.NaviMonitor
import com.dongbantest.app.navi.NaviRoute
import com.dongbantest.app.vehicle.DrivingSafetyMonitor
import com.dongbantest.app.vehicle.DrivingSafetyState
import com.dongbantest.app.vehicle.VehicleRepository
import com.dongbantest.app.vehicle.VehicleState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val ongoingTrip: Trip? = null,
    val vehicle: VehicleState? = null,
    val safety: DrivingSafetyState? = null,
    /** 끝난 여행 (최근 순) */
    val pastTrips: List<Trip> = emptyList(),
    /** 내비 길 안내 정보 (홈 주행 중 진행률에 쓴다) */
    val route: NaviRoute? = null,
)

class HomeViewModel(
    private val tripRepository: TripRepository,
    private val vehicleRepository: VehicleRepository,
    private val safetyMonitor: DrivingSafetyMonitor,
    private val naviMonitor: NaviMonitor,
    private val talkRecording: TalkRecordingController,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        tripRepository.observeOngoingTrip(),
        vehicleRepository.state,
        safetyMonitor.state,
        tripRepository.observeTrips(),
        naviMonitor.route,
    ) { trip, vehicle, safety, trips, route ->
        HomeUiState(
            ongoingTrip = trip,
            vehicle = vehicle,
            safety = safety,
            pastTrips = trips.filter { !it.isOngoing }.sortedByDescending { it.startedAt },
            route = route,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** 홈이 보일 때 내비에 남은 거리를 한 번 물어본다. */
    fun refreshRoute() = naviMonitor.refreshRoute()

    fun startTrip(title: String, destination: String, companions: List<String>, onStarted: (Long) -> Unit) {
        viewModelScope.launch {
            val vehicle = vehicleRepository.state.value
            val id = tripRepository.startTrip(
                title = title.ifBlank { "우리의 여행" },
                destination = destination.ifBlank { "어딘가 멋진 곳" },
                companions = companions,
                odometer = vehicle.odometer,
                odometerSource = vehicle.odometer?.let { vehicle.source },
            )
            onStarted(id)
            // S03's callback persists consent; permissions may be granted on the trip screen later.
            talkRecording.startForTrip(id)
        }
    }

    fun setMockDriving(driving: Boolean) {
        safetyMonitor.setMockDriving(driving)
        if (driving) vehicleRepository.addMockDistance(3.2f)
    }

    fun retryVehicleConnection() = vehicleRepository.restart()
}
