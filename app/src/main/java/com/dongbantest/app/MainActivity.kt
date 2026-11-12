package com.dongbantest.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import com.dongbantest.app.navi.ArrivalNotifier
import com.dongbantest.app.ui.ArrivalRequest
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import com.dongbantest.app.ui.v3connect.V3NavHost
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    private val container get() = (application as DongbanApplication).container

    /**
     * Pleos 차량 읽기 권한. 공식 문서(Vehicle > Permission)에 런타임 요청 필요 여부가 명시되어 있지 않아,
     * 미허용 상태일 때만 요청하고 결과와 무관하게 연결을 재시도한다 (실패 시 Mock 모드).
     */
    private val pleosPermissions = arrayOf(
        "pleos.car.permission.CAR_INFO",
        "pleos.car.permission.CAR_DRIVE",
        "pleos.car.permission.CAR_SEATS",
        "pleos.car.permission.CAR_DOORS",
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { container.vehicleRepository.restart() }

    /** 도착 알림을 눌러서 들어왔을 때, 여행 화면에서 종료 확인 창을 띄우기 위한 값. */
    private var arrivalRequest by mutableStateOf<ArrivalRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 확정안 v3 화면으로 앱을 띄운다. 예전 화면은 ui/DongbanNavHost.kt 에 그대로 남아 있다.
            DongbanchaV3Theme {
                Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
                    V3NavHost(
                        arrivalRequest = arrivalRequest,
                        onArrivalHandled = { arrivalRequest = null },
                    )
                }
            }
        }
        if (savedInstanceState == null) {
            arrivalRequest = intent.toArrivalRequest()
            requestMissingPermissions()
        }
        // 앱이 화면에 있을 때 여행이 자동 종료되면 바로 S11 도착 화면으로 간다.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.arrivalManager.arrival.filterNotNull().collect {
                    arrivalRequest = ArrivalRequest(it.tripId, it.destinationName)
                    container.arrivalManager.consume()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.toArrivalRequest()?.let { arrivalRequest = it }
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) addAll(pleosPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun Intent.toArrivalRequest(): ArrivalRequest? {
        val tripId = getLongExtra(ArrivalNotifier.EXTRA_TRIP_ID, -1L)
        if (tripId < 0) return null
        return ArrivalRequest(tripId, getStringExtra(ArrivalNotifier.EXTRA_DESTINATION).orEmpty())
    }

    override fun onStart() {
        super.onStart()
        container.arrivalManager.isAppVisible = true
        container.drivingSafetyMonitor.start()
        container.vehicleRepository.start()
    }

    override fun onStop() {
        container.arrivalManager.isAppVisible = false
        container.vehicleRepository.stop()
        container.drivingSafetyMonitor.stop()
        super.onStop()
    }
}
