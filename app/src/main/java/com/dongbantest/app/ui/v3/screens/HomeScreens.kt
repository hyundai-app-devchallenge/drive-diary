package com.dongbantest.app.ui.v3.screens

import com.dongbantest.app.gleo.GleoRecordingAction
import androidx.compose.foundation.layout.fillMaxSize
import com.dongbantest.app.ui.v3.theme.AdaptiveLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dongbantest.app.ui.v3.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import com.dongbantest.app.ui.v3.components.AppShell
import com.dongbantest.app.ui.v3.components.CircleIconButton
import com.dongbantest.app.ui.v3.components.DbBottomSheet
import com.dongbantest.app.ui.v3.components.DbIcon
import com.dongbantest.app.ui.v3.components.DbTab
import com.dongbantest.app.ui.v3.components.DbTextButton
import com.dongbantest.app.ui.v3.components.Dot
import com.dongbantest.app.ui.v3.components.DriveStatusBar
import com.dongbantest.app.ui.v3.components.Mascot
import com.dongbantest.app.ui.v3.components.OutlineButton
import com.dongbantest.app.ui.v3.components.PhotoSlot
import com.dongbantest.app.ui.v3.components.PrimaryButton
import com.dongbantest.app.ui.v3.components.RecordingState
import com.dongbantest.app.ui.v3.components.RoadRibbon
import com.dongbantest.app.ui.v3.components.StoppedTopBar
import com.dongbantest.app.ui.v3.components.floatShadow
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbLayout
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/* ───────── S01 홈 ───────── */

data class PastTripUi(val id: String, val title: String, val date: String, val tone: com.dongbantest.app.ui.v3.components.TripTone)

data class HomeUiState(
    /** 주행 중 홈(보기 전용): 시작 알약 자리에 진행 중 여행 면, 지난 여행은 한 줄로 접는다 */
    val driving: Boolean = false,
    val greeting: String = "어디로 떠나볼까요?",
    val drivingGreeting: String = "강릉 가는 중이에요",
    val startTitle: String = "여행 시작하기",
    val startSub: String = "목적지를 정하고 출발해요",
    val pastTitle: String = "지난 여행",
    val photoPlaceholder: String = "사진 자리",
    val pastTrips: List<PastTripUi> = listOf(Sample.JEJU, Sample.YANGPYEONG).map { PastTripUi(it.id, it.title, it.dateShort, it.tone) },
    val liveTitle: String = "${Sample.TRIP_TITLE} 진행 중",
    val liveOpen: String = "여행 화면으로",
    val liveProgress: Int = Sample.PROGRESS,
    val pastLocked: String = "지난 여행 2개는 정차 후 볼 수 있어요",
    val recordingText: String = Sample.REC_ON,
)

@Composable
fun HomeScreen(
    state: HomeUiState = HomeUiState(),
    onTab: (DbTab) -> Unit = {},
    onStartTrip: () -> Unit = {},
    onOpenPastTrip: (String) -> Unit = {},
    onOpenLiveTrip: () -> Unit = {},
    onPastLocked: () -> Unit = {},
    onSettings: (() -> Unit)? = null,
) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        AppShell(
            selectedTab = DbTab.Home,
            onTab = onTab,
            topBar = {
                if (state.driving) DriveStatusBar(RecordingState.On, state.recordingText, stopped = false)
                else StoppedTopBar(onSettings = onSettings)
            },
            verticalArrangement = HomeArrangement(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(MeasureModifier()) {
                HomeBody(state, onStartTrip, onOpenPastTrip, onOpenLiveTrip, onPastLocked)
            }
        }
    }
}

/** 넓은 화면의 .measure: 최대 560(Full 1040), 가운데 */
@Composable
internal fun MeasureModifier(): Modifier = when (DbTheme.layout) {
    DbLayout.Compact -> Modifier.fillMaxWidth()
    DbLayout.Expanded -> Modifier.widthIn(max = 560.dp).fillMaxWidth()
    DbLayout.Full -> Modifier.widthIn(max = 1040.dp).fillMaxWidth()
}

