package com.dongbantest.app.debug

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dongbantest.app.AppContainer
import com.dongbantest.app.domain.model.SummaryStatus
import com.drivediary.talkrec.DebugOutcome
import com.drivediary.talkrec.TalkDebugEvent
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.TalkRecDebug
import com.drivediary.talkrec.capture.CaptureStatus
import com.drivediary.talkrec.capture.SttStatus
import com.drivediary.talkrec.debug.DebugTripAudio
import java.util.Locale
import kotlin.math.log10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DebugTalkPanel(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val trip by container.tripRepository.observeOngoingTrip().collectAsStateWithLifecycle(null)
    val tripId = trip?.id
    val state = remember(tripId) { DebugTalkPanelState() }
    val capture by TalkRec.state.collectAsStateWithLifecycle()
    val isTripCapture = tripId != null && capture.tripId == tripId
    val recording = isTripCapture && capture.status == CaptureStatus.On
    var expanded by rememberSaveable { mutableStateOf(false) }
    var requestedTripId by rememberSaveable { mutableStateOf<Long?>(null) }
    var fileNotice by remember(tripId) { mutableStateOf<String?>(null) }
    var starting by remember(tripId) { mutableStateOf(false) }
    var hasConsent by remember(tripId) {
        mutableStateOf(tripId != null && container.recordingConsentStore.get(tripId) == true)
    }
    LaunchedEffect(lifecycle, tripId) {
        if (tripId == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            container.recordingConsentStore.changes().collect {
                hasConsent = container.recordingConsentStore.get(tripId) == true
            }
        }
    }
    var showFiles by remember { mutableStateOf(false) }
    var localFiles by remember { mutableStateOf<List<LabAudioFile>>(emptyList()) }
    LaunchedEffect(showFiles) {
        if (showFiles) {
            try { localFiles = withContext(Dispatchers.IO) { readLabAudioFiles(context) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localFiles = emptyList() }
        } else localFiles = emptyList()
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = requestedTripId
        requestedTripId = null
        if (uri != null && id != null) {
            fileNotice = if (DebugTripAudio.useFile(context, id, uri)) null else "여행 녹음이 켜져 있는지 확인해 주세요."
        }
    }

    LaunchedEffect(lifecycle, tripId, state) {
        if (tripId == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            TalkRecDebug.observeTrip(tripId).use {
                try {
                    TalkRecDebug.events.collect { event ->
                        if (TalkRec.state.value.tripId == tripId ||
                            event is TalkDebugEvent.AiRequest || event is TalkDebugEvent.AiResponse) {
                            state.accept(event)
                        }
                    }
                } finally { state.clear() }
            }
        }
    }

    val labRecording = !isTripCapture && capture.status in setOf(CaptureStatus.On, CaptureStatus.Paused)
    val stt = if (isTripCapture) capture.sttStatus else SttStatus.Idle
    val sttLabel = when (stt) {
        SttStatus.Idle -> "대기"
        SttStatus.Connecting -> "연결 중"
        SttStatus.Ready -> "연결됨"
        SttStatus.Reconnecting -> "다시 연결 중"
        SttStatus.Failed -> "연결 실패"
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                val toggle = if (expanded) "접기" else "펼치기"
                Text("녹음 진단 · STT $sttLabel · $toggle")
            }
            if (expanded && tripId != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        showFiles = true
                    }, enabled = recording) { Text("음성 파일 입력") }
                    DropdownMenu(expanded = showFiles, onDismissRequest = { showFiles = false }) {
                        localFiles.forEach { item ->
                            DropdownMenuItem(text = { Text(item.file.name) }, onClick = {
                                showFiles = false
                                fileNotice = if (DebugTripAudio.useFile(context, tripId, Uri.fromFile(item.file))) null
                                else "여행 녹음이 켜져 있는지 확인해 주세요."
                            })
                        }
                        DropdownMenuItem(text = { Text("기기에서 다른 파일 열기") }, onClick = {
                            showFiles = false
                            requestedTripId = tripId
                            try { pick.launch(arrayOf("audio/*", "application/ogg")) }
                            catch (_: ActivityNotFoundException) {
                                requestedTripId = null
                                fileNotice = "파일 선택기가 없어요. 실험실 폴더의 파일을 골라 주세요."
                            } catch (_: SecurityException) {
                                requestedTripId = null
                                fileNotice = "파일 선택 권한이 없어요. 실험실 폴더의 파일을 골라 주세요."
                            }
                        })
                    }
                    if (recording && capture.usingTestAudio) {
                        OutlinedButton(onClick = { DebugTripAudio.useMicrophone(tripId) }) { Text("마이크로 복귀") }
                    }
                    if (!recording && hasConsent && !labRecording) {
                        OutlinedButton(enabled = !starting, onClick = {
                            starting = true
                            fileNotice = null
                            scope.launch {
                                try {
                                    val started = container.talkRecording.resume(tripId)
                                    fileNotice = if (started) null
                                    else "동반차의 마이크 권한과 여행 녹음 설정을 확인해 주세요."
                                } finally {
                                    starting = false
                                }
                            }
                        }) { Text(if (isTripCapture && capture.status == CaptureStatus.Paused) "여행 녹음 재개" else "여행 녹음 시작") }
                    }
                    TextButton(onClick = state::clear) { Text("진단 지우기") }
                }
                if (!recording) {
                    val reason = when {
                        labRecording -> "인식 실험실 테스트를 종료한 뒤 여행 녹음을 켜 주세요."
                        !hasConsent -> "동반차 여행 화면에서 대화 녹음에 동의해야 파일을 입력할 수 있어요."
                        isTripCapture && capture.status == CaptureStatus.Paused ->
                            "여행 녹음이 멈춰 있어요. 재개하면 파일을 넣을 수 있어요."
                        else -> "여행 녹음을 시작하면 파일을 넣을 수 있어요."
                    }
                    Text(reason, style = MaterialTheme.typography.bodySmall)
                }
                val tripData by remember(container.tripRepository, tripId) {
                    container.tripRepository.observeTripWithMemories(tripId)
                }.collectAsStateWithLifecycle(null)
                val consentChanges = remember(container) { container.recordingConsentStore.changes() }
                var canSend by remember(tripId) { mutableStateOf(container.recordingConsentStore.getSendToAi(tripId)) }
                LaunchedEffect(lifecycle, tripId) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        consentChanges.collect { canSend = container.recordingConsentStore.getSendToAi(tripId) }
                    }
                }
                val talk = tripData?.memories.orEmpty().mapNotNull { it.talk }
                val input = when {
                    !recording -> "대기"
                    capture.usingTestAudio -> "음성 파일"
                    else -> "마이크"
                }
                val rms = state.rms
                val level = if (recording && rms > 0) String.format(Locale.ROOT, " · %.1f dBFS", 20 * log10(rms / 32768)) else ""
                Text("입력 $input$level · STT ON_DEVICE · 로컬 저장 ${talk.size}건", style = MaterialTheme.typography.bodySmall)
                fileNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                SelectionContainer {
                    Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val error = state.status
                        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall)
                        Text("실시간 전사", style = MaterialTheme.typography.titleSmall)
                        if (recording && state.partial.isNotBlank()) Text("인식 중 · ${state.partial}")
                        if (state.finals.isEmpty()) Text("확정 전사 대기")
                        state.finals.forEach { Text("확정 · $it") }
                        Text("저장 판정", style = MaterialTheme.typography.titleSmall)
                        if (state.decisions.isEmpty()) Text("대화 묶음 판정 대기")
                        state.decisions.forEach { Text(it) }
                        Text("외부 AI", style = MaterialTheme.typography.titleSmall)
                        val pending = talk.count { it.summaryStatus == SummaryStatus.PENDING }
                        val done = talk.count { it.summaryStatus == SummaryStatus.DONE }
                        val failed = talk.count { it.summaryStatus == SummaryStatus.FAILED }
                        Text("전송 동의 ${if (canSend) "있음" else "없음"} · 대기 $pending · 완료 $done · 실패 $failed")
                        talk.lastOrNull { it.summaryStatus == SummaryStatus.DONE && !it.title.isNullOrBlank() }?.let {
                            Text(listOf(it.title.orEmpty(), it.tags.orEmpty().joinToString(", ")).filter(String::isNotBlank).joinToString(" · "))
                        }
                        state.requests.forEach { exchange ->
                            Text("${exchange.request.provider} #${exchange.request.id} · 요청 JSON")
                            Text(exchange.request.body, style = MaterialTheme.typography.bodySmall)
                            val response = exchange.response
                            if (response == null) Text("응답 대기")
                            else {
                                Text(listOfNotNull(response.httpStatus?.let { "HTTP $it" }, response.error)
                                    .joinToString(" · "))
                                response.body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            } else if (expanded) {
                Text("진행 중인 여행이 없어요. 여행 없이 파일을 시험하려면 인식 실험실을 열어 주세요.",
                    Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private class AiExchange(val request: TalkDebugEvent.AiRequest) {
    var response by mutableStateOf<TalkDebugEvent.AiResponse?>(null)
}

private class DebugTalkPanelState {
    var partial by mutableStateOf("")
    var rms by mutableStateOf(0.0)
    var status by mutableStateOf<String?>(null)
    val finals = mutableStateListOf<String>()
    val decisions = mutableStateListOf<String>()
    val requests = mutableStateListOf<AiExchange>()

    fun accept(event: TalkDebugEvent) {
        when (event) {
            is TalkDebugEvent.Level -> rms = event.rms
            is TalkDebugEvent.Partial -> partial = event.text
            is TalkDebugEvent.Final -> {
                partial = ""
                status = null
                finals.addRecent(event.text, 8)
            }
            is TalkDebugEvent.Decision -> {
                val score = event.keepProbability?.let { " · 유지 확률 ${(it * 100).toInt()}%" }.orEmpty()
                val result = if (event.outcome == DebugOutcome.SAVED) "저장 수락" else outcomeLabel(event.outcome)
                val saved = if (event.savedCountDelta > 0 && event.outcome != DebugOutcome.SAVED) " · 저장 수락" else ""
                val reasons = event.policyReasons.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " · ").orEmpty()
                decisions.addRecent(result + saved + score + reasons, 6)
            }
            is TalkDebugEvent.AiRequest -> requests.addRecent(AiExchange(event), 3)
            is TalkDebugEvent.AiResponse -> requests.firstOrNull { it.request.id == event.id }?.response = event
            is TalkDebugEvent.SourceError -> status = "음성 파일 입력 실패 · ${event.code}"
            is TalkDebugEvent.SttError -> status = if (event.code == "stt_reconnect") "STT 재연결" else "STT 초기화 실패"
            TalkDebugEvent.VoicePaused -> status = "정지 명령 인식 · 여행 녹음 멈춤"
            TalkDebugEvent.SourceEnded, TalkDebugEvent.Stopped -> { partial = ""; rms = 0.0 }
            is TalkDebugEvent.BlockClosed, is TalkDebugEvent.RunClosed -> Unit
        }
    }

    fun clear() {
        partial = ""; rms = 0.0; status = null
        finals.clear(); decisions.clear(); requests.clear()
    }

    private fun <T> MutableList<T>.addRecent(value: T, limit: Int) {
        add(0, value)
        if (size > limit) removeAt(lastIndex)
    }
}
