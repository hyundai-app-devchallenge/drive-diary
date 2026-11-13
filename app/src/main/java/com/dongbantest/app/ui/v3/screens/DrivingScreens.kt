package com.dongbantest.app.ui.v3.screens

import com.dongbantest.app.ui.v3.theme.AdaptiveLayout
import com.dongbantest.app.gleo.GleoRecordingAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.dongbantest.app.ui.v3.theme.DbShapes
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.dongbantest.app.ui.v3.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.components.AppShell
import com.dongbantest.app.ui.v3.components.AskedItem
import com.dongbantest.app.ui.v3.components.AskedPanel
import com.dongbantest.app.ui.v3.components.AskedSummary
import com.dongbantest.app.ui.v3.components.Badge
import com.dongbantest.app.ui.v3.components.ButtonRow
import com.dongbantest.app.ui.v3.components.DbBottomSheet
import com.dongbantest.app.ui.v3.components.DbDialog
import com.dongbantest.app.ui.v3.components.DbIcon
import com.dongbantest.app.ui.v3.components.DbTab
import com.dongbantest.app.ui.v3.components.DbTextButton
import com.dongbantest.app.ui.v3.components.DongbaniBubble
import com.dongbantest.app.ui.v3.components.DriveStatusBar
import com.dongbantest.app.ui.v3.components.Hairline
import com.dongbantest.app.ui.v3.components.Mascot
import com.dongbantest.app.ui.v3.components.OutlineButton
import com.dongbantest.app.ui.v3.components.PlaybackUi
import com.dongbantest.app.ui.v3.components.PrimaryButton
import com.dongbantest.app.ui.v3.components.QuestionCard
import com.dongbantest.app.ui.v3.components.QuietFace
import com.dongbantest.app.ui.v3.components.RecordingState
import com.dongbantest.app.ui.v3.components.RoadRibbon
import com.dongbantest.app.ui.v3.components.SayDensity
import com.dongbantest.app.ui.v3.components.SayList
import com.dongbantest.app.ui.v3.components.StateChip
import com.dongbantest.app.ui.v3.components.Tag
import com.dongbantest.app.ui.v3.components.ToastKind
import com.dongbantest.app.ui.v3.components.VoiceToast
import com.dongbantest.app.ui.v3.components.VoiceToastUi
import com.dongbantest.app.ui.v3.components.quoted
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbLayout
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/* ───────── 주행 화면 공통(S04–S11, B01, Q01·Q02·P01·P02의 배경) ───────── */

/** 질문 카드 자리에 무엇을 보일지: 질문 카드 또는 조용히 듣는 면 */
sealed interface DrivingCard {
    data class Question(
        val question: String,
        val say: List<String>,
        val tag: String = Sample.RELATION,
        val hint: String = Sample.HINT,
        val sayLabel: String = Sample.SAY_LABEL,
        /** 현재 녹음 상태에 맞는 Gleo 명령 안내. */
        val pauseCaption: String? = null,
    ) : DrivingCard

    data class Quiet(
        val title: String = "대화가 이어지는 동안은 조용히 들을게요",
        val body: String = "대화가 한동안 없으면 동반이가 먼저 말을 걸어요.",
        val pauseHint: String? = "“${GleoRecordingAction.PAUSE.example}”",
    ) : DrivingCard
}