@Composable
internal fun HomeArrangement(): Arrangement.Vertical =
    if (DbTheme.layout.wide) Arrangement.Center else Arrangement.Top

/** 홈 본문. S02·S03 시트의 배경으로도 쓴다. */
@Composable
fun HomeBody(
    state: HomeUiState,
    onStartTrip: () -> Unit = {},
    onOpenPastTrip: (String) -> Unit = {},
    onOpenLiveTrip: () -> Unit = {},
    onPastLocked: () -> Unit = {},
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val full = DbTheme.layout == DbLayout.Full
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.xs, bottom = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            if (state.driving) state.drivingGreeting else state.greeting,
            style = t.display, color = c.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Mascot(88.dp)
    }
    if (state.driving) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(DbShapes.card).background(c.paper2)
                .clickable(role = Role.Button, onClick = onOpenLiveTrip)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(state.liveTitle, style = t.pill, color = c.ink, modifier = Modifier.weight(1f))
                Text(state.liveOpen, style = t.caption.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
            }
            RoadRibbon(state.liveProgress, c.accent, null, null, car = true)
        }
        Row(
            Modifier.padding(top = Spacing.lg).fillMaxWidth().heightIn(min = 48.dp).clip(DbShapes.button)
                .clickable(role = Role.Button, onClick = onPastLocked).padding(horizontal = Spacing.s2xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            DbIcon(DbIcons.Lock, 16.dp, c.muted)
            Text(state.pastLocked, style = t.status.copy(fontWeight = FontWeight.Medium), color = c.muted)
        }
    } else {
        // 시작 알약: 높이 64, 떠 있는 그림자, 오른쪽 파란 원
        Row(
            Modifier.fillMaxWidth().height(64.dp).floatShadow(DbShapes.pill).clip(DbShapes.pill).background(c.paper)
                .clickable(role = Role.Button, onClick = onStartTrip)
                .padding(start = Spacing.lg, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
                Text(state.startTitle, style = t.title, color = c.ink)
                Text(state.startSub, style = t.caption, color = c.muted)
            }
            Box(Modifier.size(48.dp).clip(DbShapes.pill).background(c.accent), contentAlignment = Alignment.Center) {
                DbIcon(DbIcons.Navigation, 24.dp, c.accentInk)
            }
        }
        Column(Modifier.padding(top = Spacing.xl)) {
            Text(state.pastTitle, style = t.titleLg, color = c.ink, modifier = Modifier.semantics { heading() })
            Row(Modifier.padding(top = Spacing.md).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                state.pastTrips.forEach { p ->
                    Column(
                        Modifier.weight(1f).clip(DbShapes.card).clickable(role = Role.Button) { onOpenPastTrip(p.id) }
                            .semantics(mergeDescendants = true) { contentDescription = "${p.title} 다이어리 열기, ${p.date}" },
                        verticalArrangement = Arrangement.spacedBy(Spacing.s2xs),
                    ) {
                        PhotoSlot(state.photoPlaceholder, aspect = if (full) 16f / 9f else 4f / 3f)
                        // 웹: 제목 줄 위 8 + 제목 글자 위 12(점은 줄 가운데)
                        Row(
                            Modifier.padding(top = Spacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(p.tone.color)
                            Text(p.title, style = t.title, color = c.ink, modifier = Modifier.padding(top = Spacing.sm))
                        }
                        Text(p.date, style = t.bodySm, color = c.muted)
                    }
                }
            }
        }
    }
}

/* ───────── S02 여행 설정 ───────── */

