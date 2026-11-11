package com.dongbantest.app.navi

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pleos NaviHelper 연결.
 *
 * 내비가 보내는 값을 로그로 남기고, 목적지에 도착하면 [onArrived]를 부른다.
 * 확인 명령: adb logcat -s NaviMonitor
 */
/** 내비에 설정된 목적지 */
data class NaviDestination(val name: String, val latitude: Double, val longitude: Double)

class NaviMonitor(context: Context) {

    private val naviHelper = NaviHelper(context.applicationContext)
    private var started = false
    private val addressWaiters = mutableListOf<CompletableDeferred<String>>()

    /** 목적지에 도착했을 때 부를 함수. 인자는 목적지 이름이다. */
    var onArrived: ((String) -> Unit)? = null

    /** 내비에 설정된 목적지가 바뀌면 부른다. 목적지가 없으면 null. */
    var onDestination: ((NaviDestination?) -> Unit)? = null

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            Log.i(TAG, "현재 위치: $currentLocationInfo")
            val waiting = synchronized(addressWaiters) { addressWaiters.toList().also { addressWaiters.clear() } }
            waiting.forEach { it.complete(currentLocationInfo.address) }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.i(TAG, "목적지: $destinationInfo")
            val hasDestination = destinationInfo.latitude != 0.0 || destinationInfo.longitude != 0.0
            onDestination?.invoke(
                if (hasDestination) NaviDestination(destinationInfo.name, destinationInfo.latitude, destinationInfo.longitude)
                else null
            )
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            Log.i(TAG, "경로 상태: $routeStateInfo")
        }

        override fun onDestinationChanged() {
            Log.i(TAG, "목적지가 바뀜 → 목적지 다시 조회")
            naviHelper.getDestinationInfo()
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "길 안내 시작: $routeStartInfo")
            runCatching { naviHelper.getDestinationInfo() }
        }

        override fun onRouteEnded() {
            Log.i(TAG, "길 안내 끝남")
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "길 안내 취소")
            onDestination?.invoke(null)
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "목적지 도착: $destinationArrivedInfo")
            onArrived?.invoke(destinationArrivedInfo.name)
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            Log.i(TAG, "내비 상태: $status")
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "내비 오류: $errorCode")
        }
    }

    /** 앱이 켜질 때 한 번 연결한다. 이미 연결했으면 아무것도 하지 않는다. */
    fun start() {
        if (started) return
        started = true
        naviHelper.addListener(listener)
        naviHelper.initialize(
            onConnected = {
                Log.i(TAG, "NaviHelper 연결됨 → 현재 위치·목적지·경로 상태 조회")
                requestSnapshot()
            },
            onFailed = { e ->
                Log.w(TAG, "NaviHelper 연결 실패", e)
                started = false
            },
        )
    }

    /** 지금 상태를 한 번 물어본다. 답은 위 listener로 온다. */
    fun requestSnapshot() {
        runCatching {
            naviHelper.getCurrentLocationInfo()
            naviHelper.getDestinationInfo()
            naviHelper.getRouteStateInfo()
        }.onFailure { Log.w(TAG, "내비 정보 조회 실패", it) }
    }

    /**
     * 내비가 아는 지금 위치의 주소 (예: "수정구 시흥동"). 출발지·도착지 이름에 쓴다.
     * 내비가 연결되지 않았거나 [timeoutMillis] 안에 답이 없으면 null.
     */
    suspend fun currentAddress(timeoutMillis: Long = 3_000L): String? {
        if (!started) return null
        val waiter = CompletableDeferred<String>()
        synchronized(addressWaiters) { addressWaiters += waiter }
        runCatching { naviHelper.getCurrentLocationInfo() }.onFailure {
            Log.w(TAG, "현재 위치 조회 실패", it)
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { waiter.await() }?.takeIf { it.isNotBlank() }
        } finally {
            synchronized(addressWaiters) { addressWaiters -= waiter }
        }
    }

    /** 에뮬레이터는 내비 위치가 움직이지 않아 실제 도착을 만들 수 없다. 디버그 빌드 테스트용. */
    fun simulateArrival(destinationName: String) {
        Log.i(TAG, "도착 흉내: $destinationName")
        onArrived?.invoke(destinationName)
    }

    fun stop() {
        if (!started) return
        started = false
        naviHelper.removeListener(listener)
        naviHelper.release()
    }

    private companion object {
        const val TAG = "NaviMonitor"
    }
}
