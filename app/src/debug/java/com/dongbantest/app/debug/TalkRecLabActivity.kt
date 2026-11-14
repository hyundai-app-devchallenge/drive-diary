package com.dongbantest.app.debug

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import com.drivediary.talkrec.DebugOutcome
import com.drivediary.talkrec.TalkDebugEvent
import com.drivediary.talkrec.block.CaptureSource
import com.drivediary.talkrec.block.CloseReason
import com.drivediary.talkrec.capture.CaptureState
import com.drivediary.talkrec.debug.LabBusyException
import com.drivediary.talkrec.debug.TalkRecLab
import com.drivediary.talkrec.summary.ConnectionTestResult
import com.drivediary.talkrec.summary.SummaryProviderType
import com.drivediary.talkrec.summary.SummarySettings
import com.drivediary.talkrec.summary.StorySplitResult
import java.util.Locale
import kotlin.math.log10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug only. All transcripts, document grants and summaries live only in this Activity. */
class TalkRecLabActivity : ComponentActivity() {
    private val ui = LabScreenState()
    private val container get() = (applicationContext as DongbanApplication).container
    private var pendingStart: LabStartRequest? = null
    private var documentJob: Job? = null
    private var filesJob: Job? = null

    private val audioMimeTypes = arrayOf("audio/*", "application/ogg")

    private fun pickerAvailable(): Boolean = ActivityResultContracts.OpenDocument()
        .createIntent(this, audioMimeTypes).resolveActivity(packageManager) != null

    private fun pickDocument() {
        ui.pickerAvailable = pickerAvailable()
        if (!ui.pickerAvailable) return
        try {
            document.launch(audioMimeTypes)
        } catch (_: ActivityNotFoundException) {
            ui.pickerAvailable = false
        } catch (_: SecurityException) {
            ui.banner = "다른 파일을 열 수 없어요. 실험실 폴더의 파일을 골라 주세요."
        }
    }