data class TripSetupUiState(
    val title: String = "여행 제목을 지어 주세요",
    val tripTitleLabel: String = "여행 제목",
    val tripTitle: String = "강릉 바다 여행",
    val tripTitlePlaceholder: String = "예: 판교 여행",
    val destinationLabel: String = "목적지",
    /** 내비에 찍힌 목적지 이름. 아직 없으면 null. */
    val naviDestination: String? = "강릉 경포해변",
    val noDestination: String = "내비에서 목적지를 찍으면 여기에 나와요",
    val withLabel: String = "누구와 가요?",
    val companions: List<String> = listOf("가족", "연인", "친구"),
    val selectedCompanion: String = "친구",
    val headcountLabel: String = "인원",
    val headcount: Int = 4,
    val headcountMin: Int = 1,
    val headcountMax: Int = 9,
    val primary: String = "출발! 여행 시작",
    val secondary: String = "나중에",
    val home: HomeUiState = HomeUiState(),
)

@Composable
fun TripSetupScreen(
    state: TripSetupUiState = TripSetupUiState(),
    onTab: (DbTab) -> Unit = {},
    onTripTitleChange: (String) -> Unit = {},
    onCompanionSelect: (String) -> Unit = {},
    onHeadcountChange: (Int) -> Unit = {},
    onStart: () -> Unit = {},
    onLater: () -> Unit = {},
) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val c = DbTheme.colors
        val t = DbTheme.type
        AppShell(
            selectedTab = DbTab.Home, onTab = onTab, topBar = { StoppedTopBar() },
            overlay = {
                DbBottomSheet(state.title) {
                    Text(state.title, style = t.titleLg, color = c.ink, modifier = Modifier.semantics { heading() })
                    // 여행 제목 입력: 높이 56. 여기 쓴 글이 여행 화면의 제목(예: "판교 여행")이 된다.
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).border(1.dp, c.muted, DbShapes.button).clip(DbShapes.button).background(c.paper),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = state.tripTitle,
                            onValueChange = onTripTitleChange,
                            singleLine = true,
                            textStyle = t.body.copy(color = c.ink),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md)
                                .semantics { contentDescription = state.tripTitleLabel },
                            decorationBox = { field ->
                                if (state.tripTitle.isEmpty()) Text(state.tripTitlePlaceholder, style = t.body, color = c.muted)
                                field()
                            },
                        )
                    }
                    // 목적지: 내비에 찍힌 곳을 그대로 보여준다(여기서 고치지 않는다).
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DbIcon(DbIcons.MapPin, 20.dp, if (state.naviDestination != null) c.ink else c.muted)
                        Text(state.destinationLabel, style = t.bodySm, color = c.muted)
                        Text(
                            state.naviDestination ?: state.noDestination,
                            style = if (state.naviDestination != null) t.body.copy(fontWeight = FontWeight.Medium) else t.bodySm,
                            color = if (state.naviDestination != null) c.ink else c.muted,
                        )
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Text(state.withLabel, style = t.title, color = c.ink, modifier = Modifier.padding(bottom = Spacing.sm))
                        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            state.companions.forEach { o ->
                                val on = o == state.selectedCompanion
                                Box(
                                    Modifier.heightIn(min = 48.dp).clip(DbShapes.pill)
                                        .background(if (on) c.ink else c.paper)
                                        .border(1.dp, if (on) c.ink else c.rule, DbShapes.pill)
                                        .selectable(on, role = Role.RadioButton) { onCompanionSelect(o) }
                                        .padding(horizontal = Spacing.lg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(o, style = t.body.copy(fontWeight = FontWeight.Medium), color = if (on) c.paper else c.ink)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.headcountLabel, style = t.title, color = c.ink, modifier = Modifier.weight(1f))
                        CircleIconButton(DbIcons.Minus, "인원 줄이기", { onHeadcountChange(state.headcount - 1) }, enabled = state.headcount > state.headcountMin)
                        Text(
                            "${state.headcount}명", style = t.title, color = c.ink,
                            modifier = Modifier.widthIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        CircleIconButton(DbIcons.Plus, "인원 늘리기", { onHeadcountChange(state.headcount + 1) }, enabled = state.headcount < state.headcountMax)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        PrimaryButton(state.primary, onStart)
                        DbTextButton(state.secondary, onLater)
                    }
                }
            },
        ) {
            HomeBody(state.home)
        }
    }
}

