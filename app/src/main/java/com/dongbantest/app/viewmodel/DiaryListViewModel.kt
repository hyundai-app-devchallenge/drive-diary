package com.dongbantest.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.Trip
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
)

class DiaryListViewModel(
    private val tripRepository: TripRepository,
    safetyMonitor: DrivingSafetyMonitor,
) : ViewModel() {

    val uiState: StateFlow<DiaryListUiState> = combine(
        tripRepository.observeTrips(),
        tripRepository.observeFinishedRoutes(),
        safetyMonitor.state,
    ) { trips, routes, safety ->
        DiaryListUiState(
            trips = trips.filter { !it.isOngoing },
            routes = routes,
            isLoading = false,
            isRestricted = safety.isRestricted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryListUiState())

    fun addSamples() = viewModelScope.launch { tripRepository.seedSampleTrips() }
    fun clearSamples() = viewModelScope.launch { tripRepository.clearSampleTrips() }
}
