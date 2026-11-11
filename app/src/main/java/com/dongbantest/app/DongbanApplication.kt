package com.dongbantest.app

import android.app.Application
import android.content.Context
import com.dongbantest.app.data.local.DongbanDatabase
import com.dongbantest.app.data.repository.MediaFileStore
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.location.LocationReader
import com.dongbantest.app.location.LocationTracker
import com.dongbantest.app.navi.ArrivalNotifier
import com.dongbantest.app.navi.NaviMonitor
import com.dongbantest.app.vehicle.DrivingSafetyMonitor
import com.dongbantest.app.vehicle.VehicleSignalReader
import com.dongbantest.app.vehicle.VehicleSignalTracker
import com.dongbantest.app.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 간단한 수동 DI 컨테이너. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database by lazy { DongbanDatabase.create(context) }
    val mediaFileStore by lazy { MediaFileStore(context) }
    val tripRepository by lazy { TripRepository(database.tripDao(), mediaFileStore) }
    val vehicleRepository by lazy { VehicleRepository(context, appScope) }
    val drivingSafetyMonitor by lazy { DrivingSafetyMonitor(context) }
    val locationTracker by lazy { LocationTracker(LocationReader(context), tripRepository, appScope) }
    val vehicleSignalTracker by lazy { VehicleSignalTracker(VehicleSignalReader(context), tripRepository, appScope) }
    val naviMonitor by lazy { NaviMonitor(context) }
    private val arrivalNotifier by lazy { ArrivalNotifier(context) }

    /** 내비에서 목적지 도착 신호가 오면, 진행 중인 여행이 있을 때만 알림을 띄운다. */
    fun startNavi() {
        naviMonitor.onArrived = { destinationName ->
            appScope.launch {
                val trip = tripRepository.observeOngoingTrip().first() ?: return@launch
                arrivalNotifier.show(trip.id, destinationName)
            }
        }
        naviMonitor.start()
    }
}

class DongbanApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startNavi()
    }
}
