package com.dongbantest.app.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import com.dongbantest.app.domain.model.VehicleSignalType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 정차 판단에 쓰는 차량 신호를 받는다. 같은 값이 반복될 수 있어서, 중복은 [VehicleSignalTracker]가 거른다.
 *
 * - 기어: AAOS 기본 GEAR_SELECTION (Pleos Gear API 는 deprecated 이고, Google 속성을 쓰라고 안내함)
 * - 운전석 착석·안전벨트: Pleos Seat API (pleos.car.permission.CAR_SEATS, normal)
 * - 문 열림: Pleos Door API (pleos.car.permission.CAR_DOORS, normal). 1·2열 문 중 하나라도 열리면 열림.
 *
 * 운전석은 왼쪽(ROW_1_LEFT)으로 본다.
 * 일반 폰 등 Automotive 가 아닌 기기에서는 아무 신호도 내보내지 않는다.
 */
class VehicleSignalReader(context: Context) {

    private val appContext = context.applicationContext

    fun signals(): Flow<VehicleSignalType> = callbackFlow {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            awaitClose { }
            return@callbackFlow
        }

        // 기어 (AAOS)
        val gear = runCatching { GearBridge(appContext) { parked -> trySend(gearSignal(parked)) } }
            .onFailure { Log.w(TAG, "기어 신호 사용 불가", it) }
            .getOrNull()

        // 운전석·안전벨트·문 (Pleos)
        val vehicle = runCatching { Vehicle(appContext).also { it.initialize() } }
            .onFailure { Log.w(TAG, "Pleos Vehicle 초기화 실패", it) }
            .getOrNull()

        val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
        val seatListener = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                if (occupancy != null) {
                    trySend(if (occupancy) VehicleSignalType.DRIVER_SEAT_OCCUPIED else VehicleSignalType.DRIVER_SEAT_EMPTY)
                }
            }
            override fun onFailed(e: Exception) {
                Log.w(TAG, "운전석 착석 신호 실패", e)
            }
        }
        val beltListener = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(state: Boolean?) {
                if (state != null) {
                    trySend(if (state) VehicleSignalType.DRIVER_BELT_ON else VehicleSignalType.DRIVER_BELT_OFF)
                }
            }
            override fun onFailed(e: Exception) {
                Log.w(TAG, "운전석 안전벨트 신호 실패", e)
            }
        }

        // 문은 여러 개라서, 문마다 열림 상태를 기억해 두고 하나라도 열려 있으면 "열림"으로 본다.
        val openDoors = mutableSetOf<DoorValue>()
        val doorListeners = DOORS.associateWith { door ->
            object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) {
                    if (pos == null) return
                    synchronized(openDoors) {
                        if (pos > 0) openDoors += door else openDoors -= door
                        trySend(if (openDoors.isEmpty()) VehicleSignalType.DOOR_CLOSED else VehicleSignalType.DOOR_OPEN)
                    }
                }
                override fun onFailed(e: Exception) {
                    Log.w(TAG, "문 신호 실패: $door", e)
                }
            }
        }

        vehicle?.let { v ->
            runCatching { v.getSeat().registerSeatOccupancy(driverSeat, seatListener) }
                .onFailure { Log.w(TAG, "운전석 착석 등록 실패", it) }
            runCatching { v.getSeat().registerSeatBeltBuckledState(driverSeat, beltListener) }
                .onFailure { Log.w(TAG, "운전석 안전벨트 등록 실패", it) }
            doorListeners.forEach { (door, listener) ->
                runCatching { v.getDoor().registerDoorPosition(DoorArea(door), listener) }
                    .onFailure { Log.w(TAG, "문 등록 실패: $door", it) }
            }
        }

        awaitClose {
            gear?.close()
            vehicle?.let { v ->
                runCatching { v.getSeat().unregisterSeatOccupancy(driverSeat, seatListener) }
                runCatching { v.getSeat().unregisterSeatBeltBuckledState(driverSeat, beltListener) }
                doorListeners.forEach { (door, listener) ->
                    runCatching { v.getDoor().unregisterDoorPosition(DoorArea(door), listener) }
                }
                runCatching { v.release() }
            }
        }
    }

    private fun gearSignal(parked: Boolean) =
        if (parked) VehicleSignalType.PARKED else VehicleSignalType.GEAR_NOT_PARKED

    /** android.car 클래스는 Automotive 기기에서만 로드되도록 따로 감싼다. */
    private class GearBridge(context: Context, private val onGear: (parked: Boolean) -> Unit) {
        private val car = Car.createCar(context)
        private val manager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        private val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val gear = value.value as? Int ?: return
                onGear(gear == VehicleGear.GEAR_PARK)
            }
            override fun onErrorEvent(propertyId: Int, zone: Int) {
                Log.w(TAG, "기어 신호 오류: property=$propertyId")
            }
        }

        init {
            val ok = manager.registerCallback(
                callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE,
            )
            if (!ok) Log.w(TAG, "기어 신호 등록 실패")
        }

        fun close() {
            runCatching { manager.unregisterCallback(callback) }
            runCatching { car.disconnect() }
        }
    }

    private companion object {
        const val TAG = "VehicleSignal"
        val DOORS = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    }
}