data class DrivingUiState(
    val tripTitle: String = Sample.TRIP_TITLE,
    val recording: RecordingState = RecordingState.On,
    val recordingText: String = Sample.REC_ON,
    /** 상단 줄 왼쪽: 정차 중(true) / 주행 중 */
    val stopped: Boolean = false,
    val pillTitle: String = "${Sample.TO}까지 ${Sample.REMAINING}",
    val pillSub: String = "${Sample.START} ${Sample.FROM} 출발",
    val people: String = Sample.PEOPLE,
    val progress: Int = Sample.PROGRESS,
    val from: String = Sample.FROM,
    val to: String = Sample.TO,
    val bubble: String = Sample.BUBBLE_DRIVING,
    val card: DrivingCard = DrivingCard.Question(Sample.Q_FIRST, Sample.SAY_ON, pauseCaption = Sample.PAUSE_CAPTION),
    val askedTitle: String = "지금까지 나눈 대화",
    val askedCountLabel: String = "나눈 대화",
    val asked: List<AskedItem> = Sample.ASKED_BASE,
    val playback: PlaybackUi? = null,
    /** 요약 줄 오른쪽 "지금: …". null 이면 asked 의 지금 이야기 중 제목 */
    val nowText: String? = null,
    val footnote: String = "대화 내용은 여행이 끝난 뒤 다이어리로 정리돼요",
    val toast: VoiceToastUi? = null,
    // 설정 "주행 중 제목 보이기"가 꺼져 있으면 주행 중에 제목을 숨긴다.
    val showTitlesWhileDriving: Boolean = true,
    // "이 순간 담기" 버튼: 기록 동의 여행에서 주행 중에만 보인다. 누른 직후 잠깐은 눌림 막음 + 완료 문구.
    val momentButton: Boolean = false,
    val momentDone: Boolean = false,
    /** 정차 중이고 녹음에 동의한 진행 중 여행에 표시한다. */
    val recordingToggleText: String? = null,
    val recordingToggleEnabled: Boolean = true,
)

/** 주행 중(= 정차가 아님)에 제목·태그를 숨겨야 하는지 */
private fun DrivingUiState.hideTitles() = !stopped && !showTitlesWhileDriving

/** 주행 중에는 최근 3개만, 정차 중에는 전부 (asked 는 최신순) */
private fun DrivingUiState.visibleAsked() = if (stopped) asked else asked.take(3)

/** 주행 화면 이벤트. 음성 예시·녹음 칩·말풍선·진행 알약은 데모 지름길(버튼 모양 없음). */
data class DrivingEvents(
    val onTab: (DbTab) -> Unit = {},
    /** 음성 예시를 누르면 그 말을 한 것으로 본다 */
    val onVoiceExample: (String) -> Unit = {},
    val onRecordingChip: (() -> Unit)? = null,
    val onBubble: (() -> Unit)? = null,
    val onTripPill: (() -> Unit)? = null,
    /** 확인 알림의 "아니야" 버튼 */
    val onToastSay: (String) -> Unit = {},
    /** "이 순간 담기" 탭 */
    val onMoment: () -> Unit = {},
    val onRecordingToggle: () -> Unit = {},
    val onPlayStory: (String) -> Unit = {},
    val onSeekStory: (String, Long) -> Unit = { _, _ -> },
)

/** 주행 화면 틀. overlay 로 시트·대화상자를 얹는다. */
@Composable
fun DrivingLayout(
    state: DrivingUiState,
    events: DrivingEvents = DrivingEvents(),
    overlay: (@Composable () -> Unit)? = null,
) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val layout = DbTheme.layout
        val toast = state.toast
        // talk-rec 연동: 제목 숨김이면 "지금: 제목" 줄도 숨긴다(직접 준 nowText 는 유지)
        val now = state.nowText ?: if (state.hideTitles()) null else state.asked.firstOrNull { it.now }?.title
        AppShell(
            selectedTab = DbTab.Trip,
            onTab = events.onTab,
            topBar = { DriveStatusBar(state.recording, state.recordingText, state.stopped, onChipTap = events.onRecordingChip) },
            scroll = false,
            bodyPadding = if (layout.wide) PaddingValues(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.md)
            else PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.s2xs),
            phoneSummary = { AskedSummary("${state.askedCountLabel} ${state.asked.size}개", now?.let { "지금: $it" }) },
            phoneToast = toast?.let { { VoiceToast(it, onSay = events.onToastSay) } },
            overlay = overlay,
        ) {
            AdaptiveDrivingBody(state, events)
        }
    }
}

