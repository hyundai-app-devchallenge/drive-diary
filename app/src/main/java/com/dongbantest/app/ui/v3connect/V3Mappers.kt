package com.dongbantest.app.ui.v3connect

import com.dongbantest.app.domain.model.MemoryType
import com.dongbantest.app.domain.model.RouteRibbon
import com.dongbantest.app.domain.model.Trip
import com.dongbantest.app.domain.model.TripWithMemories
import com.dongbantest.app.navi.NaviRoute
import com.dongbantest.app.ui.components.RouteColors
import com.dongbantest.app.ui.components.formatDuration
import com.dongbantest.app.ui.components.formatElapsed
import com.dongbantest.app.ui.components.formatShortDate
import com.dongbantest.app.ui.v3.components.AskedItem
import com.dongbantest.app.ui.v3.components.RecordingState
import com.dongbantest.app.ui.v3.components.ToastKind
import com.dongbantest.app.ui.v3.components.TripTone
import com.dongbantest.app.ui.v3.components.VoiceToastUi
import com.dongbantest.app.ui.v3.screens.ArrivalUiState
import com.dongbantest.app.ui.v3.screens.DiaryReadyUiState
import com.dongbantest.app.ui.v3.screens.DrivingCard
import com.dongbantest.app.ui.v3.screens.DrivingStates
import com.dongbantest.app.ui.v3.screens.DrivingUiState
import com.dongbantest.app.ui.v3.screens.HomeUiState
import com.dongbantest.app.ui.v3.screens.MakingDiaryUiState
import com.dongbantest.app.ui.v3.screens.MakingStep
import com.dongbantest.app.ui.v3.screens.PastTripUi
import com.dongbantest.app.ui.v3.screens.RecordMapUiState
import com.dongbantest.app.ui.v3.screens.StepState
import com.dongbantest.app.ui.v3.screens.SummaryCell
import com.dongbantest.app.ui.v3.screens.TalkUi
import com.dongbantest.app.ui.v3.screens.TripRecordUi
import com.dongbantest.app.viewmodel.RecordingMode
import com.dongbantest.app.viewmodel.TripUiState
import com.dongbantest.app.viewmodel.VoiceFeedback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.dongbantest.app.viewmodel.HomeUiState as AppHomeUiState

/*
 * 지금 앱의 데이터(ViewModel 상태)를 확정안 v3 화면이 받는 UiState 로 바꾸는 함수 모음.
 * v3 화면은 값을 받아 그리기만 하므로, 실제 값은 모두 여기서 채운다.
 * 기준 문서: docs/compose-ui-v3-integration.md
 */

/** v3 시간 표기 "09:20" (24시간) */
fun hhmm(millis: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(millis))

/** v3 날짜 표기 "2026년 10월 10일 토요일" */
fun longDate(millis: Long): String = SimpleDateFormat("yyyy년 M월 d일 EEEE", Locale.KOREA).format(Date(millis))

/** 기록 색: 지도 선 색(RouteColors)과 같은 순서로 고른다. */
fun toneOf(tripId: Long): TripTone = TripTone.entries[RouteColors.toneIndex(tripId)]

/** 길 리본 진행률 0–1 → v3 의 0–100 */
fun percentOf(progress: Float?): Int = ((progress ?: 0f) * 100).roundToInt().coerceIn(0, 100)

/** 내비 길 안내 정보 → 진행률 0–100 */
fun percentOf(route: NaviRoute?): Int = percentOf(RouteRibbon.progressOf(route?.totalMeters, route?.remainMeters))

private val SAY_ON = listOf("다른 질문 해 줘", "질문 그만")
private val SAY_PAUSED = listOf("다시 녹음해 줘", "다른 질문 해 줘")
private val SAY_OFF = listOf("녹음 시작해 줘", "다른 질문 해 줘")

/* ───────── 녹음 칩 ───────── */

/** 지금까지 녹음한 시간: 저장된 목소리 조각 + 지금 녹음 중인 조각 */
fun recordedMillis(state: TripUiState, now: Long): Long {
    val saved = state.data?.memories.orEmpty().filter { it.type == MemoryType.VOICE }.sumOf { it.durationMs ?: 0L }
    val current = state.recordingStartedAt?.let { now - it } ?: 0L
    return saved + current
}

fun recordingChip(state: TripUiState, now: Long): Pair<RecordingState, String> {
    val elapsed = formatElapsed(recordedMillis(state, now))
    val ended = state.data?.trip?.endedAt != null
    return when {
        ended -> RecordingState.Saved to "녹음 저장됨 $elapsed"
        state.recordingMode == RecordingMode.ON -> RecordingState.On to "녹음 중 $elapsed"
        state.recordingMode == RecordingMode.PAUSED -> RecordingState.Paused to "녹음 멈춤 $elapsed"
        state.recordingMode == RecordingMode.OFF -> RecordingState.Off to "녹음하지 않는 여행"
        else -> RecordingState.Ready to "녹음 준비됨"
    }
}

/* ───────── S01 홈 ───────── */

