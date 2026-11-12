package com.dongbantest.app.ui.v3.screens

import com.dongbantest.app.ui.v3.theme.AdaptiveLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.dongbantest.app.ui.v3.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.components.AppShell
import com.dongbantest.app.ui.v3.components.ButtonKind
import com.dongbantest.app.ui.v3.components.CircleIconButton
import com.dongbantest.app.ui.v3.components.DbButton
import com.dongbantest.app.ui.v3.components.DbIcon
import com.dongbantest.app.ui.v3.components.DbTab
import com.dongbantest.app.ui.v3.components.DriveStatusBar
import com.dongbantest.app.ui.v3.components.ExampleMap
import com.dongbantest.app.ui.v3.components.Mascot
import com.dongbantest.app.ui.v3.components.OutlineButton
import com.dongbantest.app.ui.v3.components.PhotoSlot
import com.dongbantest.app.ui.v3.components.PlayButton
import com.dongbantest.app.ui.v3.components.PrimaryButton
import com.dongbantest.app.ui.v3.components.RecordCard
import com.dongbantest.app.ui.v3.components.RecordingChip
import com.dongbantest.app.ui.v3.components.RecordingState
import com.dongbantest.app.ui.v3.components.RoadRibbon
import com.dongbantest.app.ui.v3.components.StoppedTopBar
import com.dongbantest.app.ui.v3.components.Tag
import com.dongbantest.app.ui.v3.components.TopBarFrame
import com.dongbantest.app.ui.v3.components.TripTone
import com.dongbantest.app.ui.v3.components.topRule
import com.dongbantest.app.ui.v3.components.bottomRule
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbLayout
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/* ───────── S12 다이어리 만드는 중 ───────── */

enum class StepState { Done, Active, Pending }
data class MakingStep(val label: String, val state: StepState)

data class MakingDiaryUiState(
    val title: String = "오늘의 다이어리를 만들고 있어요",
    val body: String = "3시간 10분의 녹음을 글로 옮기고, 경로와 사진을 한 장에 모으는 중이에요. 다 되면 알려 드릴게요.",
    val progress: Int = 60,
    val steps: List<MakingStep> = listOf(
        MakingStep("녹음 저장", StepState.Done),
        MakingStep("대화를 글로 옮기는 중", StepState.Active),
        MakingStep("다이어리 꾸미기", StepState.Pending),
    ),
)

/** 넓은 화면 .lead.measure: 560(Full 640) 가운데 */
@Composable
private fun leadWidth(): Modifier = when (DbTheme.layout) {
    DbLayout.Compact -> Modifier.fillMaxWidth()
    DbLayout.Expanded -> Modifier.widthIn(max = 560.dp).fillMaxWidth()
    DbLayout.Full -> Modifier.widthIn(max = 640.dp).fillMaxWidth()
}

@Composable
fun MakingDiaryScreen(state: MakingDiaryUiState = MakingDiaryUiState(), onTab: (DbTab) -> Unit = {}) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val c = DbTheme.colors
        val t = DbTheme.type
        AppShell(
            selectedTab = DbTab.Trip, onTab = onTab, topBar = { StoppedTopBar() },
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(leadWidth().padding(top = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Mascot(56.dp)
                Text(state.title, style = t.display, color = c.ink, modifier = Modifier.semantics { heading() })
                Text(state.body, style = t.body, color = c.ink2)
                Box(
                    Modifier.fillMaxWidth().height(6.dp).clip(DbShapes.pill).background(c.ruleSoft)
                        .semantics { contentDescription = "다이어리 만드는 진행률 ${state.progress}%" },
                ) {
                    Box(Modifier.fillMaxWidth(state.progress / 100f).height(6.dp).clip(DbShapes.pill).background(c.accent))
                }
                Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    state.steps.forEach { s ->
                        val fg = if (s.state == StepState.Pending) c.muted else c.ink
                        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            DbIcon(
                                when (s.state) { StepState.Done -> DbIcons.CircleCheck; StepState.Active -> DbIcons.LoaderCircle; StepState.Pending -> DbIcons.Circle },
                                24.dp, fg,
                            )
                            Text(s.label, style = t.body.copy(fontWeight = if (s.state == StepState.Active) FontWeight.SemiBold else FontWeight.Normal), color = fg)
                        }
                    }
                }
            }
        }
    }
}