@Composable
private fun AdaptiveDrivingBody(state: DrivingUiState, events: DrivingEvents) {
    val layout = DbTheme.layout
    if (!layout.wide) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            DrivingLeft(state, events)
            if (state.stopped) AskedPanel(
                state.askedTitle, "${state.asked.size}개", state.asked,
                modifier = Modifier.fillMaxWidth().height(400.dp),
                onPlayStory = events.onPlayStory,
                playback = state.playback,
                onSeekStory = events.onSeekStory,
            )
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        val left = if (layout == DbLayout.Full) Modifier.width(600.dp) else Modifier.weight(1.4f)
        Column(left.fillMaxHeight().verticalScroll(rememberScrollState())) { DrivingLeft(state, events) }
        AskedPanel(
            title = state.askedTitle,
            countText = "${state.asked.size}개",
            items = state.visibleAsked(),
            driving = !state.stopped,
            hideTitles = state.hideTitles(),
            hiddenLabel = state.askedCountLabel,
            onPlayStory = events.onPlayStory,
            playback = state.playback,
            onSeekStory = events.onSeekStory,
            footnote = if (state.toast == null) state.footnote else null,
            bottom = state.toast?.let { t ->
                {
                    VoiceToast(
                        t,
                        Modifier.padding(top = Spacing.lg).then(if (layout == DbLayout.Full) Modifier.widthIn(max = 480.dp) else Modifier),
                        inPanel = true, onSay = events.onToastSay,
                    )
                }
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun DrivingLeft(state: DrivingUiState, events: DrivingEvents) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val wide = DbTheme.layout.wide
    Text(
        state.tripTitle,
        style = if (wide) t.titleLg else t.status,
        color = if (wide) c.ink else c.muted,
        modifier = Modifier.padding(top = if (wide) Spacing.xs else Spacing.s2xs, bottom = Spacing.xs).semantics { heading() },
    )
    TripProgress(state, events)
    DongbaniBubble(state.bubble, onBubbleTap = events.onBubble)
    if (state.momentButton) {
        com.dongbantest.app.ui.v3.components.DbButton(
            text = if (state.momentDone) "담았어요" else "이 순간 담기",
            onClick = events.onMoment,
            kind = com.dongbantest.app.ui.v3.components.ButtonKind.Outline,
            modifier = Modifier.padding(bottom = Spacing.xs).heightIn(min = 76.dp),
            block = true,
            enabled = !state.momentDone,
        )
    }
    state.recordingToggleText?.takeIf { state.stopped }?.let { text ->
        com.dongbantest.app.ui.v3.components.DbButton(
            text = text,
            onClick = events.onRecordingToggle,
            kind = com.dongbantest.app.ui.v3.components.ButtonKind.Primary,
            modifier = Modifier.padding(bottom = Spacing.xs).heightIn(min = 76.dp),
            block = true,
            enabled = state.recordingToggleEnabled,
        )
    }
    when (val card = state.card) {
        is DrivingCard.Question -> QuestionCard(
            tag = card.tag, question = card.question, hint = card.hint, sayLabel = card.sayLabel, say = card.say,
            pauseCaption = card.pauseCaption,
            density = when (state.toast?.kind) {
                null -> SayDensity.Normal
                ToastKind.Success -> SayDensity.ToastTight
                else -> SayDensity.Toast
            },
            onSay = events.onVoiceExample.takeIf { state.stopped },
        )
        is DrivingCard.Quiet -> QuietFace(card.title, card.body, card.pauseHint)
    }
}

@Composable
private fun TripProgress(state: DrivingUiState, events: DrivingEvents) {
    com.dongbantest.app.ui.v3.components.TripProgressPill(
        title = state.pillTitle, sub = state.pillSub, people = state.people, percent = state.progress,
        from = state.from, to = state.to, paused = state.recording == RecordingState.Paused, onTap = events.onTripPill,
    )
}

/* 화면별 기본 상태(웹 DRIVE 표와 같음) */
object DrivingStates {
    val S04 = DrivingUiState(
        recording = RecordingState.Ready, recordingText = Sample.REC_READY, stopped = true,
        pillTitle = "${Sample.TO}까지 ${Sample.TOTAL}", pillSub = "${Sample.FROM}에서 출발해요", progress = 0,
        bubble = Sample.BUBBLE_READY, card = DrivingCard.Question(Sample.Q_FIRST, Sample.SAY_ON), asked = emptyList(),
    )
    val B01 = DrivingUiState(card = DrivingCard.Quiet(), asked = Sample.ASKED_QUIET, nowText = "듣는 중")
    /** Q01 무응답·‘질문 그만’ 뒤의 B01: 먼저 묻지 않는다 */
    val B01_QUIET = DrivingUiState(
        card = DrivingCard.Quiet("이번 여행에서는 먼저 묻지 않을게요", "이야기가 필요하면 ‘다른 질문 해 줘’라고 말해 주세요."),
        asked = Sample.ASKED_QUIET, nowText = "조용히 듣는 중",
    )
    val S05 = DrivingUiState()
    val S05_MOMENT = DrivingUiState(momentButton = true)
    /** Q02 에서 온 S05: 질문 카드와 나눈 대화 첫 줄이 여행지 질문 */
    val S05_SILENCE = DrivingUiState(
        card = DrivingCard.Question(Sample.Q_SILENCE, Sample.SAY_ON, pauseCaption = Sample.PAUSE_CAPTION),
        asked = Sample.ASKED_SILENCE,
    )
    val S06 = DrivingUiState(
        card = DrivingCard.Question(Sample.Q_NEXT, Sample.SAY_ON, pauseCaption = Sample.PAUSE_CAPTION),
        asked = Sample.ASKED_AFTER_NEW, toast = Sample.TOAST_NEW,
    )
    val S07 = DrivingUiState(
        recording = RecordingState.Paused, recordingText = Sample.REC_PAUSED, bubble = Sample.BUBBLE_PAUSED,
        card = DrivingCard.Question(Sample.Q_FIRST, Sample.SAY_PAUSED),
    )
    val S08 = DrivingUiState(toast = Sample.TOAST_NOT_HEARD)
    val S09 = DrivingUiState(
        recording = RecordingState.Off, recordingText = Sample.REC_OFF, bubble = Sample.BUBBLE_OFF,
        card = DrivingCard.Question(Sample.Q_FIRST, Sample.SAY_OFF),
    )
    val S10 = S07.copy(toast = Sample.TOAST_MISHEARD)
    val S11 = DrivingUiState(
        recording = RecordingState.Saved, recordingText = Sample.REC_SAVED, stopped = true,
        pillTitle = "${Sample.TO}에 도착했어요", progress = 100,
        card = DrivingCard.Question(Sample.Q_FIRST, Sample.SAY_ON),
    )
}

/* ───────── S05–S10 여행 중 화면(상태만 다름) ───────── */

/** S05 보기 전용, S06 새 질문 알림, S07 녹음 멈춤, S08 못 들음, S09 녹음 없음, S10 잘못 멈춤 되돌리기 */
@Composable
fun DrivingScreen(state: DrivingUiState = DrivingStates.S05, events: DrivingEvents = DrivingEvents()) {
    DrivingLayout(state, events)
}

/** B01 조용히 듣는 중 */
@Composable
fun ListeningScreen(state: DrivingUiState = DrivingStates.B01, events: DrivingEvents = DrivingEvents()) {
    DrivingLayout(state, events)
}

/* ───────── S04 첫 여행 음성 안내 ───────── */

data class VoiceCommandUi(val say: String, val desc: String)

data class VoiceGuideUiState(
    val title: String = "주행 중에는 말로 불러 주세요",
    val body: String = "글레오를 부르면 화면을 누르지 않고 녹음을 제어할 수 있어요.",
    val commands: List<VoiceCommandUi> = listOf(
        VoiceCommandUi(GleoRecordingAction.PAUSE.example, "여행 녹음을 잠깐 멈춰요"),
        VoiceCommandUi(GleoRecordingAction.RESUME.example, "멈춘 여행 녹음을 이어가요"),
    ),
    val primary: String = "알겠어요, 출발",
    val background: DrivingUiState = DrivingStates.S04,
)

@Composable
fun VoiceGuideScreen(state: VoiceGuideUiState = VoiceGuideUiState(), events: DrivingEvents = DrivingEvents(), onStart: () -> Unit = {}) {
    val c = DbTheme.colors
    val t = DbTheme.type
    DrivingLayout(state.background, events, overlay = {
        DbDialog(state.title) {
            Text(state.title, style = t.titleLg, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(state.body, style = t.body, color = c.ink2)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                state.commands.forEach { cmd ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        DbIcon(DbIcons.Mic, 20.dp, c.muted, Modifier.padding(top = Spacing.s3xs).padding(end = Spacing.s2xs))
                        Column {
                            Text(quoted(cmd.say), style = t.title.copy(lineHeight = t.title.fontSize * 1.4f), color = c.ink)
                            Text(cmd.desc, style = t.bodySm, color = c.muted)
                        }
                    }
                }
            }
            PrimaryButton(state.primary, onStart, capWide = false)
        }
    })
}

/* ───────── Q01 조용함 확인 / Q02 대화를 여는 질문 ───────── */

data class QuietCheckUiState(
    val speaker: String = "운전석 스피커로 작게 물어봤어요",
    val title: String = "혹시 다들 자고 있나요?",
    val body: String = "깨어 있다면 이렇게 말해 보세요.",
    val say: String = "아니, 안 자",
    val wait: String = "대답이 없으면 조용히 돌아갈게요",
    val background: DrivingUiState = DrivingStates.B01,
)

@Composable
fun QuietCheckScreen(state: QuietCheckUiState = QuietCheckUiState(), events: DrivingEvents = DrivingEvents()) {
    val c = DbTheme.colors
    val t = DbTheme.type
    DrivingLayout(state.background, events.copy(onBubble = null), overlay = {
        DbDialog(state.title, alert = true, belowBar = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2xs)) {
                DbIcon(DbIcons.Volume1, 16.dp, c.muted)
                Text(state.speaker, style = t.caption, color = c.muted)
            }
            Mascot(48.dp)
            Text(state.title, style = t.questionPhone, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(state.body, style = t.hint, color = c.muted, modifier = Modifier.offsetUp(Spacing.s2xs))
            SayList(listOf(state.say), events.onVoiceExample, Modifier.offsetUp())
            Text(state.wait, style = t.caption, color = c.muted)
        }
    })
}

