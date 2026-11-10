package com.dongbantest.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android 기본 LocationManager(GPS)로 위치를 받는다.
 * Pleos FusedLocation SDK 가 공개되면 이 파일 안쪽만 바꾸면 된다.
 */
class LocationReader(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean = hasPermission(context)

    /** [intervalMillis] 마다 위치를 흘려보낸다. 수집을 멈추면(취소) GPS 요청도 해제된다. */
    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMillis: Long = 3_000L): Flow<Location> = callbackFlow {
        val listener = LocationListener { location -> trySend(location) }
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
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