    private fun refreshFiles() {
        if (filesJob?.isActive == true) return
        ui.filesLoading = true
        ui.filesError = false
        ui.pickerAvailable = pickerAvailable()
        filesJob = lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) { readLabAudioFiles(this@TalkRecLabActivity) }
                ui.files = files
                if (ui.fileUri?.scheme == "file" && files.none { Uri.fromFile(it.file) == ui.fileUri }) {
                    ui.fileUri = null
                    ui.fileName = ""
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ui.filesError = true }
            finally { ui.filesLoading = false }
        }
    }

    private fun selectFile(file: LabAudioFile) {
        documentJob?.cancel()
        ui.fileUri = Uri.fromFile(file.file)
        ui.fileName = file.file.name
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingStart
        pendingStart = null
        ui.permissionPending = false
        if (granted && request != null) start(request)
        else if (!granted) ui.banner = "마이크 권한이 필요해요. 권한을 거부했다면 앱 설정에서 허용해 주세요."
    }
    private val document = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            documentJob?.cancel()
            ui.fileUri = uri
            ui.fileName = "파일 이름 확인 중…"
            documentJob = lifecycleScope.launch {
                ui.fileName = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        }?.takeIf { it.isNotBlank() } ?: "선택한 음성 파일"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { "선택한 음성 파일" }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshFiles()
        // Install the collector before any user start action. Never log or save event contents.
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            TalkRecLab.events.collect { event ->
                ui.accept(event)
                // 파일이 끝나면 엔진이 Paused로 남아 다음 파일을 막는다. 바로 정리하고 결과는 화면에 남긴다.
                if (event == TalkDebugEvent.SourceEnded && ui.ownsSession) {
                    stopLab("파일 끝 · 다른 파일을 골라 다시 시작할 수 있어요")
                }
            }
        }
        setContent {
            DongbanchaV3Theme {
                val capture by TalkRecLab.state.collectAsStateWithLifecycle()
                val sink by TalkRecLab.sink.collectAsStateWithLifecycle()
                val stopping by TalkRecLab.stopping.collectAsStateWithLifecycle()
                val settings by container.summarySettingsStore.settings.collectAsStateWithLifecycle()
                val available = TalkRecLab.canStart()
                LaunchedEffect(capture, sink) {
                    // A real trip can replace a lab without this Activity initiating stop.
                    if (ui.ownsSession && sink == null) {
                        ui.ownsSession = false
                        ui.clear()
                        ui.banner = "실험이 끝났어요. 여행 녹음 상태를 확인해 주세요."
                    }
                }
                DebugToolsLayout(container) { restricted ->
                    LabScreen(
                        ui, capture, settings, available, stopping, restricted,
                        onPick = ::pickDocument,
                        onRefresh = ::refreshFiles,
                        onSelectFile = ::selectFile,
                        onStart = ::requestStart,
                        onStop = { stopLab("실험을 정지했어요") },
                        onSummary = ::summarize,
                        onSplit = ::splitStories,
                    )
                }
            }
        }
    }

    private fun requestStart() {
        if (!TalkRecLab.canStart()) {
            ui.banner = busyMessage()
            return
        }
        val request = LabStartRequest(ui.mode, ui.fileUri, ui.playAloud)
        if (request.mode == LabMode.FILE && request.uri == null) return
        if (ContextCompat.checkSelfPermission(this, TalkRecLab.REQUIRED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = request
            ui.permissionPending = true
            permission.launch(TalkRecLab.REQUIRED_PERMISSION)
        } else start(request)
    }

    private fun start(request: LabStartRequest) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        try {
            // Both sources use the microphone-type foreground service and need RECORD_AUDIO.
            when (request.mode) {
                LabMode.MIC -> TalkRecLab.startMic(this)
                LabMode.FILE -> TalkRecLab.startFile(this, request.uri ?: return, request.playAloud)
            }
            ui.clear()
            ui.ownsSession = true
        } catch (_: LabBusyException) {
            ui.banner = busyMessage()
        } catch (_: SecurityException) {
            ui.banner = "마이크·음성 인식 권한을 확인해 주세요"
        } catch (_: RuntimeException) {
            ui.banner = "실험을 시작하지 못했어요. 음성 인식 서비스 상태를 확인해 주세요."
        }
    }

    private fun stopLab(message: String) {
        TalkRecLab.stop()
        ui.ownsSession = false
        ui.rms = 0.0
        ui.partial = ""
        ui.banner = message
    }

    private fun busyMessage() = if (TalkRecLab.stopping.value) "이전 실험 정리 중" else "여행 녹음 중이라 시작할 수 없어요"

    private fun summarize(block: LabBlock) {
        val lines = block.closed?.lines?.takeIf { it.isNotEmpty() } ?: return
        if (block.summarizing) return
        if (container.drivingSafetyMonitor.state.value.isRestricted) {
            ui.banner = "제목 시험은 정차 중에만 할 수 있어요"
            return
        }
        block.summary = null
        block.summarizing = true
        // Lazy start makes job ownership deterministic even if the provider returns immediately.
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                block.summary = container.summarySettingsStore.trySummarize(lines.joinToString("\n"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                block.summary = ConnectionTestResult(ok = false, elapsedMs = 0, errorCode = "connection_error")
            } finally {
                block.summarizing = false
                block.summaryJob = null
            }
        }
        block.summaryJob = job
        job.start()
    }

    private fun splitStories(run: LabRun) {
        if (run.job != null || container.drivingSafetyMonitor.state.value.isRestricted) return
        run.error = null
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try { run.result = container.summarySettingsStore.tryStorySplit(run.segments) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { run.error = "이야기 시험에 실패했어요" }
            finally { run.job = null }
        }
        run.job = job
        job.start()
    }

    override fun onDestroy() {
        try {
            TalkRecLab.stop()
        } finally {
            pendingStart = null
            documentJob?.cancel()
            filesJob?.cancel()
            ui.clear()
            ui.fileUri = null
            ui.fileName = ""
            ui.files = emptyList()
            super.onDestroy()
        }
    }
}

