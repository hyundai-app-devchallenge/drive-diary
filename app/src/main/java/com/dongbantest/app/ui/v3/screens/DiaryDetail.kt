package com.dongbantest.app.ui.v3.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import com.dongbantest.app.ui.v3.theme.DbLayout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.components.AppShell
import com.dongbantest.app.ui.v3.components.DbIcon
import com.dongbantest.app.ui.v3.components.DbTab
import com.dongbantest.app.ui.v3.components.MomentKind
import com.dongbantest.app.ui.v3.components.MomentMap
import com.dongbantest.app.ui.v3.components.MomentPin
import com.dongbantest.app.ui.v3.components.MomentTimeline
import com.dongbantest.app.ui.v3.components.OutlineButton
import com.dongbantest.app.ui.v3.components.PhotoSlot
import com.dongbantest.app.ui.v3.components.PlayButton
import com.dongbantest.app.ui.v3.components.PlaybackBar
import com.dongbantest.app.ui.v3.components.Tag
import com.dongbantest.app.ui.v3.components.Text
import com.dongbantest.app.ui.v3.components.topRule
import com.dongbantest.app.ui.v3.theme.AdaptiveLayout
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing
import kotlinx.coroutines.delay

/*
 * S14 다이어리 한 장(탭 없이 하나로).
 * 넓은 화면: 왼쪽 3 = 지도 + 시간 띠, 오른쪽 2 = 제목·함께한 사람·함께 달린 시간 + 나눈 이야기(재생).
 * 좁은 화면: 위에서 아래로 같은 순서.
 * 지도 핀·시간 띠 마커·이야기 줄은 "고른 이야기" 하나를 같이 쓴다. 어디서 골라도 셋 다 강조된다.
 */

/**
 * 다이어리 지도 칸. 받은 핀을 그리고, 핀을 누르면 onSelect(id), 빈 곳은 onSelect(null).
 * 앱은 실제 지도(RouteMap)에 핀을 얹어 넣는다. null 이면 예시 지도(MomentMap).
 */
typealias MomentMapSlot = @Composable (modifier: Modifier, pins: List<MomentPin>, selected: String?, onSelect: (String?) -> Unit) -> Unit

private fun talkKey(t: TalkUi) = t.id.ifEmpty { t.time + t.title }

private fun TripRecordUi.pins(): List<MomentPin> =
    talks.filter { !it.hidden && it.span != null }.map {
        MomentPin(talkKey(it), MomentKind.Talk, it.span!!.start, it.span.endInclusive, it.title)
    }

private fun hhmmToMin(s: String): Int? {
    val p = s.split(":")
    if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    return h * 60 + m
}

/** 출발·도착 사이 정시 눈금. 끝이 "진행 중"이거나 양 끝에 너무 붙으면 뺀다. */
private fun TripRecordUi.hourTicks(): List<Pair<Float, String>> {
    val a = hhmmToMin(startTime) ?: return emptyList()
    var b = hhmmToMin(arriveTime) ?: return emptyList()
    if (b <= a) b += 24 * 60
    val span = (b - a).toFloat()
    return ((a / 60 + 1)..(b / 60)).map { h -> (h * 60 - a) / span to "%02d:00".format(h % 24) }
        .filter { (f, _) -> f in 0.08f..0.92f }
}

