package com.dongbantest.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.data.demo.DemoSeeder
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.Trip
import com.dongbantest.app.navi.NaviMonitor
import com.dongbantest.app.navi.NaviRoute
import com.dongbantest.app.vehicle.DrivingSafetyMonitor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DiaryListUiState(
    val trips: List<Trip> = emptyList(),
    /** 기록 지도에 그릴 경로 (여행 id → 구간들) */
    val routes: Map<Long, List<List<LocationPoint>>> = emptyMap(),
    val isLoading: Boolean = true,
    val isRestricted: Boolean = false,
    /** 진행 중인 여행 (R01 목록 맨 위 진행 중 카드) */
    val ongoingTrip: Trip? = null,
    /** 진행 중 여행의 내비 길 안내 정보 (진행률) */
    val route: NaviRoute? = null,
)

class DiaryListViewModel(
    private val tripRepository: TripRepository,
    safetyMonitor: DrivingSafetyMonitor,
    naviMonitor: NaviMonitor,
    private val demoSeeder: DemoSeeder,
) : ViewModel() {

    val uiState: StateFlow<DiaryListUiState> = combine(
        tripRepository.observeTrips(),
        tripRepository.observeFinishedRoutes(),
        safetyMonitor.state,
        naviMonitor.route,
    ) { trips, routes, safety, route ->
        DiaryListUiState(
            trips = trips.filter { !it.isOngoing },
            routes = routes,
            isLoading = false,
            isRestricted = safety.isRestricted,
            ongoingTrip = trips.firstOrNull { it.isOngoing },
            route = route,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryListUiState())

    fun addSamples() = viewModelScope.launch { demoSeeder.seed() }
    fun clearSamples() = viewModelScope.launch { tripRepository.clearSampleTrips() }
}
