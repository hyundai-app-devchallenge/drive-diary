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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.delay
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
import com.dongbantest.app.ui.v3.components.PlaybackBar
import com.dongbantest.app.ui.v3.components.PlaybackUi
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

/**
 * 기록 지도·기록 상세의 지도 : 목록 폭 비율.
 * Full 은 3 : 2. Expanded(약 680dp)에서 3 : 2 면 목록이 260dp 남짓이라 장소 이름·이야기 제목이 꺾이므로 1 : 1.
 */
@Composable
internal fun mapListWeights(): Pair<Float, Float> =
    if (DbTheme.layout == DbLayout.Full) 3f to 2f else 1f to 1f

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
internal fun leadWidth(): Modifier = when (DbTheme.layout) {
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
    val edit: String = "편집",
    val next: String = "다음 장",
    val peopleLabel: String = "함께한 사람",
    val durationLabel: String = "함께 달린 시간",
    val talksTitle: String = "나눈 이야기",
    val fromQuestionTag: String = "동반이 질문",
    val photoPlaceholder: String = "사진 자리",
    val photosTitle: String = "사진",
    val photosMore: String = "더보기",
    val photosLess: String = "접기",
    val playback: PlaybackUi? = null,
    val playbackEnabled: Boolean = true,
    val recordingText: String = Sample.REC_ON,
    val momentHint: String = "지도나 시간 띠에서 이야기를 고르면 그 자리를 함께 보여 줘요.",
    val talksEmpty: String = "이번 여행에선 나눈 이야기가 없어요.",
)

/**
 * S14 다이어리 한 장: 지도·시간 띠·이야기 목록을 한 화면에.
 * 지도 핀, 시간 띠 막대, 목록 줄 어디서 골라도 같은 이야기가 함께 강조되고, 목록 줄에서 바로 재생한다.
 */
@Composable
fun DiaryScreen(
    state: DiaryUiState = DiaryUiState(),
    onTab: (DbTab) -> Unit = {},
    onBack: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onPlay: (TalkUi) -> Unit = {},
    onSeek: (TalkUi, Long) -> Unit = { _, _ -> },
    /** "편집" 버튼. null 이면 비활성(확정안 기본). */
    onEdit: (() -> Unit)? = null,
    /** 지도 칸. null 이면 예시 지도(MomentMap). 앱은 이야기 핀을 얹은 실제 지도(RouteMap)를 넣는다. */
    momentMap: MomentMapSlot? = null,
) {
    MomentDiary(state, onTab, onBack, onNextPage, onPlay, onSeek, onEdit, momentMap)
}

