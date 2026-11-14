package com.dongbantest.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.data.ask.QuestionTrial
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.ask.QuestionPolicy
import com.drivediary.talkrec.ask.Reaction
import com.drivediary.talkrec.ask.ReactionClassifier
import com.drivediary.talkrec.ask.SpeakResult
import com.drivediary.talkrec.ask.WeatherClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 질문 실험실 (question-suggest DESIGN §9). 디버그 빌드 전용.
 * 지금 위치로 질문 강제 생성, 위치 덮어쓰기, Pleos TTS 읽기, 흐름(조용함 흉내·반응) 시험, 상태 기록.
 * 대화 전사 글은 보이지 않는다. 반응 글 입력칸의 글은 분류에만 쓰고 저장하지 않는다.
 */
class QuestionLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DongbanApplication).container
        setContent {
            DongbanchaV3Theme {
                DebugToolsLayout(container) { QuestionLab() }
            }
        }
    }
}

/** 에뮬레이터 위치가 고정이라 쓰는 위치 프리셋 */
private val PRESETS = listOf(
    "서울역" to (37.5547 to 126.9707),
    "강릉 경포해변" to (37.8055 to 128.9081),
    "제주공항" to (33.5104 to 126.4914),
)

/** 시험용 짧은 시간: 30초 조용하면 묻고, 여행 시작·남은 시간 조건은 없다. */
private val SHORT_POLICY = QuestionPolicy(
    quietMs = 30_000, prepareLeadMs = 10_000, minTripAgeMs = 0, minRemainSeconds = 0,
    cooldownMs = 60_000, maxPerTrip = 10, reactionWindowMs = 10_000, answerWindowMs = 30_000,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionLab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { (context.applicationContext as DongbanApplication).container }
    val coordinator = container.questionCoordinator
    val speaker = container.questionSpeaker
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    val ui by coordinator.ui.collectAsStateWithLifecycle()
    val log by coordinator.log.collectAsStateWithLifecycle()
    val capture by TalkRec.state.collectAsStateWithLifecycle()
    val speech by TalkRec.speech.collectAsStateWithLifecycle()
    val route by container.naviMonitor.route.collectAsStateWithLifecycle()

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }

    var override by remember { mutableStateOf(coordinator.locationOverride) }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var trial by remember { mutableStateOf<QuestionTrial?>(null) }
    var making by remember { mutableStateOf(false) }
    var ttsText by remember { mutableStateOf("") }
    var speaking by remember { mutableStateOf(false) }
    var speakResult by remember { mutableStateOf<SpeakResult?>(null) }
    val ttsEvents = remember { mutableStateListOf<String>() }
    var shortPolicy by remember { mutableStateOf(coordinator.policyOverride != null) }
    var reactionText by remember { mutableStateOf("") }
    var reactionResult by remember { mutableStateOf<Reaction?>(null) }
    val time = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA) }

    LaunchedEffect(speaker) {
        speaker.events.collect { e -> ttsEvents.add(0, "${time.format(Date(e.atMs))} ${e.name}"); if (ttsEvents.size > 20) ttsEvents.removeAt(20) }
    }

    fun setOverride(value: Pair<Double, Double>?) { coordinator.locationOverride = value; override = value }

    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("질문 실험실", style = MaterialTheme.typography.titleLarge)

        // ── 상태
        Section("상태")
        Mono(
            "여행 ${ui.tripId ?: "없음"} · 녹음 ${capture.status} · 단계 ${ui.phase}${if (ui.muted) " · 먼저 묻지 않음" else ""}\n" +
                "조용함 ${speech.quietMs(now) / 1000}초 · 입력 ${if (speech.live(now)) "들어오는 중" else "없음"} · 알림 ${ui.overlay.javaClass.simpleName}\n" +
                "목적지 ${route?.destinationName ?: "-"} · 남은 시간 ${route?.remainSeconds?.let { "${it / 60}분" } ?: "-"}"
        )

        // ── 위치
        Section("위치")
        val tracked = container.locationTracker.lastSample
        Mono(
            "지금: " + (override?.let { "덮어씀 %.4f, %.4f".format(it.first, it.second) }
                ?: tracked?.let { "%.4f, %.4f (기록 중 위치)".format(it.latitude, it.longitude) } ?: "없음") +
                "\n도착지: " + (container.arrivalManager.destination?.let { "${it.name} %.4f, %.4f".format(it.latitude, it.longitude) } ?: "내비 목적지 없음")
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { (name, ll) -> OutlinedButton(onClick = { setOverride(ll) }) { Text(name) } }
            OutlinedButton(onClick = { setOverride(null) }) { Text("덮어쓰기 끄기") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(latText, { latText = it }, Modifier.weight(1f), label = { Text("위도") }, singleLine = true)
            OutlinedTextField(lonText, { lonText = it }, Modifier.weight(1f), label = { Text("경도") }, singleLine = true)
            Button(onClick = {
                val lat = latText.toDoubleOrNull(); val lon = lonText.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) setOverride(lat to lon)
            }) { Text("적용") }
        }

        // ── 질문 만들기
        Section("질문 만들기 (조용함·동의 조건 무시)")
        Button(enabled = !making, onClick = {
            making = true
            scope.launch {
                trial = coordinator.makeQuestion(ui.tripId, includeWeather = true)
                trial?.question?.let { ttsText = it.question }
                making = false
            }
        }) { Text(if (making) "만드는 중…" else "지금 위치로 질문 만들기") }
        trial?.let { t ->
            Mono(
                "걸린 시간 ${t.latencyMs}ms\n" +
                    "날씨 지금: ${t.weatherNow?.describe() ?: "-"} / 도착지: ${t.weatherDestination?.describe() ?: "-"}\n" +
                    (t.question?.let { q -> "질문: ${q.question}\n보조: ${q.hint.ifBlank { "-" }}\n주제: ${q.topic} · 출처: ${q.source}" }
                        ?: "실패: ${t.error} (앱 질문 목록으로 대신함)")
            )
            Text("보낸 맥락 JSON", style = MaterialTheme.typography.labelLarge)
            Mono(t.context.toJson())
        }
        // Open-Meteo CC BY 4.0: 날씨를 보여 주는 곳 옆에 출처 링크
        androidx.compose.material3.TextButton(onClick = { uri.openUri(WeatherClient.ATTRIBUTION_URL) }) {
            Text(WeatherClient.ATTRIBUTION, color = Color(0xFF1A5FB4), style = MaterialTheme.typography.bodySmall)
        }

        // ── TTS
        Section("Pleos TTS (ON_DEVICE, ko-KR)")
        OutlinedTextField(ttsText, { ttsText = it }, Modifier.fillMaxWidth(), label = { Text("읽을 문장") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !speaking && ttsText.isNotBlank(), onClick = {
                speaking = true
                scope.launch { speakResult = speaker.speak(ttsText.trim()); speaking = false }
            }) { Text(if (speaking) "읽는 중…" else "읽기") }
            OutlinedButton(onClick = { speaker.stop() }) { Text("멈춤") }
        }
        speakResult?.let { r ->
            Mono(if (r.ok) "성공 · 첫 소리 ${r.firstSoundMs ?: "-"}ms · 전체 ${r.totalMs}ms" else "실패 ${r.error} · ${r.totalMs}ms")
        }
        if (ttsEvents.isNotEmpty()) Mono(ttsEvents.joinToString("\n"))

        // ── 흐름
        Section("흐름 시험 (진행 중인 녹음 여행 필요)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(shortPolicy, onCheckedChange = {
                shortPolicy = it
                coordinator.policyOverride = if (it) SHORT_POLICY else null
            })
            Text("시험용 짧은 시간 (조용함 30초, 대답 시간 30초)")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { coordinator.forceCheck() }) { Text("조용함 흉내 (Q01)") }
            Button(onClick = { coordinator.forceAsk() }) { Text("바로 질문 (Q02)") }
            OutlinedButton(onClick = { coordinator.debugReaction(Reaction.ACCEPT) }) { Text("반응: 수락") }
            OutlinedButton(onClick = { coordinator.debugReaction(Reaction.DECLINE) }) { Text("반응: 거절") }
            OutlinedButton(onClick = { coordinator.debugReaction(Reaction.OTHER) }) { Text("반응: 기타") }
            OutlinedButton(onClick = { coordinator.unmute() }) { Text("먼저 묻기 다시 켜기") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(reactionText, { reactionText = it }, Modifier.weight(1f), label = { Text("반응 글 분류 시험") }, singleLine = true)
            Button(onClick = { reactionResult = ReactionClassifier.classify(reactionText) }) { Text("분류") }
        }
        reactionResult?.let { Mono("분류 결과: $it") }

        Section("상태 기록")
        Mono(log.reversed().joinToString("\n").ifBlank { "-" })
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().background(Color(0xFFF4F4F4)).padding(8.dp),
    )
}
