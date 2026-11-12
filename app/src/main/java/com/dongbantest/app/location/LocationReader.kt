package com.dongbantest.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll

/** 위치 한 번. 어느 소스(Pleos FusedLocation / Android GPS)에서 왔든 같은 모양으로 쓴다. */
data class LocationSample(
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    /** 오차(미터). 모르면 null. */
    val accuracyMeters: Float?,
)

/**
 * 차량 위치를 받는다.
 *
 * 1순위: Pleos FusedLocation SDK (pleos.car.permission.FUSED_LOCATION, dangerous)
 *        차량 GPS 등을 합친 위치. 서비스가 있고 권한이 허용됐을 때만 쓴다.
 * 2순위: Android 기본 LocationManager GPS (ACCESS_FINE_LOCATION)
 *        FusedLocation 을 쓸 수 없거나, 쓰다가 오류가 나면 여기로 바꾼다.
 */
class LocationReader(private val context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean = hasPermission(appContext)

    /** 위치를 흘려보낸다. 수집을 멈추면(취소) 위치 요청도 해제된다. */
    fun locationUpdates(intervalMillis: Long = 3_000L): Flow<LocationSample> =
        if (canUseFused()) {
            fusedUpdates(intervalMillis).catch { e ->
                Log.w(TAG, "FusedLocation 오류 → GPS 로 전환", e)
                if (hasGpsPermission(appContext)) emitAll(gpsUpdates(intervalMillis))
            }
        } else {
            gpsUpdates(intervalMillis)
        }

    private fun canUseFused(): Boolean =
        hasFusedPermission(appContext) &&
            runCatching { FusedLocation(appContext).isSupported() }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun fusedUpdates(intervalMillis: Long): Flow<LocationSample> = callbackFlow {
        Log.i(TAG, "위치 소스: Pleos FusedLocation")
        val fused = FusedLocation(appContext)
        fused.initialize()
        var lastSent = 0L
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                location ?: return
                // FusedLocation 은 1초마다 오므로 GPS 와 같은 간격으로 줄인다.
                if (location.timestampMillis - lastSent < intervalMillis) return
                lastSent = location.timestampMillis
                trySend(
                    LocationSample(
                        time = location.timestampMillis,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        // confidence 를 오차(미터)로 본다. 에뮬레이터에서 GPS 오차(hAcc)와 같은 값이 왔다.
                        accuracyMeters = location.confidence,
                    )
                )
            }

            override fun onError(exception: Exception) {
                close(exception)
            }
        }
        fused.registerFusedLocationCallback(listener)
        awaitClose {
            runCatching { fused.unregisterFusedLocationCallback(listener) }
            runCatching { fused.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun gpsUpdates(intervalMillis: Long): Flow<LocationSample> = callbackFlow {
        Log.i(TAG, "위치 소스: Android GPS")
        val listener = LocationListener { location ->
            trySend(
                LocationSample(
                    time = location.time,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                )
            )
        }
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMillis,
            0f,
            listener,
            Looper.getMainLooper(),
        )
        awaitClose { manager.removeUpdates(listener) }
    }

    companion object {
        private const val TAG = "LocationReader"
        private const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"

        val PERMISSIONS = arrayOf(
            FUSED_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** 아직 허용되지 않은 위치 권한. 이미 거절한 권한은 시스템이 다시 묻지 않는다. */
        fun missingPermissions(context: Context): Array<String> =
            PERMISSIONS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

        /** Pleos 위치나 Android GPS 중 하나라도 허용되면 기록할 수 있다. */
        fun hasPermission(context: Context): Boolean =
            hasFusedPermission(context) || hasGpsPermission(context)

        private fun hasFusedPermission(context: Context) =
            context.checkSelfPermission(FUSED_LOCATION) == PackageManager.PERMISSION_GRANTED

        private fun hasGpsPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