data class SilenceQuestionUiState(
    val eyebrow: String = "동반이의 질문",
    val tag: String = Sample.RELATION,
    val question: String = Sample.Q_SILENCE,
    val hint: String = Sample.HINT,
    val sayLabel: String = Sample.SAY_LABEL,
    val say: List<String> = Sample.SAY_ON,
    val background: DrivingUiState = DrivingStates.B01,
)

@Composable
fun SilenceQuestionScreen(state: SilenceQuestionUiState = SilenceQuestionUiState(), events: DrivingEvents = DrivingEvents()) {
    val c = DbTheme.colors
    val t = DbTheme.type
    DrivingLayout(state.background, events.copy(onBubble = null), overlay = {
        DbDialog(state.question, alert = true, belowBar = true) {
            Text(state.eyebrow, style = t.caption.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
            Tag(state.tag)
            Text(state.question, style = t.questionPhone, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(state.hint, style = t.hint, color = c.muted, modifier = Modifier.offsetUp(Spacing.s2xs))
            Hairline(Modifier.padding(top = Spacing.s2xs))
            Text(state.sayLabel, style = t.caption, color = c.muted)
            SayList(state.say, events.onVoiceExample, Modifier.offsetUp())
        }
    })
}

/* ───────── S11 도착 · 여행 종료 ───────── */

data class ArrivalUiState(
    val title: String = "강릉 경포해변에 도착했어요",
    val saved: String = Sample.REC_SAVED,
    val fromLabel: String = "${Sample.FROM} ${Sample.START}",
    val toLabel: String = "${Sample.TO} ${Sample.ARRIVE}",
    val body: String = "여행이 끝났어요. 주행 중에는 다이어리를 만들지 않고, 지금부터 녹음을 정리해서 오늘의 다이어리를 만들게요.",
    val primary: String = "알겠어요",
    val secondary: String = "시간선 보기",
    val notYet: String = "아직 여행 중이에요",
    val notYetHint: String = "도착이 아니라면 녹음은 그대로 이어져요.",
    val background: DrivingUiState = DrivingStates.S11,
)

@Composable
fun ArrivalScreen(
    state: ArrivalUiState = ArrivalUiState(),
    events: DrivingEvents = DrivingEvents(),
    onConfirm: () -> Unit = {},
    onTimeline: () -> Unit = {},
    onNotYet: () -> Unit = {},
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    DrivingLayout(state.background, events, overlay = {
        DbBottomSheet(state.title) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Mascot(40.dp)
                Text(state.title, style = t.titleLg, color = c.ink, modifier = Modifier.semantics { heading() })
                Badge(state.saved, DbIcons.Check)
            }
            RoadRibbon(100, c.accent, state.fromLabel, state.toLabel, Modifier.fillMaxWidth(), car = true)
            Text(state.body, style = t.body, color = c.ink2)
            ButtonRow {
                PrimaryButton(state.primary, onConfirm, Modifier.weight(1f), capWide = false)
                OutlineButton(state.secondary, onTimeline, Modifier.weight(1f), capWide = false)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DbTextButton(state.notYet, onNotYet)
                Text(state.notYetHint, style = t.caption, color = c.muted)
            }
        }
    })
}

