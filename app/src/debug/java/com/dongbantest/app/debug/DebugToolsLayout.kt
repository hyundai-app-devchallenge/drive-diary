package com.dongbantest.app.debug

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.dongbantest.app.R
import com.dongbantest.app.gleo.GleoRecordingAction
import com.dongbantest.app.gleo.performGleoRecordingAction
import com.dongbantest.app.vehicle.DebugGear
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.capture.CaptureStatus
import com.drivediary.talkrec.debug.DebugTripAudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
        DebugTalkPanel(container)
    }
}

@Composable
private fun DebugControls(container: AppContainer, restricted: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trip by container.tripRepository.observeOngoingTrip().collectAsStateWithLifecycle(null)
    val capture by TalkRec.state.collectAsStateWithLifecycle()
    val recording = trip != null && capture.tripId == trip?.id && capture.status == CaptureStatus.On
    val captureLabel = when {
        trip == null -> "진행 중인 여행 없음"
        capture.tripId != trip?.id -> "여행 녹음 대기"
        capture.status == CaptureStatus.On -> "여행 녹음 중"
        capture.status == CaptureStatus.Paused -> "여행 녹음 멈춤"
        else -> "여행 녹음 대기"
    }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var testingStop by remember { mutableStateOf(false) }
    var openingGleo by remember { mutableStateOf(false) }
    val openGleo = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        openingGleo = false
        feedback = when (result.resultCode) {
            DebugGleoVoiceActivity.UNSUPPORTED -> "이 기기에서 앱의 Gleo 음성 호출을 지원하지 않아요."
            DebugGleoVoiceActivity.DENIED -> "Android가 Gleo 음성 세션 요청을 거부했어요."
            DebugGleoVoiceActivity.NO_SESSION -> "Gleo가 듣기 세션을 열지 못했어요. 버튼을 다시 눌러 주세요."
            else -> null
        }
    }
    fun pressVoiceButton() {
        if (busy || openingGleo) return
        if (capture.status == CaptureStatus.On && !capture.usingTestAudio) {
            feedback = "녹음 중에는 ‘녹음 정지’라고 말하거나 정지 음성 테스트를 사용하세요."
            return
        }
        openingGleo = true
        feedback = "Gleo 듣기 세션을 여는 중…"
        openGleo.launch(Intent(context, DebugGleoVoiceActivity::class.java))
    }
    fun testStopSpeech() {
        val tripId = trip?.id ?: return
        if (testingStop || container.recordingConsentStore.get(tripId) != true) return
        testingStop = true
        feedback = "‘녹음 정지’ 음성을 STT로 보내는 중…"
        scope.launch {
            try {
                val wav = Uri.parse("android.resource://${context.packageName}/${R.raw.debug_recording_stop}")
                val result = DebugTripAudio.testCommand(context, tripId, wav)
                feedback = when {
                    result == null -> "여행 녹음을 켠 뒤 다시 테스트해 주세요."
                    withTimeoutOrNull(35_000) { result.await() } == true ->
                        "STT가 ‘녹음 정지’ 명령을 인식해 여행 녹음을 멈췄어요."
                    else -> "정지 명령을 확인하지 못했어요. 하단 녹음 진단의 전사를 확인해 주세요."
                }
            } finally {
                testingStop = false
            }
        }
    }
    fun simulateResume() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val action = GleoRecordingAction.RESUME
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
                OutlinedButton(onClick = ::pressVoiceButton, enabled = !busy && !openingGleo) { Text("Gleo 차량 음성 버튼") }
                OutlinedButton(onClick = ::testStopSpeech, enabled = recording && !testingStop && !busy) {
                    Text("정지 음성 STT 테스트")
                }
                OutlinedButton(onClick = ::simulateResume, enabled = !busy) { Text("Gleo 재개 모의 호출") }
            }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