fun AppHomeUiState.toV3(): HomeUiState {
    val ongoing = ongoingTrip
    val past = pastTrips.take(2).map {
        PastTripUi(it.id.toString(), it.title, formatShortDate(it.startedAt), toneOf(it.id))
    }
    return HomeUiState(
        driving = safety?.isRestricted == true && ongoing != null,
        drivingGreeting = ongoing?.let { "${it.destination.ifBlank { "목적지" }} 가는 중이에요" } ?: "안전 운전하세요",
        pastTrips = past,
        liveTitle = ongoing?.let { "${it.title} 진행 중" } ?: "",
        liveProgress = percentOf(route),
        pastLocked = if (pastTrips.isEmpty()) "지난 여행은 정차 후 볼 수 있어요" else "지난 여행 ${pastTrips.size}개는 정차 후 볼 수 있어요",
        recordingText = "여행 기록 중",
    )
}

/* ───────── S05–S10 여행 중 ───────── */

fun TripUiState.toDriving(now: Long): DrivingUiState {
    val trip = data?.trip ?: return DrivingStates.S05.copy(asked = emptyList())
    val ribbon = ribbon
    val (rec, recText) = recordingChip(this, now)
    val to = ribbon?.destinationName ?: trip.destination.ifBlank { "목적지" }
    val from = ribbon?.originName ?: trip.startPlace ?: "출발"
    val ended = trip.endedAt != null
    val pillTitle = when {
        ended -> "${to}에 도착했어요"
        ribbon?.remainSeconds != null -> "${to}까지 ${formatElapsed(ribbon.remainSeconds * 1000L)}"
        else -> "$to 가는 중"
    }
    val asked = data.memories
        .filter { it.type == MemoryType.QUESTION && !it.text.isNullOrBlank() }
        .sortedByDescending { it.createdAt }
        .mapIndexed { i, m -> AskedItem(hhmm(m.createdAt), m.text.orEmpty(), now = i == 0 && !quiet) }
    val say = when (recordingMode) {
        RecordingMode.PAUSED -> SAY_PAUSED
        RecordingMode.OFF, RecordingMode.READY -> SAY_OFF
        RecordingMode.ON -> SAY_ON
    }
    val bubble = when (recordingMode) {
        RecordingMode.ON -> "$to 가는 길, 모두의 이야기를 담고 있어요."
        RecordingMode.PAUSED -> "녹음을 잠시 멈췄어요. 질문은 계속할 수 있어요."
        RecordingMode.OFF -> "이번 여행은 녹음 없이 질문만 함께해요."
        RecordingMode.READY -> "출발하면 모두의 이야기를 담기 시작할게요."
    }
    val people = trip.companions.size
    return DrivingUiState(
        tripTitle = trip.title,
        recording = rec,
        recordingText = recText,
        stopped = !safety.isRestricted,
        pillTitle = pillTitle,
        pillSub = listOf(hhmm(trip.startedAt), ribbon?.originName ?: trip.startPlace, "출발").filterNotNull().joinToString(" "),
        people = if (people > 0) "함께 ${people}명" else "",
        progress = if (ended) 100 else percentOf(ribbon?.progress),
        from = from,
        to = to,
        bubble = bubble,
        card = if (quiet) {
            DrivingCard.Quiet("이번 여행에서는 먼저 묻지 않을게요", "이야기가 필요하면 ‘다른 질문 해 줘’라고 말해 주세요.")
        } else {
            DrivingCard.Question(
                question = question,
                say = say,
                tag = "동반이 질문",
                pauseCaption = if (recordingMode == RecordingMode.ON) "녹음 멈춤은 ‘녹음 잠시 멈춰’" else null,
            )
        },
        asked = asked,
        nowText = if (quiet) "조용히 듣는 중" else null,
        toast = voiceFeedback?.toToast(),
    )
}

private fun VoiceFeedback.toToast(): VoiceToastUi = when (kind) {
    VoiceFeedback.Kind.SUCCESS -> VoiceToastUi(ToastKind.Success, "‘$heard’, 알겠어요.", result)
    VoiceFeedback.Kind.NOT_HEARD -> VoiceToastUi(ToastKind.NotHeard, "잘 못 들었어요.", "다시 한 번 말해 주세요. 예: ‘다른 질문 해 줘’")
    VoiceFeedback.Kind.CONFIRM -> VoiceToastUi(ToastKind.Confirm, "‘$heard’로 들었어요.", "잘못 들었다면 이렇게 말해 주세요.", say = "아니야")
}

/* ───────── S14 다이어리 한 장 ───────── */

/** 이야기 한 줄과 그 음성 파일 경로 (재생할 때 쓴다) */
data class TalkWithPath(val talk: TalkUi, val path: String?)

fun TripWithMemories.talks(): List<TalkWithPath> {
    val questions = memories.filter { it.type == MemoryType.QUESTION }.map { it.createdAt }
    return memories.filter { it.type == MemoryType.VOICE }
        .sortedBy { it.createdAt }
        .map { m ->
            // 녹음 시작 10분 안에 동반이 질문이 있었으면 "동반이 질문"에서 나온 이야기로 본다.
            val clipStart = m.createdAt - (m.durationMs ?: 0L)
            val fromQuestion = questions.any { it in (clipStart - 10 * 60_000L)..m.createdAt }
            TalkWithPath(
                TalkUi(
                    time = hhmm(clipStart),
                    title = m.transcript?.takeIf { it.isNotBlank() } ?: "목소리 ${hhmm(clipStart)}",
                    audio = formatDuration(m.durationMs ?: 0L),
                    fromQuestion = fromQuestion,
                ),
                m.filePath,
            )
        }
}