/* ───────── S13 다이어리 완성 ───────── */

data class SummaryCell(val kind: Kind, val value: String, val label: String) {
    enum class Kind { Talks, Photos, Route }
}

data class DiaryReadyUiState(
    val title: String = "강릉 바다 여행 다이어리가 완성됐어요",
    val body: String = "이야기 3개와 사진 자리 2곳, 경로가 한 장에 담겼어요. 정차 중에 펼쳐보고 꾸밀 수 있어요.",
    val summary: List<SummaryCell> = listOf(
        SummaryCell(SummaryCell.Kind.Talks, "3개", "이야기"),
        SummaryCell(SummaryCell.Kind.Photos, "2곳", "사진 자리"),
        SummaryCell(SummaryCell.Kind.Route, "${Sample.FROM} → ${Sample.TO}", "경로"),
    ),
    val tone: TripTone = TripTone.Gangneung,
    val from: String = Sample.FROM,
    val to: String = Sample.TO,
    val primary: String = "다이어리 펼치기",
    val secondary: String = "나중에 볼게요",
)

@Composable
fun DiaryReadyScreen(
    state: DiaryReadyUiState = DiaryReadyUiState(),
    onTab: (DbTab) -> Unit = {},
    onOpenDiary: () -> Unit = {},
    onLater: () -> Unit = {},
) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val c = DbTheme.colors
        val t = DbTheme.type
        val wide = DbTheme.layout.wide
        AppShell(
            selectedTab = DbTab.Trip, onTab = onTab, topBar = { StoppedTopBar() },
            verticalArrangement = if (wide) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                leadWidth().then(if (wide) Modifier else Modifier.weight(1f)).padding(top = if (wide) Spacing.xl else Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Mascot(if (wide) 56.dp else 40.dp)
                Text(state.title, style = t.display, color = c.ink, modifier = Modifier.then(if (wide) Modifier else Modifier.offsetUp()).semantics { heading() })
                Text(state.body, style = t.body, color = c.ink2)
                SummaryRow(state.summary)
                RoadRibbon(100, state.tone.color, state.from, state.to, Modifier.fillMaxWidth().then(if (wide) Modifier else Modifier.offsetUp()))
                if (!wide) Spacer(Modifier.weight(1f))
                Column(
                    Modifier.padding(top = if (wide) 0.dp else Spacing.s2xs).then(if (wide) Modifier.widthIn(max = 480.dp) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    PrimaryButton(state.primary, onOpenDiary)
                    OutlineButton(state.secondary, onLater)
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(cells: List<SummaryCell>) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val compact = !DbTheme.layout.wide
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).topRule(c.rule).bottomRule(c.rule),
    ) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.rule))
            Column(
                Modifier.weight(1f).padding(
                    start = if (i > 0) Spacing.sm else 0.dp, end = Spacing.sm,
                    top = if (compact) 6.dp else Spacing.sm, bottom = if (compact) 6.dp else Spacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2xs),
            ) {
                DbIcon(
                    when (cell.kind) { SummaryCell.Kind.Talks -> DbIcons.MessageCircle; SummaryCell.Kind.Photos -> DbIcons.Image; SummaryCell.Kind.Route -> DbIcons.Route },
                    20.dp, c.muted,
                )
                Text(cell.value, style = t.title, color = c.ink)
                Text(cell.label, style = t.caption, color = c.muted)
            }
        }
    }
}

/* ───────── S14 다이어리 ───────── */

