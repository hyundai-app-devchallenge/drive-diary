package com.dongbantest.app.ui.v3.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.dongbantest.app.gleo.GleoRecordingAction
import com.dongbantest.app.gleo.VoiceCommands
import com.dongbantest.app.ui.v3.components.AppShell
import com.dongbantest.app.ui.v3.components.AskedItem
import com.dongbantest.app.ui.v3.components.AskedPanel
import com.dongbantest.app.ui.v3.components.AskedSummary
import com.dongbantest.app.ui.v3.components.Badge
import com.dongbantest.app.ui.v3.components.ButtonKind
import com.dongbantest.app.ui.v3.components.ButtonRow
import com.dongbantest.app.ui.v3.components.DbBottomSheet
import com.dongbantest.app.ui.v3.components.DbButton
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
import com.dongbantest.app.ui.v3.components.Text
import com.dongbantest.app.ui.v3.components.ToastKind
import com.dongbantest.app.ui.v3.components.TripProgressPill
import com.dongbantest.app.ui.v3.components.VoiceToast
import com.dongbantest.app.ui.v3.components.VoiceToastUi
import com.dongbantest.app.ui.v3.components.quoted
import com.dongbantest.app.ui.v3.theme.AdaptiveLayout
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbLayout
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/* ───────── 주행 화면 공통(S04–S11, B01, Q01·Q02·P01·P02의 배경) ───────── */

/** 질문 카드 자리에 무엇을 보일지: 질문 카드 또는 조용히 듣는 면 */
@Immutable
sealed interface DrivingCard {
    data class Question(
        val question: String,
        val say: List<String>,
        val tag: String = Sample.RELATION,
        val hint: String = Sample.HINT,
        val sayLabel: String = Sample.SAY_LABEL,
        /** 현재 녹음 상태에 맞는 음성 명령 안내. */
        val pauseCaption: String? = null,
    ) : DrivingCard

    data class Quiet(
        val title: String = "대화가 이어지는 동안은 조용히 들을게요",
        val body: String = "대화가 한동안 없으면 동반이가 먼저 말을 걸어요.",
        val pauseHint: String? = "“${GleoRecordingAction.PAUSE.example}”",
    ) : DrivingCard
}

@Immutable
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
    /** 정차 중이고 녹음에 동의한 진행 중 여행에 표시한다. */
    val recordingToggleText: String? = null,
    val recordingToggleEnabled: Boolean = true,
)

/** 주행 중(= 정차가 아님)에 제목·태그를 숨겨야 하는지 */
private fun DrivingUiState.hideTitles() = !stopped && !showTitlesWhileDriving

/** 주행 중에는 최근 3개만, 정차 중에는 전부 (asked 는 최신순) */
private fun DrivingUiState.visibleAsked() = if (stopped) asked else asked.take(3)

/** 주행 화면 이벤트. 음성 예시·녹음 칩·말풍선·진행 알약은 데모 지름길(버튼 모양 없음). */
@Immutable
data class DrivingEvents(
    val onTab: (DbTab) -> Unit = {},
    /** 음성 예시를 누르면 그 말을 한 것으로 본다 */
    val onVoiceExample: (String) -> Unit = {},
    val onRecordingChip: (() -> Unit)? = null,
    val onBubble: (() -> Unit)? = null,
    val onTripPill: (() -> Unit)? = null,
    /** 확인 알림의 "아니야" 버튼 */
    val onToastSay: (String) -> Unit = {},
    val onRecordingToggle: () -> Unit = {},
    /** "여행 끝내기". null 이면 버튼을 그리지 않는다(디자인 카탈로그·미리보기). 정차 중에만 보인다. */
    val onEndTrip: (() -> Unit)? = null,
    val onPlayStory: (String) -> Unit = {},
    val onSeekStory: (String, Long) -> Unit = { _, _ -> },
)

/**
 * 정차 중에만 보이는 여행 조작(녹음 멈추기/다시 녹음하기, 여행 끝내기).
 * 넓은 화면은 위 상태 줄의 녹음 칩 옆, 폰은 카드 아래 한 줄에 둔다. 주행 중에는 하나도 없다.
 */
private class TripControls(
    val toggleText: String?,
    val toggleEnabled: Boolean,
    val onToggle: () -> Unit,
    val onEndTrip: (() -> Unit)?,
) {
    val resume get() = toggleText != null && toggleText != "녹음 멈추기"
    val isEmpty get() = toggleText == null && onEndTrip == null
}

