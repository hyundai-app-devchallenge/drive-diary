package com.dongbantest.app

import android.app.Application
import android.content.Context
import com.dongbantest.app.data.local.DongbanDatabase
import com.dongbantest.app.data.repository.MediaFileStore
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.location.LocationReader
import com.dongbantest.app.location.LocationTracker
import com.dongbantest.app.navi.ArrivalManager
import com.dongbantest.app.navi.ArrivalNotifier
import com.dongbantest.app.navi.NaviMonitor
import com.dongbantest.app.navi.TripPlaceRecorder
import com.dongbantest.app.vehicle.DrivingSafetyMonitor
import com.dongbantest.app.vehicle.VehicleSignalReader
import com.dongbantest.app.vehicle.VehicleSignalTracker
import com.dongbantest.app.vehicle.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val tripPlaceRecorder by lazy { TripPlaceRecorder(naviMonitor, tripRepository, appScope) }
    val arrivalManager by lazy {
        ArrivalManager(
            tripRepository, vehicleRepository, locationTracker, vehicleSignalTracker,
            tripPlaceRecorder, ArrivalNotifier(context), appScope,
        )
    }

    /** 내비 연결 + 도착 자동 종료 연결: 내비 도착 신호, 내비 목적지, 주차 신호를 ArrivalManager 로 보낸다. */
    fun startNavi() {
        naviMonitor.onArrived = { arrivalManager.onNaviArrived(it) }
        naviMonitor.onDestination = { arrivalManager.destination = it }
        vehicleSignalTracker.onParked = { arrivalManager.onParked(it) }
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
