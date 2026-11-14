package com.dongbantest.app.ui.v3.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.components.AppShell
import com.dongbantest.app.ui.v3.components.DbIcon
import com.dongbantest.app.ui.v3.components.DbTab
import com.dongbantest.app.ui.v3.components.MomentIcons
import com.dongbantest.app.ui.v3.components.MomentKind
import com.dongbantest.app.ui.v3.components.MomentMap
import com.dongbantest.app.ui.v3.components.MomentPin
import com.dongbantest.app.ui.v3.components.MomentTimeline
import com.dongbantest.app.ui.v3.components.OutlineButton
import com.dongbantest.app.ui.v3.components.PlayButton
import com.dongbantest.app.ui.v3.components.PlaybackBar
import com.dongbantest.app.ui.v3.components.Tag
import com.dongbantest.app.ui.v3.components.Text
import com.dongbantest.app.ui.v3.components.topRule
import com.dongbantest.app.ui.v3.theme.AdaptiveLayout
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbLayout
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/*
 * S14 다이어리 — 탭 모드(M00 설계 4장).
 * 지도(기본): 경로 위 이야기 핀·노래 핀, 시간 띠, 고른 순간 카드, 시간순 목록.
 * 경로·이야기: 지금 한 장 화면의 두 부분 그대로. 노래: 들은 노래 목록.
 * 지도·띠·목록·카드는 "고른 순간" 하나를 같이 쓴다.
 */

private fun talkKey(t: TalkUi) = "talk:" + t.id.ifEmpty { t.time + t.title }
private fun songKey(s: SongUi) = "song:" + s.id

/** 빈 칸은 빼고 " · " 로 잇는다(가수 정보가 없는 곡) */
internal fun dotJoin(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" · ")

/** "제목 · 가수" */
internal val SongUi.label: String get() = dotJoin(title, artist)

/**
 * 지도 탭의 지도 칸. 받은 핀을 그리고, 핀을 누르면 onSelect(id), 빈 곳은 onSelect(null).
 * 앱은 실제 지도(RouteMap)에 핀을 얹어 넣는다. null 이면 예시 지도(MomentMap).
 */
typealias MomentMapSlot = @Composable (modifier: Modifier, pins: List<MomentPin>, selected: String?, onSelect: (String?) -> Unit) -> Unit

private fun ClosedFloatingPointRange<Float>.overlaps(o: ClosedFloatingPointRange<Float>) =
    start <= o.endInclusive && o.start <= endInclusive

/** "4분", "1시간 5분" */
internal fun minutesLabel(ms: Long): String {
    val m = ((ms + 30_000) / 60_000).coerceAtLeast(1)
    return if (m < 60) "${m}분" else if (m % 60 == 0L) "${m / 60}시간" else "${m / 60}시간 ${m % 60}분"
}

private fun TripRecordUi.pins(): List<MomentPin> =
    talks.filter { !it.hidden && it.span != null }.map {
        MomentPin(talkKey(it), MomentKind.Talk, it.span!!.start, it.span.endInclusive, it.title)
    } + songs.map { MomentPin(songKey(it), MomentKind.Song, it.span.start, it.span.endInclusive, it.label) }

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
        .filter { (f, _) -> f in 0.12f..0.88f }
}

@Composable
internal fun TabbedDiary(
    state: DiaryUiState,
    onTab: (DbTab) -> Unit,
    onBack: () -> Unit,
    onNextPage: () -> Unit,
    onPlay: (TalkUi) -> Unit,
    onSeek: (TalkUi, Long) -> Unit,
    map: (@Composable (Modifier) -> Unit)?,
    onEdit: (() -> Unit)?,
    onOpenSongSettings: (() -> Unit)?,
    momentMap: MomentMapSlot?,
) {
    val r = state.record
    var tab by remember(r.id) { mutableStateOf(DiaryTab.Map) }
    var selected by remember(r.id) { mutableStateOf<String?>(null) }
    val pins = remember(r) { r.pins() }
    val pick: (String?) -> Unit = { id -> selected = if (id == selected) null else id }
    AdaptiveLayout(Modifier.fillMaxSize()) {
        val layout = DbTheme.layout
        val wide = layout.wide
        AppShell(
            selectedTab = DbTab.Record, onTab = onTab,
            topBar = { DiaryTopBar(state, onBack, onNextPage, onEdit) },
            scroll = !wide,
        ) {
            DiaryHead(state, tab) { tab = it }
            val body = if (wide) Modifier.weight(1f).fillMaxWidth().padding(top = Spacing.md) else Modifier.padding(top = Spacing.md)
            Column(body) {
                when (tab) {
                    DiaryTab.Map -> MapTab(state, pins, selected, pick, { selected = it }, onPlay, onSeek, momentMap)
                    DiaryTab.Route -> Pane(wide) { DiaryInfo(state, map, header = false) }
                    DiaryTab.Talks -> Pane(wide) { DiaryTalks(state, onPlay, onSeek) }
                    DiaryTab.Songs -> Pane(wide) {
                        SongsTab(state, onOpenSongSettings) { key -> selected = key; tab = DiaryTab.Map }
                    }
                }
            }
        }
    }
}

