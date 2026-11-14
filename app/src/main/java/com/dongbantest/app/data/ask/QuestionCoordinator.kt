package com.dongbantest.app.data.ask

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.dongbantest.app.data.repository.QuestionBank
import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.model.MemoryType
import com.dongbantest.app.location.LocationTracker
import com.dongbantest.app.navi.ArrivalManager
import com.dongbantest.app.navi.NaviMonitor
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.ask.AskEvent
import com.drivediary.talkrec.ask.AskInputs
import com.drivediary.talkrec.ask.AskPhase
import com.drivediary.talkrec.ask.GeneratedQuestion
import com.drivediary.talkrec.ask.QuestionContext
import com.drivediary.talkrec.ask.QuestionGenerators
import com.drivediary.talkrec.ask.QuestionPolicy
import com.drivediary.talkrec.ask.QuestionSpeaker
import com.drivediary.talkrec.ask.QuietWatcher
import com.drivediary.talkrec.ask.Reaction
import com.drivediary.talkrec.ask.ReactionClassifier
import com.drivediary.talkrec.ask.WeatherClient
import com.drivediary.talkrec.ask.WeatherSnapshot
import com.drivediary.talkrec.capture.CaptureStatus
import com.drivediary.talkrec.summary.SummarySettingsStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** 화면에 띄울 질문 알림 */
sealed interface QuestionOverlay {
    data object None : QuestionOverlay
    /** Q01: 화면으로만 묻는 "혹시 다들 자고 있나요?" */
    data object Check : QuestionOverlay
    /** Q02: TTS 로 읽는 질문 */
    data class Asking(val question: String, val hint: String) : QuestionOverlay
}

data class QuestionUi(
    val tripId: Long? = null,
    val phase: AskPhase = AskPhase.OFF,
    val overlay: QuestionOverlay = QuestionOverlay.None,
    /** 이번 여행에서는 먼저 묻지 않는다 */
    val muted: Boolean = false,
    /** 마지막으로 건넨 질문(질문 카드에 보인다) */
    val lastQuestion: String? = null,
)

/** 디버그 실험실용: 만든 질문과 그 재료. 대화 글은 들어가지 않는다. */
class QuestionTrial(
    val context: QuestionContext,
    val weatherNow: WeatherSnapshot?,
    val weatherDestination: WeatherSnapshot?,
    val question: GeneratedQuestion?,
    val error: String?,
    val latencyMs: Long,
)

/**
 * 조용할 때만 건네는 대화 질문 (talk-rec/docs/question-suggest).
 *
 * 1초마다 캡처의 조용함·녹음 상태·내비 남은 시간을 [QuietWatcher]에 넣고, 나온 사건대로
 * Q01 화면 확인 → 소리 반응 → TTS 질문 → 녹음하지 않는 대답 시간을 진행한다.
 * 반응 글은 [ReactionClassifier]로 그 자리에서 나누고 버린다. 로그에는 상태와 이유만 남긴다.
 */
class QuestionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tripRepository: TripRepository,
    private val consentStore: RecordingConsentStore,
    private val summarySettings: SummarySettingsStore,
    private val settings: QuestionSettingsStore,
    private val naviMonitor: NaviMonitor,
    private val arrivalManager: ArrivalManager,
    private val locationTracker: LocationTracker,
    private val speaker: QuestionSpeaker,
    private val weather: WeatherClient = WeatherClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow(QuestionUi())
    val ui: StateFlow<QuestionUi> = state.asStateFlow()

    private val logLines = MutableStateFlow<List<String>>(emptyList())
    /** 디버그 실험실의 상태 기록 (상태 전이와 이유만) */
    val log: StateFlow<List<String>> = logLines.asStateFlow()

    /** 디버그 실험실의 시험용 시간 덮어쓰기. null 이면 기본값 + 사용자 설정. */
    @Volatile var policyOverride: QuestionPolicy? = null
    /** 디버그 위치 덮어쓰기 (에뮬레이터는 위치가 고정이다) */
    @Volatile var locationOverride: Pair<Double, Double>? = null

    private var watcher = QuietWatcher()
    private var tripId: Long? = null
    private var tripStartedAt = 0L
    private var prepared: Pair<Long, GeneratedQuestion>? = null
    private var preparing: Deferred<GeneratedQuestion>? = null
    private var overlayJob: Job? = null
    private var started = false
    /** 사용자가 직접 요청한 질문을 읽는 동안 > 0. 이 동안은 조용함 감시가 TTS 와 화면을 끊지 않는다. */
    private val onDemandSpeaking = AtomicInteger(0)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                try { tick() } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { Log.w(TAG, "tick failed ${e.javaClass.simpleName}") }
                delay(1_000)
            }
        }
    }

    private fun policy(): QuestionPolicy =
        policyOverride ?: QuestionPolicy(answerWindowMs = settings.answerWindowSeconds.value * 1_000L)

    private suspend fun tick() {
        val capture = TalkRec.state.value
        val active = capture.tripId?.takeIf { capture.status == CaptureStatus.On || capture.status == CaptureStatus.Paused }
        if (active != tripId) switchTrip(active)
        val id = tripId ?: return
        val now = clock()
        val speech = TalkRec.speech.value
        watcher.policy = policy()
        val inputs = AskInputs(
            nowMs = now,
            listening = capture.status == CaptureStatus.On && speech.live(now) && settings.enabled.value,
            quietMs = speech.quietMs(now),
            tripStartedAtMs = tripStartedAt,
            remainSeconds = naviMonitor.route.value?.remainSeconds,
        )
        watcher.tick(inputs)?.let { handle(it, id) }
        publishPhase()
    }

    private suspend fun switchTrip(next: Long?) {
        endCheck()
        val keepSpeech = onDemandSpeaking.get() > 0
        if (!keepSpeech) { overlayJob?.cancel(); speaker.stop() }
        prepared = null; preparing?.cancel(); preparing = null
        watcher = QuietWatcher(policy())
        tripId = next
        tripStartedAt = next?.let { id -> tripRepository.observeTripWithMemories(id).first()?.trip?.startedAt } ?: 0L
        state.value = if (keepSpeech) state.value.copy(tripId = next ?: state.value.tripId, phase = AskPhase.OFF, muted = false)
        else QuestionUi(tripId = next)
        note("여행 ${next ?: "없음"}")
    }

    private fun handle(event: AskEvent, id: Long) {
        note("${event.javaClass.simpleName}${detail(event)} quiet=${TalkRec.speech.value.quietMs(clock()) / 1000}s phase=${watcher.phase}")
        when (event) {
            AskEvent.Prepare -> prepareAsync(id)
            AskEvent.ShowCheck -> showCheck(id)
            is AskEvent.Ask -> { endCheck(); scope.launch { ask(id, onDemand = event.forced) } }
            is AskEvent.Muted -> { endCheck(); setOverlay(QuestionOverlay.None) }
            is AskEvent.Listening -> { endCheck(); if (state.value.overlay is QuestionOverlay.Check) setOverlay(QuestionOverlay.None) }
            AskEvent.Stopped -> {
                endCheck()
                // 사용자가 요청한 질문은 녹음이 멈춘 뒤에도 끝까지 읽는다.
                if (onDemandSpeaking.get() == 0) { speaker.stop(); setOverlay(QuestionOverlay.None) }
            }
        }
        publishPhase()
    }

    private fun detail(event: AskEvent) = when (event) {
        is AskEvent.Muted -> "(${event.reason})"
        is AskEvent.Listening -> "(${event.reason})"
        is AskEvent.Ask -> if (event.forced) "(forced)" else ""
        else -> ""
    }

    private fun showCheck(id: Long) {
        if (preparing == null && freshPrepared() == null) prepareAsync(id)
        val now = clock()
        // 반응 창 동안 시작된 대화는 남기지 않는다(PLAN §6).
        TalkRec.markNoRecord(now, now + watcher.policy.reactionWindowMs)
        TalkRec.reactionTap = { text ->
            val reaction = ReactionClassifier.classify(text)
            scope.launch { react(reaction, "voice") }
        }
        setOverlay(QuestionOverlay.Check)
    }

    private fun endCheck() { TalkRec.reactionTap = null }

    private fun react(reaction: Reaction, source: String) {
        val id = tripId ?: return
        note("반응 $reaction ($source)")
        watcher.onReaction(reaction, clock())?.let { handle(it, id) }
    }

    /** Q01 화면의 "아니, 안 자" 예시를 정차 중에 눌렀을 때. */
    fun acceptFromScreen() = scope.launch { react(Reaction.ACCEPT, "screen") }

    /** "질문 그만" (화면 예시) */
    fun mute() = stopAsking("screen")

    /** "동반차 질문 그만해줘": 이번 여행에서는 먼저 묻지 않는다. [source] = voice / screen. */
    fun stopAsking(source: String) = scope.launch {
        note("질문 그만 ($source)")
        if (onDemandSpeaking.get() > 0) speaker.stop()
        val id = tripId ?: return@launch
        handle(watcher.mute(clock(), "user"), id)
    }

    /**
     * "동반차 다른 질문 해줘" (voice) / Gleo ASK_NEW_QUESTION (gleo) / 화면 예시 (screen).
     * 녹음 상태와 무관하다: 녹음 중이면 감시기의 강제 질문, 일시정지·녹음 없음이면 독립 질문.
     * 여행당 횟수에 세지 않고, 먼저 묻기 끔(muted)과 "조용할 때 질문하기" 설정은 건드리지 않는다.
     * @return 질문을 시작했으면 true, 진행 중인 여행이 없으면 false
     */
    suspend fun startQuestion(source: String): Boolean {
        val id = tripId ?: tripRepository.observeOngoingTrip().first()?.id
        if (id == null) { note("질문 요청($source): 진행 중인 여행 없음"); return false }
        note("질문 요청 ($source)")
        // 이미 읽는 중이면 끊고 새 질문으로 바꾼다.
        if (onDemandSpeaking.get() > 0) speaker.stop()
        val capture = TalkRec.state.value
        if (tripId == id && capture.tripId == id && capture.status == CaptureStatus.On) {
            handle(watcher.forceAsk(clock()), id)
        } else {
            state.update { it.copy(tripId = id) }
            scope.launch { ask(id, onDemand = true) }
        }
        return true
    }

    fun requestQuestion(source: String) = scope.launch { startQuestion(source) }

    fun unmute() = scope.launch { watcher.unmute(clock()); note("먼저 묻기 다시 켬"); publishPhase() }

    /** 디버그: 조용함 조건을 건너뛰고 Q01 을 띄운다. */
    fun forceCheck() = scope.launch {
        val id = tripId ?: return@launch note("진행 중인 녹음 여행이 없어요")
        handle(watcher.forceCheck(clock()), id)
    }

    /** 디버그·정차 중: 확인 없이 바로 질문한다(여행당 횟수에 세지 않음). */
    fun forceAsk() = scope.launch {
        val id = tripId ?: return@launch note("진행 중인 녹음 여행이 없어요")
        handle(watcher.forceAsk(clock()), id)
    }

    /** 디버그: 반응 글 대신 분류 결과를 직접 넣는다. */
    fun debugReaction(reaction: Reaction) = scope.launch { react(reaction, "debug") }

    private suspend fun ask(id: Long, onDemand: Boolean = false) {
        if (onDemand) onDemandSpeaking.incrementAndGet()
        try { askNow(id, onDemand) } finally { if (onDemand) onDemandSpeaking.decrementAndGet() }
    }

    private suspend fun askNow(id: Long, onDemand: Boolean) {
        val question = freshPrepared()
            ?: preparing?.let { job -> withTimeoutOrNull(3_000) { job.await() } }
            ?: (if (preparing == null && onDemand) withTimeoutOrNull(3_000) { prepare(id) } else null)
            ?: bankQuestion(id)
        prepared = null; preparing = null
        state.update { it.copy(lastQuestion = question.question) }
        setOverlay(QuestionOverlay.Asking(question.question, question.hint))
        tripRepository.addQuestion(id, question.question)
        val start = clock()
        // TTS 소리와 대답 시간은 녹음·기록하지 않는다. 끝 시각은 TTS 가 끝나면 다시 잡는다.
        TalkRec.markNoRecord(start, start + watcher.policy.askTimeoutMs + watcher.policy.answerWindowMs)
        note("질문 (${question.topic}, ${question.source})")
        val result = speaker.speak(question.question)
        val end = clock()
        TalkRec.markNoRecord(start, end + watcher.policy.answerWindowMs)
        note(if (result.ok) "TTS 끝 첫소리=${result.firstSoundMs}ms 전체=${result.totalMs}ms" else "TTS 실패 ${result.error}")
        if (tripId == id) watcher.spoken(end)
        publishPhase()
        overlayJob?.cancel()
        overlayJob = scope.launch {
            delay(ASK_OVERLAY_MS)
            if (state.value.overlay is QuestionOverlay.Asking) setOverlay(QuestionOverlay.None)
        }
    }

    private fun freshPrepared(): GeneratedQuestion? =
        prepared?.takeIf { clock() - it.first < PREPARED_TTL_MS }?.second

    private fun prepareAsync(id: Long) {
        if (preparing?.isActive == true || freshPrepared() != null) return
        val job = scope.async { prepare(id) }
        preparing = job
        scope.launch {
            val q = try { job.await() } catch (cancelled: CancellationException) { return@launch }
            if (tripId == id) prepared = clock() to q
        }
    }

    private suspend fun prepare(id: Long): GeneratedQuestion {
        // 외부 AI 동의(요약과 같은 하나의 동의)가 없으면 날씨도 부르지 않고 앱 안 질문 목록만 쓴다.
        if (!consentStore.getSendToAi(id)) return bankQuestion(id).also { note("외부 AI 동의 없음 → 질문 목록") }
        val trial = makeQuestion(id, includeWeather = true)
        trial.error?.let { note("질문 생성 실패 $it → 질문 목록") }
        return trial.question ?: bankQuestion(id)
    }

    private suspend fun previousQuestions(id: Long?): List<String> = id?.let {
        tripRepository.observeTripWithMemories(it).first()?.memories
            ?.filter { m -> m.type == MemoryType.QUESTION }?.sortedBy { m -> m.createdAt }?.mapNotNull { m -> m.text }
    }.orEmpty()

    private suspend fun bankQuestion(id: Long?): GeneratedQuestion {
        val asked = previousQuestions(id).toSet()
        val text = QuestionBank.questions.firstOrNull { it !in asked } ?: QuestionBank.next(asked.lastOrNull())
        return GeneratedQuestion(text, "", "free", "bank")
    }

    /** 맥락을 모아 질문 하나를 만든다. 디버그 실험실도 이 함수를 쓴다(동의·조용함 조건 없이). */
    suspend fun makeQuestion(id: Long?, includeWeather: Boolean): QuestionTrial {
        val begin = SystemClock.elapsedRealtime()
        val trip = id?.let { tripRepository.observeTripWithMemories(it).first()?.trip }
        val route = naviMonitor.route.value
        val here = locationOverride ?: locationTracker.lastSample?.let { it.latitude to it.longitude }
        val dest = arrivalManager.destination
        val weatherNow = if (includeWeather) here?.let { weather.current(it.first, it.second) } else null
        val weatherDest = if (includeWeather) dest?.let { weather.current(it.latitude, it.longitude) } else null
        val now = Calendar.getInstance().apply { timeInMillis = clock() }
        val ctx = QuestionContext(
            destination = route?.destinationName ?: dest?.name ?: trip?.destination?.ifBlank { null },
            area = QuestionContext.coarseArea(if (locationOverride == null) naviMonitor.currentAddress() else null),
            remainMinutes = QuestionContext.roundedMinutes(route?.remainSeconds),
            timeOfDay = QuestionContext.timeOfDay(now.get(Calendar.HOUR_OF_DAY)),
            weekday = QuestionContext.weekday(now),
            weatherNow = weatherNow?.describe(),
            weatherDestination = weatherDest?.describe(),
            previousQuestions = previousQuestions(id),
        )
        val generator = try { QuestionGenerators.create(context, summarySettings) } catch (e: Exception) { null }
        if (generator == null) {
            return QuestionTrial(ctx, weatherNow, weatherDest, null, "no_provider", SystemClock.elapsedRealtime() - begin)
        }
        return try {
            val q = generator.generate(ctx)
            QuestionTrial(ctx, weatherNow, weatherDest, q, null, SystemClock.elapsedRealtime() - begin)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) {
            val reason = (e as? com.drivediary.talkrec.ask.InvalidQuestion)?.reason
                ?: (e as? com.drivediary.talkrec.summary.SummaryException)?.code?.name?.lowercase() ?: "error"
            QuestionTrial(ctx, weatherNow, weatherDest, null, reason, SystemClock.elapsedRealtime() - begin)
        }
    }

    private fun setOverlay(overlay: QuestionOverlay) = state.update { it.copy(overlay = overlay) }

    private fun publishPhase() = state.update { it.copy(phase = watcher.phase, muted = watcher.muted) }

    private fun note(line: String) {
        Log.i(TAG, line)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(clock()))
        logLines.update { (it + "$stamp $line").takeLast(60) }
    }

    private companion object {
        const val TAG = "QuestionCoordinator"
        const val PREPARED_TTL_MS = 30 * 60_000L
        const val ASK_OVERLAY_MS = 15_000L
    }
}
