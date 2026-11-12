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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pleos NaviHelper 연결.
 *
 * 내비가 보내는 값을 로그로 남기고, 목적지에 도착하면 [onArrived]를 부른다.
 * 확인 명령: adb logcat -s NaviMonitor
 */
/** 내비에 설정된 목적지 */
data class NaviDestination(val name: String, val latitude: Double, val longitude: Double)

/** 길 안내 중인 경로의 지금 상태 (길 리본용). 거리는 미터, 시간은 초. */
data class NaviRoute(
    val destinationName: String,
    /** 길 안내를 시작할 때의 전체 거리. 모르면 처음 받은 남은 거리를 쓴다. */
    val totalMeters: Int,
    val remainMeters: Int,
    val remainSeconds: Int,
)

class NaviMonitor(context: Context) {

    private val naviHelper = NaviHelper(context.applicationContext)
    private var started = false
    private val addressWaiters = mutableListOf<CompletableDeferred<String>>()

    private val _route = MutableStateFlow<NaviRoute?>(null)
    /** 길 안내 중이면 남은 거리·시간, 아니면 null. [refreshRoute] 로 새로 받는다. */
    val route: StateFlow<NaviRoute?> = _route.asStateFlow()
    /** 길 안내를 시작할 때 받은 전체 거리 (목적지 이름 → 미터) */
    @Volatile private var routeStartTotal: Pair<String, Int>? = null

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
            updateRoute(if (hasDestination) destinationInfo else null)
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
            routeStartTotal = routeStartInfo.name to routeStartInfo.distance
            runCatching { naviHelper.getDestinationInfo() }
        }

        override fun onRouteEnded() {
            Log.i(TAG, "길 안내 끝남")
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "길 안내 취소")
            onDestination?.invoke(null)
            _route.value = null
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "목적지 도착: $destinationArrivedInfo")
            _route.value = null
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

    /** 남은 거리·시간을 새로 물어본다. 답은 [route] 로 온다. */
    fun refreshRoute() {
        if (!started) return
        runCatching { naviHelper.getDestinationInfo() }.onFailure { Log.w(TAG, "목적지 조회 실패", it) }
    }

    private fun updateRoute(info: DestinationInfo?) {
        if (info == null) {
            naviRoute = null
            _route.value = null
            return
        }
        val previous = _route.value?.takeIf { it.destinationName == info.name }
        val startTotal = routeStartTotal?.takeIf { it.first == info.name }?.second
        // 전체 거리: 길 안내 시작 때 받은 값 > 이전에 정한 값 > 지금 남은 거리 (앱을 중간에 켠 경우)
        val total = maxOf(startTotal ?: previous?.totalMeters ?: info.remainDistance, info.remainDistance)
        naviRoute = NaviRoute(info.name, total, info.remainDistance, info.remainTime)
        _route.value = withSimulatedProgress(naviRoute)
    }

    /** 내비가 실제로 보낸 마지막 경로. 시연용 진행률을 지울 때 이 값으로 돌아간다. */
    @Volatile private var naviRoute: NaviRoute? = null

    /** 시연용 진행률(0~100). null 이면 내비 값을 그대로 쓴다. */
    @Volatile private var simulatedPercent: Int? = null

    /**
     * 에뮬레이터는 내비 위치가 움직이지 않아 길 리본이 0%에 멈춰 있다. 디버그 빌드 시연용으로
     * 진행률을 [percent] 로 정한다. null 이면 시연을 끄고 내비 값으로 돌아간다.
     * 15초마다 내비 값을 다시 받아도 이 진행률이 유지된다.
     */
    fun simulateProgress(percent: Int?) {
        simulatedPercent = percent?.coerceIn(0, 100)
        Log.i(TAG, "진행률 흉내: ${simulatedPercent ?: "끔"}")
        _route.value = withSimulatedProgress(naviRoute)
    }

    /** 시연용 진행률이 있으면 남은 거리·시간을 그만큼 줄인다. */
    private fun withSimulatedProgress(route: NaviRoute?): NaviRoute? {
        val percent = simulatedPercent ?: return route
        if (route == null) return null
        val left = 100 - percent
        return route.copy(
            remainMeters = route.totalMeters * left / 100,
            remainSeconds = route.remainSeconds * left / 100,
        )
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