@Composable
internal fun DiaryTopBar(state: DiaryUiState, onBack: () -> Unit, onNextPage: () -> Unit, onEdit: (() -> Unit)? = null) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val r = state.record
    TopBarFrame {
        // 날짜·제목을 맨 위 줄로 올려 아래 칸(사진·이야기) 자리를 넓힌다.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            CircleIconButton(DbIcons.ArrowLeft, "뒤로", onBack)
            Column(Modifier.weight(1f)) {
                Text(r.dateLabel, style = t.caption, color = c.muted, maxLines = 1)
                Text(
                    r.title, style = t.titleLg, color = c.ink, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (state.live) Spacing.xs else Spacing.s2xs)) {
            if (state.live) {
                DbIcon(DbIcons.SquareParking, 16.dp, c.ink, contentDescription = "정차 중")
                RecordingChip(RecordingState.On, state.recordingText, small = true)
            } else {
                DbButton(state.edit, onEdit ?: {}, ButtonKind.Plain, off = onEdit == null)
            }
            // "다음 장" 버튼은 뺐다(기록은 지도에서 골라 연다). onNextPage 는 호출하는 쪽 호환을 위해 남겨 둔다.
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FlowPeople(people: List<String>, modifier: Modifier = Modifier) {
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
    val listTitle: String = "지난 여행",
    val liveDate: String = "지금 여행 중",
    val liveOpen: String = "여행 화면으로",
    val savedChip: String = "기록 3개 저장됨",
    val recordingText: String = Sample.REC_ON,
    /** 지도에서 경로를 고른 카드 아래에 뜨는 버튼 */
    val openRecord: String = "기록 보기",
)

/**
 * 기록 지도 칸. 받은 고른 기록 id 로 그 경로만 진하게 그리고, 경로를 누르면 onSelect(id), 빈 곳은 onSelect(null).
 */
typealias RecordMapSlot = @Composable (modifier: Modifier, selected: String?, onSelect: (String?) -> Unit) -> Unit

@Composable
fun RecordMapScreen(
    state: RecordMapUiState = RecordMapUiState(),
    onTab: (DbTab) -> Unit = {},
    onOpenRecord: (String) -> Unit = {},
    onOpenLiveTrip: () -> Unit = {},
    /** 지도 칸. null 이면 예시 지도(ExampleMap). 앱은 실제 경로 지도(RouteMap)를 넣는다. */
    realMap: RecordMapSlot? = null,
) {
    // 경로나 카드를 누르면 그 기록을 고른다: 지도는 그 경로만 진하게, 목록은 그 카드만 강조하고 아래에 "기록 보기".
    // 같은 것을 다시 누르면 푼다.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val pick: (String?) -> Unit = { id -> selected = if (id == selected) null else id }
    // 받은 폭으로 Compact·Expanded·Full 을 먼저 정한다(아래에서 DbTheme.layout 을 읽음)
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val c = DbTheme.colors
        val t = DbTheme.type
        val layout = DbTheme.layout
        val liveRecord = if (state.live) state.records.firstOrNull { it.id == state.liveId } else null
        val past = if (liveRecord != null) state.records.filter { it.id != liveRecord.id } else state.records
        // 지운 기록이 골라져 있으면 푼다.
        if (selected != null && past.none { it.id == selected }) selected = null
        val map: @Composable (Modifier) -> Unit = { m ->
            if (realMap != null) {
                realMap(m.clip(DbShapes.card), selected, pick)
            } else {
                ExampleMap(
                    routes = past.map { SampleMap.route(it) },
                    labels = SampleMap.labels(live = liveRecord != null),
                    live = liveRecord?.let { SampleMap.LIVE.copy(progress = state.liveProgress / 100f) },
                    liveRoute = liveRecord?.let { SampleMap.route(it) },
                    onOpen = pick,
                    onLive = onOpenLiveTrip,
                    selected = selected,
                    onBlank = { selected = null },
                    endMarkers = false,
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
                    past.forEach { r ->
                        val on = r.id == selected
                        val bring = remember { BringIntoViewRequester() }
                        // 지도에서 고르면 목록이 그 카드(아래 버튼까지)를 보이는 곳으로 굴린다.
                        LaunchedEffect(on) { if (on) { delay(220); bring.bringIntoView() } }
                        RecordCard(
                            r.title, r.dateShort, r.tone, r.from, r.to, { pick(r.id) },
                            Modifier.bringIntoViewRequester(bring),
                            openLabel = "${r.title} 경로 보기",
                            selected = on,
                            dimmed = selected != null && !on,
                            action = { PrimaryButton(state.openRecord, { onOpenRecord(r.id) }) },
                            photo = r.photos.firstOrNull()?.path,
                        )
                    }
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
                // 목록 칸은 고른 카드의 그림자가 스크롤 경계에 잘리지 않게 안쪽 여백(xs)을 두고, 그만큼 칸 사이를 줄인다.
                Row(Modifier.fillMaxSize().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    val (mapW, listW) = mapListWeights()
                    map(Modifier.weight(mapW).fillMaxHeight())
                    Column(
                        Modifier.weight(listW).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(start = Spacing.xs, end = Spacing.xs, bottom = Spacing.md),
                    ) { head(); list() }
                }
            } else {
                head()
                map(Modifier.fillMaxWidth().height(220.dp))
                list()
            }
        }
    }
}