private enum class LabMode { MIC, FILE }
private data class LabStartRequest(val mode: LabMode, val uri: Uri?, val playAloud: Boolean)

private class LabBlock(val id: String) {
    var deleted by mutableStateOf(false)
    var closed by mutableStateOf<TalkDebugEvent.BlockClosed?>(null)
    var decision by mutableStateOf<TalkDebugEvent.Decision?>(null)
    var expanded by mutableStateOf(false)
    var summarizing by mutableStateOf(false)
    var summary by mutableStateOf<ConnectionTestResult?>(null)
    var summaryJob: Job? = null
    fun clear() {
        summaryJob?.cancel()
        summaryJob = null
        summary = null
        closed = null
        decision = null
        summarizing = false
    }
}

private class LabRun(val id: String, var segments: List<String>) {
    var result by mutableStateOf<StorySplitResult?>(null)
    var error by mutableStateOf<String?>(null)
    var job by mutableStateOf<Job?>(null)
    fun clear() { job?.cancel(); job = null; result = null; segments = emptyList() }
}

private class LabScreenState {
    var mode by mutableStateOf(LabMode.MIC)
    var fileUri by mutableStateOf<Uri?>(null)
    var fileName by mutableStateOf("")
    var files by mutableStateOf<List<LabAudioFile>>(emptyList())
    var filesLoading by mutableStateOf(false)
    var filesError by mutableStateOf(false)
    var pickerAvailable by mutableStateOf(false)
    var playAloud by mutableStateOf(true)
    var ownsSession by mutableStateOf(false)
    var permissionPending by mutableStateOf(false)
    var partial by mutableStateOf("")
    var rms by mutableStateOf(0.0)
    var banner by mutableStateOf<String?>(null)
    var finalSerial by mutableStateOf(0L)
    val finals = mutableStateListOf<TalkDebugEvent.Final>()
    val blocks = mutableStateListOf<LabBlock>()
    val runs = mutableStateListOf<LabRun>()

    fun clear() {
        runs.forEach { it.clear() }; runs.clear()
        blocks.forEach { it.clear() }
        blocks.clear()
        finals.clear()
        partial = ""
        rms = 0.0
        banner = null
        finalSerial++
    }

    private fun block(id: String): LabBlock = blocks.firstOrNull { it.id == id } ?: LabBlock(id).also {
        if (blocks.size >= 100) blocks.removeAt(0).clear()
        blocks += it
    }

    fun accept(event: TalkDebugEvent) {
        when (event) {
            is TalkDebugEvent.Level -> rms = event.rms
            is TalkDebugEvent.Partial -> partial = event.text
            is TalkDebugEvent.Final -> {
                partial = ""
                if (finals.size >= 200) finals.removeAt(0)
                finals += event
                finalSerial++
            }
            is TalkDebugEvent.BlockClosed -> block(event.blockId).closed = event
            is TalkDebugEvent.Decision -> {
                block(event.blockId).decision = event
                if (event.savedCountDelta < 0) {
                    blocks.lastOrNull { !it.deleted && (it.decision?.savedCountDelta ?: 0) > 0 }?.let { deleted ->
                        deleted.deleted = true
                        // A closed run may lose its last clip through the refusal command.
                        runs.firstOrNull { it.id == deleted.closed?.runId }?.let { old ->
                            old.clear(); runs.remove(old)
                            val remaining = blocks.filter { !it.deleted && it.closed?.runId == old.id && (it.decision?.savedCountDelta ?: 0) > 0 }
                            if (remaining.isNotEmpty()) runs += LabRun(old.id, remaining.map { it.closed!!.lines.joinToString("\n") })
                        }
                    }
                }
            }
            is TalkDebugEvent.RunClosed -> {
                val members = blocks.filter { !it.deleted && it.closed?.runId == event.runId && (it.decision?.savedCountDelta ?: 0) > 0 }
                if (members.isNotEmpty() && runs.none { it.id == event.runId }) {
                    if (runs.size >= 100) runs.removeAt(0).clear()
                    runs += LabRun(event.runId, members.map { it.closed!!.lines.joinToString("\n") })
                }
            }
            TalkDebugEvent.VoicePaused, is TalkDebugEvent.AiRequest, is TalkDebugEvent.AiResponse -> Unit
            TalkDebugEvent.SourceEnded -> { rms = 0.0; partial = ""; banner = "파일 끝" }
            TalkDebugEvent.Stopped -> { rms = 0.0; partial = "" }
            is TalkDebugEvent.SttError -> banner = when (event.code) {
                "stt_reconnect" -> "음성 인식 서비스에 다시 연결 중이에요"
                else -> "음성 인식에 실패했어요. 서비스·권한을 확인해 주세요."
            }
            is TalkDebugEvent.SourceError -> {
                rms = 0.0
                partial = ""
                banner = when (event.code) {
                    "file_unreadable" -> "음성 파일을 읽을 수 없어요. 다시 골라 주세요."
                    "unsupported_audio" -> "지원하지 않는 음성 파일이에요"
                    "corrupt_audio" -> "음성 파일이 손상됐거나 디코딩할 수 없어요"
                    "playback_failed" -> "소리 재생에 실패했어요"
                    else -> "입력 소스에 문제가 생겼어요"
                }
            }
        }
    }
}