/* ───────── S03 녹음 동의 ───────── */

// talk-rec 연동: 동의를 세 가지로 나눴다(DESIGN §7). 아래 문구는 초안이며 팀에서 확정한다.
//  (1) 여행 중 대화 기록  (2) 전사 글을 외부 AI로 보내 제목·태그 만들기(1을 고른 경우만)  (3) 녹음 없이 출발
data class ConsentUiState(
    val title: String = "이번 여행의 대화를 기록할까요?",
    val subtitle: String = "여행 중 차 안의 대화를 듣다가, 남길 만한 이야기만 저장해요",
    val items: List<String> = listOf(
        "녹음 중에는 화면 위쪽에 항상 표시돼요",
        "“${GleoRecordingAction.PAUSE.example}”라고 말하면 녹음을 멈출 수 있어요",
        "남긴 대화는 정차 후 언제든 지울 수 있어요",
    ),
    /** (1) 여행 중 대화 기록 */
    val recordOption: String = "여행 중 대화 기록",
    val recordOptionSub: String = "상시 기록해요. 남길 것만 저장하고 나머지는 바로 지워요",
    /**
     * (2) 외부 AI 제목·태그 + 대화 질문 (1을 고른 경우에만 켤 수 있음).
     * 기기 안 모델이 1차로 고른 이야기만 외부로 간다는 점을 앞세운다(question-suggest DESIGN §7.1). 최종 문구는 팀과 맞춘다.
     */
    val aiOption: String = "AI로 이야기 제목과 질문 만들기",
    val aiOptionSub: String = "기기 안 모델이 먼저 고른 이야기의 글만 외부 AI로 보내 제목을 붙여요. " +
        "조용할 때 건넬 질문은 대화 없이 목적지·지역 이름·날씨만 보내 만들어요. 음성과 이름은 보내지 않아요",
    /** (3) 녹음 없이 출발 */
    val noRecordOption: String = "녹음 없이 출발",
    val noRecordOptionSub: String = "질문만 함께해요",
    val primaryRecord: String = "대화 기록하고 출발",
    val primaryNoRecord: String = "녹음 없이 출발",
    /** 처음 선택 상태. 외부 AI 전송은 직접 체크해야만 켜진다. */
    val recordSelected: Boolean = true,
    val sendToAi: Boolean = false,
    val home: HomeUiState = HomeUiState(),
)

@Composable
fun ConsentScreen(
    state: ConsentUiState = ConsentUiState(),
    onTab: (DbTab) -> Unit = {},
    /** 출발: record = (1) 선택, sendToAi = (2) 체크(record 가 false 면 항상 false) */
    onStart: (record: Boolean, sendToAi: Boolean) -> Unit = { _, _ -> },
) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val c = DbTheme.colors
        val t = DbTheme.type
        val icons = listOf(DbIcons.Eye, DbIcons.MicOff, DbIcons.Trash2)
        var record by rememberSaveable { mutableStateOf(state.recordSelected) }
        var sendToAi by rememberSaveable { mutableStateOf(state.sendToAi) }
        AppShell(
            selectedTab = DbTab.Home, onTab = onTab, topBar = { StoppedTopBar() },
            overlay = {
                DbBottomSheet(state.title) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Mascot(40.dp)
                        Text(state.title, style = t.titleLg, color = c.ink, modifier = Modifier.semantics { heading() })
                        Text(state.subtitle, style = t.body, color = c.ink2)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        state.items.forEachIndexed { i, s ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                DbIcon(icons[i % icons.size], 24.dp, c.ink, Modifier.padding(top = 1.dp))
                                Text(s, style = t.body, color = c.ink2)
                            }
                        }
                    }
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        ConsentChoice(
                            label = state.recordOption, sub = state.recordOptionSub,
                            selected = record, onClick = { record = true },
                        )
                        // (2) 외부 AI 전송: (1)을 고른 경우에만 켤 수 있다
                        ConsentChoice(
                            label = state.aiOption, sub = state.aiOptionSub,
                            selected = record && sendToAi, enabled = record,
                            checkbox = true, indent = true, onClick = { sendToAi = !sendToAi },
                        )
                        ConsentChoice(
                            label = state.noRecordOption, sub = state.noRecordOptionSub,
                            selected = !record, onClick = { record = false },
                        )
                    }
                    PrimaryButton(
                        if (record) state.primaryRecord else state.primaryNoRecord,
                        { onStart(record, record && sendToAi) },
                    )
                }
            },
        ) {
            HomeBody(state.home)
        }
    }
}