data class DiaryUiState(
    val record: TripRecordUi = Sample.GANGNEUNG,
    /** 정차 중이지만 여행이 아직 진행 중(P02 이어서 열기 뒤): 머리줄에 정차 아이콘 + 작은 녹음 칩, 편집 숨김 */
    val live: Boolean = false,
    val title: String = "다이어리",
    val edit: String = "편집",
    val next: String = "다음 장",
    val peopleLabel: String = "함께한 사람",
    val durationLabel: String = "함께 달린 시간",
    val talksTitle: String = "나눈 이야기",
    val fromQuestionTag: String = "동반이 질문",
    val photoPlaceholder: String = "사진 자리",
    /** 재생 중인 이야기(time). 진행 표시는 시연용 30% 고정 */
    val playingTime: String? = null,
    val recordingText: String = Sample.REC_ON,
)

@Composable
fun DiaryScreen(
    state: DiaryUiState = DiaryUiState(),
    onTab: (DbTab) -> Unit = {},
    onBack: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onPlay: (TalkUi) -> Unit = {},
    /** 지도 칸. null 이면 예시 지도(ExampleMap). 팀 앱은 실제 경로 지도(MapLibre)를 넣는다. */
    map: (@Composable (Modifier) -> Unit)? = null,
) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val layout = DbTheme.layout
        AppShell(
            selectedTab = DbTab.Record, onTab = onTab,
            topBar = { DiaryTopBar(state, onBack, onNextPage) },
            scroll = !layout.wide,
        ) {
            if (layout.wide) {
                Row(Modifier.fillMaxSize().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    val left = if (layout == DbLayout.Full) Modifier.width(560.dp) else Modifier.weight(1f)
                    Column(left.fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = Spacing.s2xs)) { DiaryInfo(state, map) }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(DbShapes.card).border(1.dp, DbTheme.colors.rule, DbShapes.card)
                            .background(DbTheme.colors.paper).verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    ) { DiaryTalks(state, onPlay) }
                }
            } else {
                DiaryInfo(state, map)
                Column(Modifier.padding(top = Spacing.xl)) { DiaryTalks(state, onPlay) }
            }
        }
    }
}

@Composable
private fun DiaryTopBar(state: DiaryUiState, onBack: () -> Unit, onNextPage: () -> Unit) {
    val c = DbTheme.colors
    TopBarFrame {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            CircleIconButton(DbIcons.ArrowLeft, "뒤로", onBack)
            Text(state.title, style = DbTheme.type.title, color = c.ink, modifier = Modifier.semantics { heading() })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (state.live) Spacing.xs else Spacing.s2xs)) {
            if (state.live) {
                DbIcon(DbIcons.SquareParking, 16.dp, c.ink, contentDescription = "정차 중")
                RecordingChip(RecordingState.On, state.recordingText, small = true)
            } else {
                DbButton(state.edit, {}, ButtonKind.Plain, off = true)
            }
            DbButton(state.next, onNextPage, ButtonKind.Plain)
        }
    }
}

