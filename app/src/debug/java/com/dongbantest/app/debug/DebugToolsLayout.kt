package com.dongbantest.app.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dongbantest.app.AppContainer
import com.dongbantest.app.gleo.GleoRecordingAction
import com.dongbantest.app.gleo.performGleoRecordingAction
import com.dongbantest.app.vehicle.DebugGear
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.capture.CaptureStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The controls stay above the scrolling content on every debug screen. */
@Composable
internal fun DebugToolsLayout(container: AppContainer, content: @Composable (restricted: Boolean) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, container) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The main activity disconnects its monitor when it moves to the background.
            while (true) {
                container.drivingSafetyMonitor.start()
                delay(2_000)
            }
        }
    }
    val driving by container.drivingSafetyMonitor.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        DebugControls(container, driving.isRestricted)
        Box(Modifier.weight(1f)) { content(driving.isRestricted) }
    }
}

@Composable
private fun DebugControls(container: AppContainer, restricted: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trip by container.tripRepository.observeOngoingTrip().collectAsStateWithLifecycle(null)
    val capture by TalkRec.state.collectAsStateWithLifecycle()
    val captureLabel = when {
        trip == null -> "진행 중인 여행 없음"
        capture.tripId != trip?.id -> "여행 녹음 대기"
        capture.status == CaptureStatus.On -> "여행 녹음 중"
        capture.status == CaptureStatus.Paused -> "여행 녹음 멈춤"
        else -> "여행 녹음 대기"
    }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    fun pressVoiceButton() {
        if (busy) return
        busy = true
        feedback = null
        scope.launch {
            try {
                feedback = pressGleoVoiceButton(context)
            } finally {
                busy = false
            }
        }
    }
    fun simulate(action: GleoRecordingAction) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = performGleoRecordingAction(container, action)
                feedback = if (result.success) "Gleo Action 처리 완료 · ‘${action.example}’" else result.message
            } finally {
                busy = false
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val vehicleLabel = if (restricted) "주행 중" else "정차 중"
            Text("$vehicleLabel · $captureLabel", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { DebugGear.D.apply(container) }) { Text("주행 D") }
                OutlinedButton(onClick = { DebugGear.P.apply(container) }) { Text("정차 P") }
                OutlinedButton(onClick = { DebugGear.OFF.apply(container) }) { Text("시뮬레이션 해제") }
                OutlinedButton(onClick = ::pressVoiceButton, enabled = !busy) { Text("Gleo 차량 음성 버튼") }
                OutlinedButton(onClick = { simulate(GleoRecordingAction.PAUSE) }, enabled = !busy) { Text("Gleo 멈춤 모의 호출") }
                OutlinedButton(onClick = { simulate(GleoRecordingAction.RESUME) }, enabled = !busy) { Text("Gleo 재개 모의 호출") }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