/* ───────── P01 · P02 정차 안내 ───────── */

data class StopNoticeUiState(
    /** false = P01 주행 중(정차를 기다림), true = P02 정차됨(이어서 열기) */
    val stopped: Boolean = false,
    val waitTitle: String = "이 기록은 안전하게 정차한 뒤 펼쳐볼 수 있어요.",
    val waitBody: String = "차를 세우면 이어서 열 수 있어요.",
    val drivingState: String = "주행 중",
    val waiting: String = "정차를 기다리는 중",
    val readyTitle: String = "이제 펼쳐볼 수 있어요",
    /** 기록 지도로 갈 때. 지난 여행 다이어리면 "안전하게 정차했어요. 제주 가족 여행 다이어리를 열게요." */
    val readyBody: String = "안전하게 정차했어요. 기록 지도를 열게요.",
    val primary: String = "이어서 열기",
    val close: String = "닫기",
    /** 배경: 여행 화면(S05) 또는 주행 중 홈(drivingHome) */
    val drivingHome: Boolean = false,
    val background: DrivingUiState = DrivingStates.S05,
    val homeBackground: HomeUiState = HomeUiState(driving = true),
)

@Composable
fun StopNoticeScreen(
    state: StopNoticeUiState = StopNoticeUiState(),
    events: DrivingEvents = DrivingEvents(),
    onContinue: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val sheet: @Composable () -> Unit = { StopSheet(state, onContinue, onClose) }
        if (state.drivingHome) {
            AppShell(
                selectedTab = DbTab.Home, onTab = events.onTab,
                topBar = { DriveStatusBar(RecordingState.On, Sample.REC_ON, stopped = state.stopped) },
                verticalArrangement = HomeArrangement(), horizontalAlignment = Alignment.CenterHorizontally,
                overlay = sheet,
            ) {
                Column(MeasureModifier()) { HomeBody(state.homeBackground) }
            }
        } else {
            DrivingLayout(state.background.copy(stopped = state.stopped), events.copy(onRecordingChip = null), overlay = sheet)
        }
    }
}