@Composable
internal fun MomentDiary(
    state: DiaryUiState,
    onTab: (DbTab) -> Unit,
    onBack: () -> Unit,
    onNextPage: () -> Unit,
    onPlay: (TalkUi) -> Unit,
    onSeek: (TalkUi, Long) -> Unit,
    onEdit: (() -> Unit)?,
    momentMap: MomentMapSlot?,
) {
    val r = state.record
    var selected by remember(r.id) { mutableStateOf<String?>(null) }
    val pins = remember(r) { r.pins() }
    val ticks = remember(r) { r.hourTicks() }
    // 같은 것을 다시 누르면 푼다. 빈 곳(null)은 언제나 푼다.
    val pick: (String?) -> Unit = { id -> selected = if (id == selected) null else id }
    val play: (TalkUi) -> Unit = { talk -> selected = talkKey(talk); onPlay(talk) }
    val map = @Composable { m: Modifier ->
        if (momentMap != null) {
            Box(m.clip(DbShapes.card)) { momentMap(Modifier.fillMaxSize(), pins, selected, pick) }
        } else {
            MomentMap(SampleMap.route(r), SampleMap.labelsFor(r), pins, selected, pick, m)
        }
    }
    val strip = @Composable { m: Modifier ->
        MomentTimeline(pins, selected, pick, "${r.startTime} ${r.from}", "${r.arriveTime} ${r.to}", r.tone.color, m, ticks)
    }
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val wide = DbTheme.layout.wide
        AppShell(
            selectedTab = DbTab.Record, onTab = onTab,
            topBar = { DiaryTopBar(state, onBack, onNextPage, onEdit) },
            scroll = !wide,
        ) {
            if (wide) {
                // 지도 : 이야기 비율은 기록 지도와 같다(mapListWeights).
                val (mapW, listW) = mapListWeights()
                Row(Modifier.fillMaxSize().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    Column(Modifier.weight(mapW).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        map(Modifier.weight(1f).fillMaxWidth())
                        strip(Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(listW).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = Spacing.s2xs)) {
                        DiarySummary(state)
                        DiaryStories(state, selected, pick, play, onSeek, Modifier.padding(top = Spacing.lg))
                    }
                }
            } else {
                DiarySummary(state)
                map(Modifier.padding(top = Spacing.lg).fillMaxWidth().height(280.dp))
                strip(Modifier.padding(top = Spacing.md).fillMaxWidth())
                DiaryStories(state, selected, pick, play, onSeek, Modifier.padding(top = Spacing.xl))
            }
        }
    }
}

/** 날짜·제목, 함께한 사람(이름 칩), 함께 달린 시간 */
@Composable
private fun DiarySummary(state: DiaryUiState) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val r = state.record
    Column(Modifier.padding(top = Spacing.xs)) {
        Text(r.dateLabel, style = t.bodySm.copy(fontWeight = FontWeight.Medium), color = c.muted)
        Text(
            r.title, style = t.display, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.s2xs, bottom = Spacing.md).semantics { heading() },
        )
        FlowPeople(r.people, Modifier.semantics { contentDescription = "${state.peopleLabel}: ${r.people.joinToString()}" })
        Row(
            Modifier.padding(top = Spacing.md).fillMaxWidth().clip(DbShapes.card).background(c.paper2)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            DbIcon(DbIcons.Route, 22.dp, c.muted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
                Text(state.durationLabel, style = t.caption, color = c.muted)
                Text(r.duration, style = t.titleLg, color = c.ink)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
                Text("${r.startTime} 출발", style = t.bodySm, color = c.ink2)
                Text(if (state.live) r.arriveTime else "${r.arriveTime} 도착", style = t.bodySm, color = c.ink2)
            }
        }
    }
}