@Composable
private fun LabScreen(
    ui: LabScreenState, capture: CaptureState, settings: SummarySettings,
    available: Boolean, stopping: Boolean, restricted: Boolean, onPick: () -> Unit, onStart: () -> Unit,
    onRefresh: () -> Unit, onSelectFile: (LabAudioFile) -> Unit,
    onStop: () -> Unit, onSummary: (LabBlock) -> Unit, onSplit: (LabRun) -> Unit,
) {
    val controlsEnabled = available && !ui.ownsSession && !ui.permissionPending
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF3F5F7)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("인식 실험실", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = ui::clear) { Text("지우기") }
        }
        val provider = when (settings.provider) {
            SummaryProviderType.CLOUDFLARE -> "Cloudflare"
            SummaryProviderType.AGY -> "agy 중계"
            SummaryProviderType.FAKE -> "Fake (로컬)"
        }
        Text("제목·이야기 시험 제공자: $provider  ·  현재 모델: ${settings.modelName}", style = MaterialTheme.typography.titleMedium)
        Text("블록의 ‘제목 만들어 보기’를 누르면 위 제공자에게 해당 대화가 전달돼요. 실험 녹음은 여행에 저장되지 않아요.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(ui.mode == LabMode.MIC, { ui.mode = LabMode.MIC }, label = { Text("PC 마이크") }, enabled = controlsEnabled)
            FilterChip(ui.mode == LabMode.FILE, { ui.mode = LabMode.FILE }, label = { Text("음성 파일") }, enabled = controlsEnabled)
            Button(onClick = onStart, enabled = controlsEnabled && (ui.mode == LabMode.MIC || ui.fileUri != null)) {
                Text(if (ui.permissionPending) "권한 확인 중…" else "시작")
            }
            OutlinedButton(onClick = onStop, enabled = ui.ownsSession) { Text("정지") }
            if (!available) Text(
                when {
                    ui.ownsSession -> "실험 중 · 입력을 바꾸려면 정지해 주세요"
                    stopping -> "이전 실험 정리 중"
                    else -> "여행 녹음 중이라 시작할 수 없어요"
                }, style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (ui.mode == LabMode.MIC) {
            Text("에뮬레이터 Extended controls > Microphone > \"Enable Host Microphone Access\"를 켜세요 (AVD 기본값: 꺼짐).", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("실험실 파일 · 내부 files/lab", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onRefresh, enabled = !ui.filesLoading) { Text("새로고침") }
                OutlinedButton(onClick = onPick, enabled = controlsEnabled && ui.pickerAvailable) { Text("다른 파일 고르기") }
            }
            if (!ui.pickerAvailable) {
                Text("이 기기는 시스템 파일 선택기를 지원하지 않아요. 실험실 폴더의 파일을 골라 주세요.", style = MaterialTheme.typography.bodySmall)
            }
            when {
                ui.filesLoading -> Text("파일 목록 확인 중…")
                ui.filesError -> Text("실험실 폴더를 읽을 수 없어요. 새로고침해 주세요.")
                ui.files.isEmpty() -> Text(
                    "files/lab에 음성 파일이 없어요. PC에서 추가한 뒤 새로고침하세요:\n" +
                        ".\\module\\talkrec\\tools\\push-lab-audio.ps1 -Path \"C:\\audio\" -User 10",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> LazyColumn(Modifier.fillMaxWidth().height(140.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(ui.files, key = { it.file.name }) { file ->
                        FilterChip(
                            selected = ui.fileUri == Uri.fromFile(file.file),
                            onClick = { onSelectFile(file) },
                            enabled = controlsEnabled,
                            label = {
                                val size = String.format(Locale.ROOT, "%.2f MB", file.sizeBytes / 1_000_000.0)
                                val duration = file.durationMs?.let { " · ${it / 60_000}:${(it / 1_000 % 60).toString().padStart(2, '0')}" }.orEmpty()
                                Text("${file.file.name} · $size$duration")
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.fileName.ifBlank { "선택한 파일 없음" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Checkbox(checked = ui.playAloud, enabled = controlsEnabled, onCheckedChange = { ui.playAloud = it })
                Text("소리도 같이 재생", style = MaterialTheme.typography.bodyLarge)
            }
        }
        ui.banner?.let {
            Text(it, Modifier.fillMaxWidth().background(Color(0xFFFFE9CF)).padding(12.dp), style = MaterialTheme.typography.titleMedium)
        }
        if (restricted) Text("제목 시험은 정차 중에만 할 수 있어요", color = MaterialTheme.colorScheme.error)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 900.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LivePanel(ui, capture, Modifier.weight(1f).fillMaxHeight())
                    BlocksPanel(ui, restricted, onSummary, onSplit, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LivePanel(ui, capture, Modifier.weight(1f))
                    BlocksPanel(ui, restricted, onSummary, onSplit, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LivePanel(ui: LabScreenState, capture: CaptureState, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(ui.finalSerial) {
        if (ui.finals.isNotEmpty()) listState.scrollToItem(ui.finals.lastIndex)
    }
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("실시간 인식", style = MaterialTheme.typography.titleLarge)
            Text("상태 ${capture.status} · 프레임 ${capture.frameCount} · 확정 ${capture.finalCount} · 묶음 ${capture.blockCount} · 채택 ${capture.savedCount}", style = MaterialTheme.typography.bodyLarge)
            Text("채택은 실험 메모리 승인 수예요. 오류 ${capture.errorCount}회", style = MaterialTheme.typography.bodySmall)
            // PCM16 RMS -> dBFS; -60..0 dB becomes 0..1 (silence remains exactly zero).
            val db = if (ui.rms.isFinite() && ui.rms > 0) 20 * log10((ui.rms / 32768.0).coerceAtMost(1.0)) else -60.0
            LinearProgressIndicator(progress = { ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(12.dp))
            Text("입력 레벨 ${decimal(db.coerceAtLeast(-60.0))} dBFS", style = MaterialTheme.typography.bodyMedium)
            Text(ui.partial.ifBlank { "인식 중인 문장이 여기에 보여요" }, color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
            Text("확정 문장 (최근 200줄 · 아래가 최신)", style = MaterialTheme.typography.titleMedium)
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                if (ui.finals.isEmpty()) item { Text("아직 확정된 문장이 없어요") }
                items(ui.finals) { Text(it.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun BlocksPanel(ui: LabScreenState, restricted: Boolean, onSummary: (LabBlock) -> Unit, onSplit: (LabRun) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("묶음과 판정 (최근 100개)", style = MaterialTheme.typography.titleLarge)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            items(ui.runs, key = { "run:${it.id}" }) { run ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Run ${run.id.take(8)} · 닫힘 · 클립 ${run.segments.size}개")
                        OutlinedButton({ onSplit(run) }, enabled = !restricted && run.job == null) {
                            Text(if (run.job != null) "이야기 나누는 중…" else "이야기 나눠 보기")
                        }
                        run.error?.let { Text(it) }
                        run.result?.stories?.forEach { story ->
                            Text("묶음 ${story.segments.joinToString()} · ${story.title}")
                            Text("${story.category} · ${story.keywords.joinToString()} · ${story.kind}")
                            Text("keep=${story.keep} · reason=${story.reason}")
                        }
                    }
                }
            }
            if (ui.blocks.isEmpty()) item { Text("묶음이 닫히면 판정 결과가 보여요", style = MaterialTheme.typography.bodyLarge) }
            items(ui.blocks, key = { it.id }) { block -> BlockCard(block, restricted, onSummary) }
        }
    }
}

@Composable
private fun BlockCard(block: LabBlock, restricted: Boolean, onSummary: (LabBlock) -> Unit) {
    val closed = block.closed
    val decision = block.decision
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("묶음 ${block.id.take(8)}", style = MaterialTheme.typography.titleMedium)
            Text("Run ${closed?.runId?.take(8) ?: "—"}${if (block.deleted) " · 삭제됨" else ""}")
            if (closed == null) Text("묶음 상세 이벤트를 받지 못했어요")
            else {
                val reason = when (closed.reason) { CloseReason.SILENCE -> "무음"; CloseReason.LENGTH -> "길이"; CloseReason.MANUAL -> "수동" }
                val source = when (closed.source) { CaptureSource.AUTO -> "자동"; CaptureSource.MANUAL -> "수동" }
                Text("종료 $reason · 소스 $source · ${decimal(closed.durationMs / 1000.0)}초", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { block.expanded = !block.expanded }) {
                    Text("문장 ${closed.lines.size}줄 ${if (block.expanded) "접기" else "펼치기"}")
                }
                if (block.expanded) closed.lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            Text(decision?.let { outcomeLabel(it.outcome) } ?: "판정 대기 중", style = MaterialTheme.typography.titleMedium)
            if (decision != null) {
                Text("유지 확률 ${decision.keepProbability?.let(::decimal) ?: "—"} · 종류 ${decision.kind ?: "—"} · 판정 ${decision.judgeMs?.let { "${it}ms" } ?: "—"}")
                Text("규칙 ID: ${decision.policyReasons.joinToString(", ").ifBlank { "없음" }}")
                if (decision.outcome == DebugOutcome.JUDGE_FAILED && decision.savedCountDelta > 0) Text("판정 실패 후 기본 동작으로 채택됐어요")
            }
            OutlinedButton(onClick = { onSummary(block) }, enabled = !restricted && !block.summarizing && closed?.lines?.any { it.isNotBlank() } == true) {
                Text(if (block.summarizing) "제목 만드는 중…" else "제목 만들어 보기")
            }
            block.summary?.let { result ->
                if (result.ok) {
                    result.result?.let {
                        Text("제목: ${it.title}", style = MaterialTheme.typography.titleMedium)
                        Text("태그: ${it.tags.joinToString(", ")}", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("응답 모델: ${result.modelName ?: "—"} · ${result.elapsedMs}ms")
                } else Text("제목 시험 오류: ${result.errorCode ?: "unknown"} · ${result.elapsedMs}ms", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
internal fun outcomeLabel(outcome: DebugOutcome): String = when (outcome) {
    DebugOutcome.SAVED -> "저장"
    DebugOutcome.JUDGE_DROP -> "판정 탈락"
    DebugOutcome.POLICY_BLOCK -> "기록 거부 규칙"
    DebugOutcome.COMMAND_LINES_DROPPED -> "명령 섞여 버림"
    DebugOutcome.RETRO_DELETE -> "직전 대화 지움"
    DebugOutcome.EMPTY -> "빈 묶음"
    DebugOutcome.BUDGET -> "시간 한도"
    DebugOutcome.SINK_REJECTED -> "저장 거절"
    DebugOutcome.JUDGE_FAILED -> "판정 실패"
}