fun TripWithMemories.toRecord(): TripRecordUi {
    val t = trip
    val end = t.endedAt
    return TripRecordUi(
        id = t.id.toString(),
        title = t.title,
        dateLabel = longDate(t.startedAt),
        dateShort = formatShortDate(t.startedAt),
        tone = toneOf(t.id),
        people = t.companions.ifEmpty { listOf("나") },
        relation = "",
        from = t.startPlace ?: "출발",
        startTime = hhmm(t.startedAt),
        to = t.endPlace ?: t.destination.ifBlank { "도착" },
        arriveTime = end?.let { hhmm(it) } ?: "진행 중",
        duration = formatElapsed((end ?: System.currentTimeMillis()) - t.startedAt),
        photos = memories.filter { it.type == MemoryType.PHOTO }.map { hhmm(it.createdAt) },
        talks = talks().map { it.talk },
    )
}

/** 기록 목록 카드에만 쓰는 가벼운 변환 (메모 없이 여행 정보만) */
fun Trip.toRecordCard(): TripRecordUi = TripWithMemories(this, emptyList()).toRecord()

/* ───────── S11 도착 ───────── */

fun TripUiState.toArrival(destinationName: String, now: Long): ArrivalUiState {
    val trip = data?.trip
    val name = destinationName.ifBlank { trip?.endPlace ?: trip?.destination ?: "목적지" }
    val background = toDriving(now).copy(stopped = true, progress = 100, pillTitle = "${name}에 도착했어요")
    return ArrivalUiState(
        title = "${name}에 도착했어요",
        saved = recordingChip(this, now).second.takeIf { recordingMode != RecordingMode.OFF } ?: "녹음하지 않은 여행",
        fromLabel = listOfNotNull(trip?.startPlace ?: "출발", trip?.startedAt?.let { hhmm(it) }).joinToString(" "),
        toLabel = listOfNotNull(name, trip?.endedAt?.let { hhmm(it) }).joinToString(" "),
        background = background,
    )
}

/* ───────── S12 만드는 중 · S13 완성 ───────── */

fun TripUiState.toMaking(step: Int): MakingDiaryUiState {
    val voice = data?.memories.orEmpty().filter { it.type == MemoryType.VOICE }.sumOf { it.durationMs ?: 0L }
    val labels = listOf("녹음 저장", "경로와 시간 정리", "다이어리 꾸미기")
    return MakingDiaryUiState(
        body = "${formatElapsed(voice)}의 녹음과 달린 경로를 한 장에 모으는 중이에요. 다 되면 알려 드릴게요.",
        progress = ((step + 1) * 100 / (labels.size + 1)).coerceAtMost(100),
        steps = labels.mapIndexed { i, label ->
            MakingStep(label, if (i < step) StepState.Done else if (i == step) StepState.Active else StepState.Pending)
        },
    )
}

fun TripUiState.toReady(): DiaryReadyUiState {
    val d = data ?: return DiaryReadyUiState()
    val talks = d.memories.count { it.type == MemoryType.VOICE }
    val photos = d.memories.count { it.type == MemoryType.PHOTO }
    val from = d.trip.startPlace ?: "출발"
    val to = d.trip.endPlace ?: d.trip.destination.ifBlank { "도착" }
    return DiaryReadyUiState(
        title = "${d.trip.title} 다이어리가 완성됐어요",
        body = "이야기 ${talks}개와 사진 ${photos}장, 경로가 한 장에 담겼어요. 정차 중에 펼쳐볼 수 있어요.",
        summary = listOf(
            SummaryCell(SummaryCell.Kind.Talks, "${talks}개", "이야기"),
            SummaryCell(SummaryCell.Kind.Photos, "${photos}장", "사진"),
            SummaryCell(SummaryCell.Kind.Route, "$from → $to", "경로"),
        ),
        tone = toneOf(d.trip.id),
        from = from,
        to = to,
    )
}

/* ───────── R01 기록 지도 ───────── */

fun recordMapState(finished: List<Trip>, ongoing: Trip?, route: NaviRoute?): RecordMapUiState {
    val records = listOfNotNull(ongoing?.toRecordCard()) + finished.sortedByDescending { it.startedAt }.map { it.toRecordCard() }
    return RecordMapUiState(
        records = records,
        live = ongoing != null,
        liveId = ongoing?.id?.toString().orEmpty(),
        liveProgress = percentOf(route),
        subtitle = "함께 달린 길 ${finished.size}개",
        subtitleLive = "함께 달린 길 ${finished.size}개, 지금 달리는 길 1개",
        savedChip = "기록 ${finished.size}개 저장됨",
        recordingText = "여행 기록 중",
    )
}