/** 나눈 이야기: 고르면 지도·시간 띠도 같이 강조되고, 줄에서 바로 재생·되감기 한다. */
@Composable
private fun DiaryStories(
    state: DiaryUiState,
    selected: String?,
    onPick: (String?) -> Unit,
    onPlay: (TalkUi) -> Unit,
    onSeek: (TalkUi, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val r = state.record
    var hiddenExpanded by remember(r.id) { mutableStateOf(false) }
    val visible = r.talks.filter { !it.hidden }
    val hidden = r.talks.filter { it.hidden }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(state.talksTitle, style = t.titleLg, color = c.ink, modifier = Modifier.semantics { heading() })
            if (visible.isNotEmpty()) Text("${visible.size}", style = t.titleLg, color = c.muted)
        }
        if (visible.isEmpty()) {
            Text(state.talksEmpty, style = t.body, color = c.ink2, modifier = Modifier.padding(top = Spacing.sm))
        } else if (selected == null) {
            Text(state.momentHint, style = t.bodySm, color = c.muted, modifier = Modifier.padding(top = Spacing.s2xs))
        }
        Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.s2xs)) {
            visible.forEach { talk -> StoryRow(state, talk, talkKey(talk) == selected, onPick, onPlay, onSeek) }
            if (hidden.isNotEmpty()) {
                if (hiddenExpanded) {
                    hidden.forEach { talk -> StoryRow(state, talk, talkKey(talk) == selected, onPick, onPlay, onSeek) }
                    OutlineButton("숨긴 대화 ${hidden.size}개 접기", { hiddenExpanded = false })
                } else {
                    OutlineButton("숨긴 대화 ${hidden.size}개", { hiddenExpanded = true })
                }
            }
        }
        if (r.photos.isNotEmpty()) {
            Row(Modifier.padding(top = Spacing.lg).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                r.photos.forEach { cap ->
                    Column(Modifier.weight(1f)) {
                        PhotoSlot(state.photoPlaceholder, tile = true)
                        Text(cap, style = t.bodySm, color = c.muted, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryRow(
    state: DiaryUiState,
    talk: TalkUi,
    on: Boolean,
    onPick: (String?) -> Unit,
    onPlay: (TalkUi) -> Unit,
    onSeek: (TalkUi, Long) -> Unit,
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val tone = state.record.tone.color
    val active = state.playback?.takeIf { state.playbackEnabled && it.id == talk.id }
    val bring = remember { BringIntoViewRequester() }
    // 지도·시간 띠에서 고르면 목록이 그 줄을 보이는 곳으로 굴린다.
    LaunchedEffect(on) { if (on) { delay(120); bring.bringIntoView() } }
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .clip(DbShapes.button)
            .background(if (on) c.paper2 else Color.Transparent)
            .then(if (on) Modifier else Modifier.topRule(c.ruleSoft))
            .heightIn(min = 64.dp)
            .selectable(on, role = Role.Button) { onPick(talkKey(talk)) }
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 중간 폭(Expanded)은 오른쪽 칸이 좁아 시각을 따로 세우지 않고 아랫줄에 붙인다.
        val narrow = DbTheme.layout == DbLayout.Expanded
        if (!narrow) {
            Text(talk.time, style = t.bodySm, color = c.muted, modifier = Modifier.width(48.dp).align(Alignment.Top).padding(top = Spacing.s3xs))
        }
        // 기록 색 점. 말풍선 아이콘은 지도 핀에만 쓰고, 목록·시간 띠는 색으로만 이어 준다.
        // 제목 첫 줄(16sp × 1.3 ≈ 21dp) 가운데에 맞춘다.
        Box(Modifier.align(Alignment.Top).padding(top = 5.dp).size(10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(if (on) 10.dp else 8.dp).clip(DbShapes.pill)
                    .background(tone.copy(alpha = if (talk.hidden) 0.4f else 1f)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
            Text(
                talk.title, style = t.title.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold), color = c.ink,
                maxLines = if (on || narrow) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
            if (active != null && active.durationMs > 0) {
                PlaybackBar(active) { onSeek(talk, it) }
            } else {
                Text(
                    if (narrow) "${talk.time} · 음성 ${talk.audio}" else "음성 ${talk.audio} · 클립 ${talk.clipCount}개",
                    style = t.bodySm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (on && (talk.fromQuestion || talk.tags.isNotEmpty())) {
                FlowRow(
                    Modifier.padding(top = Spacing.s3xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    if (talk.fromQuestion) Tag(state.fromQuestionTag, small = true)
                    talk.tags.take(3).forEachIndexed { i, tag -> Tag(tag, small = true, category = i == 0 && tag == talk.category) }
                }
            }
        }
        if (state.playbackEnabled) {
            val playing = active?.isPlaying == true
            PlayButton(playing, "${talk.title} ${if (playing) "일시정지" else "재생"}", { onPlay(talk) }, Modifier.align(Alignment.Top), compact = true)
        }
    }
}