/** 넓은 화면에서 탭 하나를 가운데 폭으로 스스로 스크롤한다. 좁은 화면은 AppShell 이 스크롤한다. */
@Composable
private fun ColumnScope.Pane(wide: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (wide) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(leadWidth().fillMaxHeight().verticalScroll(rememberScrollState()), content = content)
        }
    } else {
        content()
    }
}

@Composable
private fun DiaryHead(state: DiaryUiState, tab: DiaryTab, onTab: (DiaryTab) -> Unit) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val r = state.record
    val songLabel = when {
        r.songs.isNotEmpty() -> "${state.tabSongs} ${r.songs.size}"
        r.songAccess == SongAccess.Off -> "${state.tabSongs} 꺼짐"
        else -> state.tabSongs
    }
    val tabs = listOf(
        DiaryTab.Map to state.tabMap,
        DiaryTab.Route to state.tabRoute,
        DiaryTab.Talks to "${state.tabTalks} ${r.talks.count { !it.hidden }}",
        DiaryTab.Songs to songLabel,
    )
    val title = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
            Text(r.dateLabel, style = t.bodySm.copy(fontWeight = FontWeight.Medium), color = c.muted)
            Text(r.title, style = t.titleLg, color = c.ink, modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (DbTheme.layout.wide) {
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { title() }
            DiaryTabRow(tabs, tab, onTab)
        }
    } else {
        Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            title()
            DiaryTabRow(tabs, tab, onTab, Modifier.fillMaxWidth(), fill = true)
        }
    }
}

@Composable
private fun DiaryTabRow(
    tabs: List<Pair<DiaryTab, String>>, selected: DiaryTab, onSelect: (DiaryTab) -> Unit,
    modifier: Modifier = Modifier, fill: Boolean = false,
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    Row(modifier.clip(DbShapes.pill).background(c.paper3).padding(3.dp)) {
        tabs.forEach { (tab, label) ->
            val on = tab == selected
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .height(42.dp)
                    .clip(DbShapes.pill)
                    .then(if (on) Modifier.background(c.paper).border(1.dp, c.rule, DbShapes.pill) else Modifier)
                    .selectable(on, role = Role.Tab) { onSelect(tab) }
                    .padding(horizontal = Spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, maxLines = 1,
                    style = t.bodySm.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) c.ink else c.muted,
                )
            }
        }
    }
}

/* ───────── 지도 탭 ───────── */

@Composable
private fun ColumnScope.MapTab(
    state: DiaryUiState,
    pins: List<MomentPin>,
    selected: String?,
    onPick: (String?) -> Unit,
    onJump: (String) -> Unit,
    onPlay: (TalkUi) -> Unit,
    onSeek: (TalkUi, Long) -> Unit,
    momentMap: MomentMapSlot?,
) {
    val r = state.record
    val layout = DbTheme.layout
    val ticks = remember(r) { r.hourTicks() }
    val map = @Composable { m: Modifier ->
        if (momentMap != null) {
            Box(m.clip(DbShapes.card)) { momentMap(Modifier.fillMaxSize(), pins, selected) { onPick(it) } }
        } else {
            MomentMap(SampleMap.route(r), SampleMap.labelsFor(r), pins, selected, { onPick(it) }, m)
        }
    }
    val strip = @Composable { m: Modifier ->
        MomentTimeline(pins, selected, onPick, "${r.startTime} ${r.from}", "${r.arriveTime} ${r.to}", r.tone.color, m, ticks)
    }
    if (layout.wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                map(Modifier.weight(1f).fillMaxWidth())
                strip(Modifier.fillMaxWidth())
            }
            val side = if (layout == DbLayout.Full) Modifier.width(380.dp) else Modifier.width(320.dp)
            Column(side.fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                MomentCard(state, selected, onJump, onPlay, onSeek)
                MomentList(state, selected, onPick)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            map(Modifier.fillMaxWidth().height(260.dp))
            strip(Modifier.fillMaxWidth())
            Box(Modifier.padding(top = Spacing.xs)) { MomentCard(state, selected, onJump, onPlay, onSeek) }
            MomentList(state, selected, onPick)
        }
    }
}

