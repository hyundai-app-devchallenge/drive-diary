package com.dongbantest.app.vehicle

import android.util.Log
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.model.VehicleSignal
import com.dongbantest.app.domain.model.VehicleSignalType
import com.dongbantest.app.domain.model.VehicleSignalType.DOOR_CLOSED
import com.dongbantest.app.domain.model.VehicleSignalType.DOOR_OPEN
import com.dongbantest.app.domain.model.VehicleSignalType.DRIVER_BELT_OFF
import com.dongbantest.app.domain.model.VehicleSignalType.DRIVER_BELT_ON
import com.dongbantest.app.domain.model.VehicleSignalType.DRIVER_SEAT_EMPTY
import com.dongbantest.app.domain.model.VehicleSignalType.DRIVER_SEAT_OCCUPIED
import com.dongbantest.app.domain.model.VehicleSignalType.GEAR_NOT_PARKED
import com.dongbantest.app.domain.model.VehicleSignalType.PARKED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 진행 중인 여행의 차량 신호를 DB 에 저장한다. [com.dongbantest.app.location.LocationTracker] 와 같은 방식.
 * 같은 종류(기어·운전석·안전벨트·문)에서 값이 실제로 바뀐 경우만 저장한다.
 */
class VehicleSignalTracker(
    private val reader: VehicleSignalReader,
    private val repository: TripRepository,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var trackingTripId: Long? = null

    /** 기어가 P 로 바뀌어 저장한 뒤 부른다 (도착 판단용). 인자는 여행 id. */
    var onParked: ((Long) -> Unit)? = null

    fun start(tripId: Long) {
        if (trackingTripId == tripId && job?.isActive == true) return
        stop()
        trackingTripId = tripId
        val last = mutableMapOf<String, VehicleSignalType>()
        job = scope.launch {
            reader.signals()
                .catch { e -> Log.w(TAG, "차량 신호 수집 실패", e) }
                .collect { type ->
                    val group = groupOf(type)
                    if (last[group] == type) return@collect
                    last[group] = type
                    Log.i(TAG, "차량 신호: $type")
                    repository.addVehicleSignal(VehicleSignal(tripId, System.currentTimeMillis(), type))
                    if (type == PARKED) onParked?.invoke(tripId)
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

    private fun groupOf(type: VehicleSignalType): String = when (type) {
        PARKED, GEAR_NOT_PARKED -> "gear"
        DRIVER_SEAT_EMPTY, DRIVER_SEAT_OCCUPIED -> "seat"
        DRIVER_BELT_OFF, DRIVER_BELT_ON -> "belt"
        DOOR_OPEN, DOOR_CLOSED -> "door"
    }

    private companion object {
        const val TAG = "VehicleSignal"
    }
}