@Composable
private fun ColumnScope.DiaryInfo(state: DiaryUiState, map: (@Composable (Modifier) -> Unit)? = null) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val r = state.record
    Text(r.dateLabel, style = t.bodySm.copy(fontWeight = FontWeight.Medium, lineHeight = t.bodySm.fontSize * 1.4f), color = c.muted, modifier = Modifier.padding(top = Spacing.xs))
    Text(r.title, style = t.display, color = c.ink, modifier = Modifier.padding(top = Spacing.s2xs, bottom = Spacing.md).semantics { heading() })
    FlowPeople(r.people, Modifier.semantics { contentDescription = "${state.peopleLabel}: ${r.people.joinToString()}" })
    val mapModifier = Modifier.padding(top = Spacing.md).fillMaxWidth().height(if (map != null) 200.dp else 120.dp).clip(DbShapes.card)
    if (map != null) {
        map(mapModifier)
    } else {
        ExampleMap(
            routes = listOf(SampleMap.route(r)),
            labels = SampleMap.labelsFor(r),
            modifier = mapModifier,
        )
    }
    Column(Modifier.padding(top = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.s2xs)) {
        Text(state.durationLabel, style = t.caption, color = c.muted)
        Text(r.duration, style = t.display.copy(lineHeight = t.display.fontSize * 1.2f), color = c.ink, modifier = Modifier.padding(bottom = Spacing.xs))
        RoadRibbon(100, r.tone.color, "${r.from} ${r.startTime}", "${r.to} ${r.arriveTime}", Modifier.fillMaxWidth())
    }
    Row(Modifier.padding(top = Spacing.lg).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        r.photos.forEach { cap ->
            Column(Modifier.weight(1f)) {
                PhotoSlot(state.photoPlaceholder, tile = true)
                Text(cap, style = t.bodySm, color = c.muted, modifier = Modifier.padding(top = Spacing.xs))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowPeople(people: List<String>, modifier: Modifier = Modifier) {
    val c = DbTheme.colors
    val t = DbTheme.type
    androidx.compose.foundation.layout.FlowRow(
        modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        people.forEach { p ->
            Row(
                Modifier.height(40.dp).clip(DbShapes.pill).border(1.dp, c.rule, DbShapes.pill)
                    .padding(start = if (p.length == 1) 3.dp else Spacing.s2xs, end = if (p.length == 1) 3.dp else Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Box(Modifier.size(32.dp).clip(DbShapes.pill).background(c.paper3), contentAlignment = Alignment.Center) {
                    Text(p.take(1), style = t.title, color = c.ink)
                }
                if (p.length > 1) Text(p, style = t.bodySm.copy(fontWeight = FontWeight.Medium), color = c.ink)
            }
        }
    }
}

private fun elapsed30(audio: String): String {
    val (m, s) = audio.split(":").map { it.toInt() }
    val sec = ((m * 60 + s) * 0.3).toInt()
    return "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
}

@Composable
private fun DiaryTalks(state: DiaryUiState, onPlay: (TalkUi) -> Unit) {
    val c = DbTheme.colors
    val t = DbTheme.type
    Text(state.talksTitle, style = t.titleLg, color = c.ink, modifier = Modifier.padding(bottom = Spacing.xs).semantics { heading() })
    state.record.talks.forEach { talk ->
        val on = state.playingTime == talk.time
        Row(
            Modifier.fillMaxWidth().topRule(if (DbTheme.layout.wide) c.ruleSoft else c.ruleSoft).padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(talk.time, style = t.bodySm, color = c.muted, modifier = Modifier.width(52.dp).align(Alignment.Top).padding(top = Spacing.s3xs))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
                Text(talk.title, style = t.title, color = c.ink)
                if (on) {
                    Box(Modifier.padding(top = Spacing.s2xs).fillMaxWidth().height(3.dp).clip(DbShapes.pill).background(c.ruleSoft)) {
                        Box(Modifier.fillMaxWidth(0.3f).height(3.dp).background(c.accent))
                    }
                    Text("${elapsed30(talk.audio)} / ${talk.audio}", style = t.caption.copy(fontWeight = FontWeight.Normal), color = c.ink2)
                } else {
                    Text("음성 ${talk.audio}", style = t.bodySm, color = c.muted)
                }
                if (talk.fromQuestion) Tag(state.fromQuestionTag, small = true)
            }
            PlayButton(on, "${talk.title} 재생", { onPlay(talk) })
        }
    }
}

/* ───────── R01 기록 지도 ───────── */

data class RecordMapUiState(
    val records: List<TripRecordUi> = Sample.RECORDS,
    /** 진행 중인 여행이 있음(P02 이어서 열기 뒤): 강릉을 목록 맨 위 진행 중 카드와 지도 점선 경로로 */
    val live: Boolean = false,
    val liveId: String = Sample.GANGNEUNG.id,
    val liveProgress: Int = Sample.PROGRESS,
    val title: String = "기록",
    val subtitle: String = "함께 달린 길 3개",
    val subtitleLive: String = "함께 달린 길 2개, 지금 달리는 길 1개",
    val listTitle: String = "지난 여행 다이어리",
    val liveDate: String = "지금 여행 중",
    val liveOpen: String = "여행 화면으로",
    val savedChip: String = "기록 3개 저장됨",
    val recordingText: String = Sample.REC_ON,
)

@Composable
fun RecordMapScreen(
    state: RecordMapUiState = RecordMapUiState(),
    onTab: (DbTab) -> Unit = {},
    onOpenRecord: (String) -> Unit = {},
    onOpenLiveTrip: () -> Unit = {},
    /** 지도 칸. null 이면 예시 지도(ExampleMap). 팀 앱은 실제 경로 지도(MapLibre)를 넣는다. */
    realMap: (@Composable (Modifier) -> Unit)? = null,
) {
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val c = DbTheme.colors
        val t = DbTheme.type
        val layout = DbTheme.layout
        val liveRecord = if (state.live) state.records.firstOrNull { it.id == state.liveId } else null
        val past = if (liveRecord != null) state.records.filter { it.id != liveRecord.id } else state.records
        val map: @Composable (Modifier) -> Unit = { m ->
            if (realMap != null) {
                realMap(m.clip(DbShapes.card))
            } else {
                ExampleMap(
                    routes = past.map { SampleMap.route(it) },
                    labels = SampleMap.labels(live = liveRecord != null),
                    live = liveRecord?.let { SampleMap.LIVE.copy(progress = state.liveProgress / 100f) },
                    liveRoute = liveRecord?.let { SampleMap.route(it) },
                    onOpen = onOpenRecord,
                    onLive = onOpenLiveTrip,
                    modifier = m,
                )
            }
        }
        val head = @Composable {
            Column(Modifier.padding(top = if (layout.wide) 0.dp else Spacing.xs, bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
                Text(state.title, style = t.display, color = c.ink, modifier = Modifier.semantics { heading() })
                Text(if (liveRecord != null) state.subtitleLive else state.subtitle, style = t.bodySm, color = c.muted)
            }
        }
        val list = @Composable {
            liveRecord?.let { r ->
                RecordCard(
                    r.title, state.liveDate, r.tone, r.from, r.to, onOpenLiveTrip,
                    Modifier.padding(top = if (layout.wide) Spacing.md else Spacing.lg),
                    openLabel = "${r.title} 여행 화면으로", live = true, livePercent = state.liveProgress, liveOpenText = state.liveOpen,
                )
            }
            Column(Modifier.padding(top = if (layout.wide) Spacing.md else Spacing.lg)) {
                Text(state.listTitle, style = t.title, color = c.ink, modifier = Modifier.semantics { heading() })
                Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    past.forEach { r -> RecordCard(r.title, r.dateShort, r.tone, r.from, r.to, { onOpenRecord(r.id) }) }
                }
            }
        }
        AppShell(
            selectedTab = DbTab.Record, onTab = onTab,
            topBar = {
                if (liveRecord != null) DriveStatusBar(RecordingState.On, state.recordingText, stopped = true)
                else DriveStatusBar(RecordingState.Saved, state.savedChip, stopped = true)
            },
            scroll = !layout.wide,
        ) {
            if (layout.wide) {
                Row(Modifier.fillMaxSize().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    if (layout == DbLayout.Full) {
                        map(Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { head(); list() }
                    } else {
                        map(Modifier.weight(58f).fillMaxHeight())
                        Column(Modifier.weight(42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = Spacing.s2xs)) { head(); list() }
                    }
                }
            } else {
                head()
                map(Modifier.fillMaxWidth().height(220.dp))
                list()
            }
        }
    }
}