/** 고른 순간 카드. 아무것도 안 골랐으면 여행 요약과 안내. */
@Composable
private fun MomentCard(
    state: DiaryUiState,
    selected: String?,
    onJump: (String) -> Unit,
    onPlay: (TalkUi) -> Unit,
    onSeek: (TalkUi, Long) -> Unit,
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val r = state.record
    val talk = r.talks.firstOrNull { talkKey(it) == selected }
    val song = r.songs.firstOrNull { songKey(it) == selected }
    Column(
        Modifier.fillMaxWidth().clip(DbShapes.card).border(1.dp, c.rule, DbShapes.card).background(c.paper)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2xs),
    ) {
        when {
            talk != null -> {
                Text(talk.time + " · 음성 " + talk.audio, style = t.caption, color = c.muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(talk.title, style = t.titleLg, color = c.ink, modifier = Modifier.weight(1f))
                    if (state.playbackEnabled) {
                        val active = state.playback?.takeIf { it.id == talk.id }
                        val playing = active?.isPlaying == true
                        PlayButton(playing, "${talk.title} ${if (playing) "일시정지" else "재생"}", { onPlay(talk) })
                    }
                }
                state.playback?.takeIf { state.playbackEnabled && it.id == talk.id && it.durationMs > 0 }?.let { pb ->
                    PlaybackBar(pb) { onSeek(talk, it) }
                }
                if (talk.fromQuestion || talk.tags.isNotEmpty()) {
                    Row(Modifier.padding(top = Spacing.s2xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (talk.fromQuestion) Tag(state.fromQuestionTag, small = true)
                        talk.tags.take(3).forEach { Tag(it, small = true) }
                    }
                }
                val during = talk.span?.let { s -> r.songs.filter { it.span.overlaps(s) } }.orEmpty()
                if (during.isNotEmpty()) {
                    Related(state.songDuringTalk) {
                        during.forEach { s -> RelatedRow(s.label, song = true, tone = r.tone.color) { onJump(songKey(s)) } }
                    }
                }
            }
            song != null -> {
                Text("${song.start} – ${song.end} · ${minutesLabel(song.playedMs)} 들음", style = t.caption, color = c.muted)
                Text(song.title, style = t.titleLg, color = c.ink)
                Text(dotJoin(song.artist, song.source), style = t.body, color = c.ink2)
                val during = r.talks.filter { !it.hidden && it.span?.overlaps(song.span) == true }
                if (during.isNotEmpty()) {
                    Related(state.talksDuringSong) {
                        during.forEach { tk -> RelatedRow(tk.title, song = false, tone = r.tone.color) { onJump(talkKey(tk)) } }
                    }
                }
            }
            else -> {
                val talks = r.talks.count { !it.hidden }
                val parts = listOfNotNull("이야기 ${talks}개", r.songs.size.takeIf { it > 0 }?.let { "노래 ${it}곡" }, r.duration)
                Text(parts.joinToString(" · "), style = t.title, color = c.ink)
                Text(state.momentHint, style = t.bodySm, color = c.muted)
            }
        }
    }
}

@Composable
private fun Related(title: String, rows: @Composable ColumnScope.() -> Unit) {
    val c = DbTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xs).topRule(c.ruleSoft).padding(top = Spacing.xs)) {
        Text(title, style = DbTheme.type.caption, color = c.muted)
        rows()
    }
}

@Composable
private fun RelatedRow(text: String, song: Boolean, tone: Color, onClick: () -> Unit) {
    val c = DbTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (song) DbIcon(MomentIcons.Music, 16.dp, c.musicInk) else Box(Modifier.size(10.dp).clip(DbShapes.pill).background(tone))
        Text(text, style = DbTheme.type.body, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        // DbIcons 에 오른쪽 화살표가 없어 왼쪽 화살표를 좌우로 뒤집어 쓴다.
        DbIcon(DbIcons.ArrowLeft, 16.dp, c.muted, Modifier.scale(scaleX = -1f, scaleY = 1f))
    }
}