@Composable
private fun StopSheet(state: StopNoticeUiState, onContinue: () -> Unit, onClose: () -> Unit) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val title = if (state.stopped) state.readyTitle else state.waitTitle
    DbBottomSheet(title) {
        StopIcon(state.stopped)
        Text(title, style = t.titleLg.copy(lineHeight = t.titleLg.fontSize * 1.35f), color = c.ink, modifier = Modifier.semantics { heading() })
        Text(
            if (state.stopped) state.readyBody else state.waitBody,
            style = t.hint, color = c.muted, modifier = Modifier.offsetUp(),
        )
        if (state.stopped) {
            Column(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PrimaryButton(state.primary, onContinue, capWide = false)
                DbTextButton(state.close, onClose, capWide = false)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StateChip(state.drivingState, parked = false)
                Text(state.waiting, style = t.bodySm, color = c.muted)
            }
            DbTextButton(state.close, onClose, Modifier.align(Alignment.CenterHorizontally), block = false)
        }
    }
}

@Composable
private fun StopIcon(stopped: Boolean) {
    val c = DbTheme.colors
    androidx.compose.foundation.layout.Box(
        Modifier.size(56.dp).clip(DbShapes.pill).background(c.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        DbIcon(if (stopped) DbIcons.Check else DbIcons.CircleParking, 28.dp, c.accent)
    }
}