private fun tripControls(state: DrivingUiState, events: DrivingEvents): TripControls? {
    if (!state.stopped) return null
    return TripControls(state.recordingToggleText, state.recordingToggleEnabled, events.onRecordingToggle, events.onEndTrip)
        .takeIf { !it.isEmpty }
}

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
        val controls = tripControls(state, events)
        AppShell(
            selectedTab = DbTab.Trip,
            onTab = events.onTab,
            topBar = {
                DriveStatusBar(
                    state.recording, state.recordingText, state.stopped, onChipTap = events.onRecordingChip,
                    trailing = controls?.takeIf { layout.wide }?.let { { BarControls(it) } },
                )
            },
            scroll = false,
            // 넓은 화면: 위 상태 줄 구분선에 두 열이 붙지 않게 위에도 띄운다.
            bodyPadding = if (layout.wide) PaddingValues(start = Spacing.xl, end = Spacing.xl, top = Spacing.md, bottom = Spacing.md)
            else PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.s2xs),
            phoneSummary = { AskedSummary("${state.askedCountLabel} ${state.asked.size}개", now?.let { "지금: $it" }) },
            phoneToast = toast?.let { { VoiceToast(it, onSay = events.onToastSay) } },
            overlay = overlay,
        ) {
            AdaptiveDrivingBody(state, events, controls)
        }
    }
}

@Composable
private fun AdaptiveDrivingBody(state: DrivingUiState, events: DrivingEvents, controls: TripControls?) {
    val layout = DbTheme.layout
    if (!layout.wide) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            DrivingHeader(state, events)
            DrivingCardSlot(state, events)
            controls?.let { PhoneControls(it) }
            if (state.stopped) AskedPanel(
                state.askedTitle, "${state.asked.size}개", state.asked,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).height(400.dp),
                onPlayStory = events.onPlayStory,
                playback = state.playback,
                onSeekStory = events.onSeekStory,
            )
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        val left = if (layout == DbLayout.Full) Modifier.width(600.dp) else Modifier.weight(1.4f)
        FillBelowColumn(
            modifier = left.fillMaxHeight(),
            // 아래 카드가 남은 높이를 채워 오른쪽 패널과 같은 바닥선에서 끝난다.
            top = { DrivingHeader(state, events) },
            below = { DrivingCardSlot(state, events) },
        )
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

/**
 * 위 칸과 아래 칸을 세로로 놓는다. 높이가 남으면 아래 칸을 늘려 열 바닥까지 채운다.
 * 두 칸이 보이는 높이를 넘을 때만 전체가 스크롤된다.
 */
@Composable
private fun FillBelowColumn(
    modifier: Modifier,
    top: @Composable ColumnScope.() -> Unit,
    below: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxHeight
        Layout(
            contents = listOf(
                { Column(content = top) },
                { Box(propagateMinConstraints = true) { below() } },
            ),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) { (topM, belowM), cs ->
            val w = cs.maxWidth
            val free = Constraints(minWidth = w, maxWidth = w)
            val t = topM.first().measure(free)
            val bounded = viewport != Constraints.Infinity
            val rest = if (bounded) (viewport - t.height).coerceAtLeast(0) else 0
            val b = belowM.first().measure(free.copy(minHeight = rest))
            layout(w, t.height + b.height) {
                t.place(0, 0)
                b.place(0, t.height)
            }
        }
    }
}

/**
 * 왼쪽 열 위쪽: 여행 제목, 진행 알약과 길 리본, 동반이 말풍선.
 * 블록 사이는 12dp(sm)로 맞춘다. 주행·정차 모두 같은 모양이라 기어를 바꿔도 아래가 움직이지 않는다.
 */
@Composable
private fun DrivingHeader(state: DrivingUiState, events: DrivingEvents) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val wide = DbTheme.layout.wide
    // 넓은 화면: 위 12dp 를 띄워 제목 줄을 오른쪽 패널의 "지금까지 나눈 대화" 제목 줄과 같은 높이에 둔다.
    Text(
        state.tripTitle,
        style = if (wide) t.titleLg else t.status,
        color = if (wide) c.ink else c.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = if (wide) Spacing.sm else Spacing.s2xs, bottom = Spacing.sm).semantics { heading() },
    )
    TripProgressPill(
        title = state.pillTitle, sub = state.pillSub, people = state.people, percent = state.progress,
        from = state.from, to = state.to, paused = state.recording == RecordingState.Paused, onTap = events.onTripPill,
    )
    // 질문 카드가 떠 있으면 동반이는 질문으로 말하니 말풍선을 숨기고 그 높이를 카드에 준다.
    // DongbaniBubble 은 안쪽 위 여백(넓은 화면 4dp)이 있어 바깥 8dp 와 합쳐 12dp.
    if (state.card !is DrivingCard.Question) {
        DongbaniBubble(state.bubble, Modifier.padding(top = Spacing.xs), onBubbleTap = events.onBubble)
    }
}

/** 질문 카드 또는 조용히 듣는 면. 카드 안쪽 위 여백 8dp 에 4dp 를 더해 위 블록과 12dp 띄운다. */
@Composable
private fun DrivingCardSlot(state: DrivingUiState, events: DrivingEvents) {
    val gap = Modifier.padding(top = Spacing.s2xs)
    when (val card = state.card) {
        is DrivingCard.Question -> QuestionCard(
            tag = card.tag, question = card.question, hint = card.hint, sayLabel = card.sayLabel, say = card.say,
            modifier = gap,
            pauseCaption = card.pauseCaption,
            density = when (state.toast?.kind) {
                null -> SayDensity.Normal
                ToastKind.Success -> SayDensity.ToastTight
                else -> SayDensity.Toast
            },
            onSay = events.onVoiceExample.takeIf { state.stopped },
        )
        is DrivingCard.Quiet -> QuietFace(card.title, card.body, card.pauseHint, gap)
    }
}