/** S03 선택 줄: 라디오(둥근) 또는 체크박스(네모) 표시 + 제목 + 설명. 높이 48dp 이상. */
@Composable
private fun ConsentChoice(
    label: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    checkbox: Boolean = false,
    indent: Boolean = false,
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val mark = if (checkbox) androidx.compose.foundation.shape.RoundedCornerShape(6.dp) else DbShapes.pill
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) Spacing.lg else 0.dp)
            .heightIn(min = 48.dp)
            .clip(DbShapes.card)
            .border(1.dp, if (selected) c.ink else c.rule, DbShapes.card)
            .then(
                if (checkbox) Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
            )
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp).clip(mark)
                .background(if (selected) c.ink else c.paper)
                .border(1.dp, if (selected) c.ink else c.rule, mark),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) DbIcon(DbIcons.Check, 14.dp, c.paper)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
            Text(label, style = t.body.copy(fontWeight = FontWeight.Medium), color = c.ink.copy(alpha = alpha))
            Text(sub, style = t.bodySm, color = c.muted.copy(alpha = alpha))
        }
    }
}

/* ───────── T01 진행 중인 여행 없음 ───────── */

data class TripEmptyUiState(
    val title: String = "진행 중인 여행이 없어요",
    val body: String = "여행을 시작하면 이곳에서 길 리본과 동반이의 질문을 함께 봐요.",
    val from: String = "출발",
    val to: String = "도착",
    val primary: String = "여행 시작하기",
)

@Composable
fun TripEmptyScreen(state: TripEmptyUiState = TripEmptyUiState(), onTab: (DbTab) -> Unit = {}, onStartTrip: () -> Unit = {}) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val c = DbTheme.colors
        val t = DbTheme.type
        val full = DbTheme.layout == DbLayout.Full
        AppShell(
            selectedTab = DbTab.Trip, onTab = onTab, topBar = { StoppedTopBar() },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = if (full) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Column(
                Modifier.widthIn(max = if (full) 640.dp else 560.dp).fillMaxWidth().padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Mascot(96.dp)
                Text(state.title, style = t.display, color = c.ink, modifier = Modifier.padding(top = Spacing.xs).semantics { heading() })
                Text(state.body, style = t.body, color = c.muted, modifier = Modifier.padding(bottom = 0.dp).offsetUp())
                RoadRibbon(0, c.accent, state.from, state.to, Modifier.padding(vertical = Spacing.xs))
                PrimaryButton(state.primary, onStartTrip, capWide = false)
            }
        }
    }
}

/** CSS margin-top: -8px 처럼 위 간격을 8dp 줄인다. */
internal fun Modifier.offsetUp(dy: androidx.compose.ui.unit.Dp = Spacing.xs): Modifier =
    this.then(Modifier.layoutOffsetUp(dy))

private fun Modifier.layoutOffsetUp(dy: androidx.compose.ui.unit.Dp) = this.layout { m, cs ->
    val p = m.measure(cs)
    val d = dy.roundToPx()
    layout(p.width, (p.height - d).coerceAtLeast(0)) { p.place(0, -d) }
}