/** 이야기와 노래를 시각순으로 섞은 목록 */
@Composable
private fun MomentList(state: DiaryUiState, selected: String?, onPick: (String?) -> Unit) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val r = state.record
    data class Row3(val key: String, val start: Float, val time: String, val text: String, val song: Boolean)
    val rows = remember(r) {
        (r.talks.filter { !it.hidden && it.span != null }.map { Row3(talkKey(it), it.span!!.start, it.time, it.title, false) } +
            r.songs.map { Row3(songKey(it), it.span.start, it.start, it.label, true) })
            .sortedBy { it.start }
    }
    if (rows.isEmpty()) return
    Column {
        Text("시간순", style = t.caption, color = c.muted, modifier = Modifier.padding(bottom = Spacing.s2xs))
        rows.forEach { row ->
            val on = row.key == selected
            Row(
                Modifier.fillMaxWidth().topRule(c.ruleSoft).heightIn(min = 48.dp)
                    .background(if (on) c.paper2 else Color.Transparent)
                    .selectable(on, role = Role.Button) { onPick(row.key) }
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(row.time, style = t.bodySm, color = c.muted, modifier = Modifier.width(48.dp))
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    if (row.song) DbIcon(MomentIcons.Music, 14.dp, c.musicInk)
                    else Box(Modifier.size(10.dp).clip(DbShapes.pill).background(r.tone.color))
                }
                Text(
                    row.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    style = (if (row.song) t.bodySm else t.body).copy(fontWeight = if (on || !row.song) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (row.song && !on) c.ink2 else c.ink,
                )
            }
        }
    }
}

/* ───────── 노래 탭 ───────── */

@Composable
private fun ColumnScope.SongsTab(state: DiaryUiState, onOpenSettings: (() -> Unit)?, onPick: (String) -> Unit) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val r = state.record
    Text(state.songsTitle, style = t.titleLg, color = c.ink, modifier = Modifier.semantics { heading() })
    if (r.songs.isEmpty()) {
        Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            DbIcon(MomentIcons.Music, 32.dp, c.muted)
            Text(if (r.songAccess == SongAccess.Off) state.songsOff else state.songsEmpty, style = t.body, color = c.ink2)
            if (r.songAccess == SongAccess.Off && onOpenSettings != null) {
                OutlineButton(state.songsOpenSettings, onOpenSettings, block = false)
            }
        }
        return
    }
    val total = r.songs.sumOf { it.playedMs }
    Text("노래 ${r.songs.size}곡 · ${minutesLabel(total)} 들음", style = t.bodySm, color = c.muted, modifier = Modifier.padding(top = Spacing.s3xs))
    // 이번 여행의 노래: 가장 오래 들은 곡(같으면 먼저 들은 곡)
    val top = r.songs.maxWith(compareBy<SongUi> { it.playedMs }.thenByDescending { it.span.start })
    Row(
        Modifier.padding(top = Spacing.md).fillMaxWidth().clip(DbShapes.card).background(c.paper2)
            .clickable(role = Role.Button) { onPick(songKey(top)) }.padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(Modifier.size(56.dp).clip(DbShapes.pill).background(c.music), contentAlignment = Alignment.Center) {
            DbIcon(MomentIcons.Music, 26.dp, c.paper)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
            Text(state.songOfTrip, style = t.caption.copy(fontWeight = FontWeight.SemiBold), color = c.musicInk)
            Text(top.title, style = t.titleLg, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dotJoin(top.artist, "${minutesLabel(top.playedMs)} 들음"), style = t.bodySm, color = c.ink2)
        }
    }
    Column(Modifier.padding(top = Spacing.lg)) {
        r.songs.sortedBy { it.span.start }.forEach { s ->
            Row(
                Modifier.fillMaxWidth().topRule(c.ruleSoft).heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { onPick(songKey(s)) }.padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(s.start, style = t.bodySm, color = c.muted, modifier = Modifier.width(52.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
                    Text(s.title, style = t.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, style = t.bodySm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
                    Text(minutesLabel(s.playedMs), style = t.bodySm, color = c.ink2)
                    Text(s.source, style = t.caption, color = c.muted)
                }
            }
        }
    }
}