/**
 * 넓은 화면 위 상태 줄의 여행 조작: [여행 끝내기] [녹음 멈추기 | 다시 녹음하기] 다음에 녹음 칩.
 * 녹음 조작을 녹음 상태 칩 바로 옆에 둬서 무엇을 멈추는지 한눈에 보이게 한다.
 */
@Composable
private fun BarControls(controls: TripControls) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        controls.onEndTrip?.let { BarButton(DbIcons.Flag, "여행 끝내기", it) }
        controls.toggleText?.let { text ->
            BarButton(
                icon = if (controls.resume) DbIcons.Mic else DbIcons.Pause,
                text = text,
                onClick = controls.onToggle,
                enabled = controls.toggleEnabled,
                primary = controls.resume,
            )
        }
    }
}

/**
 * 상태 줄 알약 버튼. 보이는 높이는 녹음 칩과 같은 32dp, 누르는 영역은 48dp.
 * primary = 채운 버튼(녹음이 멈춰 있을 때 "다시 녹음하기"), 그 밖에는 테두리 버튼.
 */
@Composable
private fun BarButton(icon: ImageVector, text: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    val c = DbTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fg = if (primary) c.accentInk else c.ink
    val bg = when {
        primary -> if (pressed) c.actionPressed else c.action
        pressed -> c.paper3
        else -> c.paper
    }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(32.dp)
                .clip(DbShapes.pill)
                .background(bg)
                .then(if (primary) Modifier else Modifier.border(1.dp, c.rule, DbShapes.pill))
                .padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2xs),
        ) {
            DbIcon(icon, 16.dp, fg)
            Text(text, style = DbTheme.type.status, color = fg, maxLines = 1)
        }
    }
}

/** 폰 화면: 카드 아래 한 줄에 녹음 조작과 여행 끝내기를 반씩 둔다(상태 줄에 자리가 없다). */
@Composable
private fun PhoneControls(controls: TripControls) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        controls.toggleText?.let { text ->
            DbButton(
                text = text,
                onClick = controls.onToggle,
                kind = if (controls.resume) ButtonKind.Primary else ButtonKind.Outline,
                modifier = Modifier.weight(1f),
                block = true, capWide = false,
                enabled = controls.toggleEnabled,
                icon = if (controls.resume) DbIcons.Mic else DbIcons.Pause,
            )
        }
        controls.onEndTrip?.let {
            DbButton(
                text = "여행 끝내기", onClick = it, kind = ButtonKind.Outline,
                modifier = Modifier.weight(1f), block = true, capWide = false, icon = DbIcons.Flag,
            )
        }
    }
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
        card = DrivingCard.Quiet("이번 여행에서는 먼저 묻지 않을게요", "이야기가 필요하면 ‘${VoiceCommands.ASK_NEXT}’라고 말해 주세요."),
        asked = Sample.ASKED_QUIET, nowText = "조용히 듣는 중",
    )
    val S05 = DrivingUiState()
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
        card = DrivingCard.Question(Sample.Q_FIRST, Sample.SAY_PAUSED, sayLabel = Sample.SAY_LABEL_GLEO,
            pauseCaption = "글레오에게 “${GleoRecordingAction.RESUME.example}”"),
    )
    val S08 = DrivingUiState(toast = Sample.TOAST_NOT_HEARD)
    val S09 = DrivingUiState(
        recording = RecordingState.Off, recordingText = Sample.REC_OFF, bubble = Sample.BUBBLE_OFF,
        card = DrivingCard.Question(Sample.Q_FIRST, Sample.SAY_OFF, sayLabel = Sample.SAY_LABEL_GLEO),
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
    val body: String = "녹음 중에는 동반차가 바로 알아들어요. 녹음을 멈춘 뒤에는 글레오를 부른 다음 말해 주세요.",
    val commands: List<VoiceCommandUi> = listOf(
        VoiceCommandUi(GleoRecordingAction.PAUSE.example, "녹음 중에 말하면 여행 녹음을 잠깐 멈춰요"),
        VoiceCommandUi(GleoRecordingAction.RESUME.example, "글레오를 부른 뒤 말하면 멈춘 녹음을 이어가요"),
        VoiceCommandUi(VoiceCommands.STOP_ASKING, "녹음 중에 말하면 이번 여행에서는 동반이가 먼저 묻지 않아요"),
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
    // 확인은 소리 없이 화면으로만 묻는다(question-suggest PLAN §2). 잠든 가족을 깨우지 않으려고.
    val speaker: String = "자는 분이 깨지 않게 화면으로만 여쭤봐요",
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
                DbIcon(DbIcons.Eye, 16.dp, c.muted)
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
    Box(
        Modifier.size(56.dp).clip(DbShapes.pill).background(c.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        DbIcon(if (stopped) DbIcons.Check else DbIcons.CircleParking, 28.dp, c.accent)
    }
}
