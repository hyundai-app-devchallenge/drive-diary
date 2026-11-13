package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/** 말할 문장에 붙이는 큰따옴표 */
fun quoted(s: String) = "“$s”"

/** 동반이 + 말풍선. onBubbleTap = 데모 지름길(버튼 모양 없음). */
@Composable
fun DongbaniBubble(message: String, modifier: Modifier = Modifier, onBubbleTap: (() -> Unit)? = null) {
    val c = DbTheme.colors
    val wide = DbTheme.layout.wide
    Row(modifier.padding(top = if (wide) Spacing.s2xs else Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Mascot(36.dp)
        Text(
            message,
            style = DbTheme.type.hint.copy(lineHeight = DbTheme.type.hint.fontSize * 1.4f),
            color = c.ink2,
            modifier = Modifier
                .border(1.dp, c.rule, BubbleShape)
                .clip(BubbleShape)
                .background(c.paper)
                .demoTap(onBubbleTap)
                .padding(horizontal = Spacing.sm, vertical = if (wide) Spacing.s2xs else Spacing.xs),
        )
    }
}

private val BubbleShape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp)

/** 음성 예시 줄의 밀도. 폰에서 알림이 뜨면 카드 안 간격만 줄인다(글자 크기 그대로). */
enum class SayDensity { Normal, Toast, ToastTight }

/**
 * 음성 예시 목록. 말할 문장의 예시이지 버튼이 아니다: 버튼 모양·포커스 없이,
 * 누르면 그 말을 한 것으로 시뮬레이션만 한다(onSay).
 */
@Composable
fun SayList(items: List<String>, onSay: ((String) -> Unit)?, modifier: Modifier = Modifier, density: SayDensity = SayDensity.Normal) {
    val c = DbTheme.colors
    val wide = DbTheme.layout.wide
    val minH: Dp = when {
        wide -> 48.dp
        density == SayDensity.ToastTight -> 28.dp
        density == SayDensity.Toast -> 32.dp
        else -> 40.dp
    }
    val style = DbTheme.type.title.copy(fontWeight = FontWeight.Medium, fontSize = if (wide) DbTheme.type.titleMd.fontSize else DbTheme.type.title.fontSize)
    Column(modifier) {
        items.forEach { s ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = minH).demoTap(onSay?.let { { it(s) } }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                DbIcon(DbIcons.Mic, 16.dp, c.muted)
                Text(quoted(s), style = style, color = c.ink)
            }
        }
    }
}

/**
 * 질문 카드: 관계 태그, 질문, 힌트, 가는 선, "이렇게 말해 보세요" + (녹음 멈춤 캡션), 음성 예시.
 * 질문 크기: 폰 주행 26sp, 넓은 화면 32sp.
 */
@Composable
fun QuestionCard(
    tag: String,
    question: String,
    hint: String,
    sayLabel: String,
    say: List<String>,
    modifier: Modifier = Modifier,
    pauseCaption: String? = null,
    density: SayDensity = SayDensity.Normal,
    onSay: ((String) -> Unit)? = null,
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val wide = DbTheme.layout.wide
    val hairGap = when {
        wide -> 6.dp
        density == SayDensity.ToastTight -> Spacing.s3xs
        density == SayDensity.Toast -> Spacing.s2xs
        else -> Spacing.xs
    }
    Column(
        modifier
            .padding(top = Spacing.xs)
            .fillMaxWidth()
            .border(1.dp, c.rule, DbShapes.card)
            .clip(DbShapes.card)
            .background(c.paper)
            .padding(
                start = if (wide) Spacing.lg else Spacing.md,
                end = if (wide) Spacing.lg else Spacing.md,
                top = Spacing.md,
                bottom = if (!wide && density != SayDensity.Normal) Spacing.xs else Spacing.md,
            ),
    ) {
        Tag(tag, height = if (wide) 24.dp else 26.dp)
        Spacer(Modifier.height(if (wide || density == SayDensity.ToastTight) Spacing.s2xs else Spacing.xs))
        Text(question, style = if (wide) t.questionTablet else t.questionPhone, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(hint, style = t.bodySm, color = c.muted)
        Hairline(Modifier.padding(vertical = hairGap))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Bottom) {
            Text(sayLabel, style = t.caption, color = c.muted)
            Spacer(Modifier.weight(1f))
            if (pauseCaption != null) {
                Text(pauseCaption, style = t.caption, color = c.muted, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        SayList(say, onSay, Modifier.padding(top = if (wide) Spacing.s2xs else 0.dp), density)
    }
}

/** B01 조용히 듣는 중: 질문 카드 자리의 조용한 면(paper-2, 테두리 없음). 아래에 녹음 멈춤 명령 한 줄. */
@Composable
fun QuietFace(title: String, body: String, pauseHint: String, modifier: Modifier = Modifier) {
    val c = DbTheme.colors
    val wide = DbTheme.layout.wide
    val minH = if (wide) 296.dp else 234.dp
    Layout(
        modifier = modifier
            .padding(top = Spacing.xs)
            .fillMaxWidth()
            .clip(DbShapes.card)
            .background(c.paper2)
            .padding(horizontal = if (wide) Spacing.lg else Spacing.md, vertical = Spacing.md),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                DbIcon(DbIcons.Ear, 24.dp, c.muted, Modifier.padding(bottom = Spacing.s2xs))
                Text(title, style = DbTheme.type.titleMd, color = c.ink, modifier = Modifier.semantics { heading() })
                Text(body, style = DbTheme.type.hint, color = c.muted)
            }
            Row(
                Modifier.padding(top = Spacing.sm).fillMaxWidth().topRule(c.rule).padding(top = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2xs),
            ) {
                DbIcon(DbIcons.Mic, 14.dp, c.muted)
                Text(pauseHint, style = DbTheme.type.bodySm.copy(fontWeight = FontWeight.Medium, lineHeight = DbTheme.type.caption.lineHeight), color = c.muted)
            }
        },
    ) { ms, cs ->
        // 위 안내는 남은 높이의 가운데, 명령 줄은 맨 아래(웹 grid-template-rows: 1fr auto)
        val loose = cs.copy(minWidth = 0, minHeight = 0)
        val main = ms[0].measure(loose)
        val foot = ms[1].measure(loose.copy(minWidth = cs.maxWidth))
        val inner = (minH.roundToPx() - (Spacing.md * 2).roundToPx()).coerceAtLeast(0)
        val h = maxOf(inner, main.height + foot.height).coerceIn(cs.minHeight, cs.maxHeight)
        layout(cs.maxWidth, h) {
            main.place(0, ((h - foot.height - main.height) / 2).coerceAtLeast(0))
            foot.place(0, h - foot.height)
        }
    }
}

/**
 * 주행 진행 알약(보기 전용 상태 면)과 아래 길 리본.
 * onTap = 데모 지름길(웹: 진행 영역을 누르면 도착 S11).
 */
@Composable
fun TripProgressPill(
    title: String,
    sub: String,
    people: String,
    percent: Int,
    from: String,
    to: String,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val c = DbTheme.colors
    val wide = DbTheme.layout.wide
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(DbShapes.card).background(c.paper2).demoTap(onTap)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3xs),
        ) {
            Text(title, style = if (wide) DbTheme.type.titleMd.copy(lineHeight = DbTheme.type.titleMd.fontSize * 1.3f) else DbTheme.type.pill, color = c.ink)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(sub, style = DbTheme.type.caption, color = c.muted, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2xs)) {
                    DbIcon(DbIcons.Users, 14.dp, c.muted)
                    Text(people, style = DbTheme.type.caption, color = c.muted)
                }
            }
        }
        RoadRibbon(
            percent, c.accent, from, to,
            Modifier.padding(top = if (wide) Spacing.xs else Spacing.sm).padding(horizontal = Spacing.xs),
            car = true, paused = paused,
        )
    }
}

/** 폰 하단 요약 줄: "나눈 대화 N개" + "지금: …" */
@Composable
fun AskedSummary(countText: String, nowText: String?, modifier: Modifier = Modifier) {
    val c = DbTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(c.paper)
            .topRule(c.rule)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(countText, style = DbTheme.type.status, color = c.ink, modifier = Modifier.weight(1f))
        if (nowText != null) Text(nowText, style = DbTheme.type.status.copy(fontWeight = FontWeight.Medium), color = c.body)
    }
}

/**
 * "지금까지 나눈 대화" 패널(넓은 화면 오른쪽 열). 맨 아래 안내 줄(footnote) 또는 그 자리의 음성 알림(bottom).
 */
@Composable
fun AskedPanel(
    title: String,
    countText: String,
    items: List<AskedItem>,
    modifier: Modifier = Modifier,
    nowNote: String = "지금 이야기 중",
    footnote: String? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    // talk-rec 연동(DESIGN §6): 주행 중에는 제목 한 줄 + 태그 2개까지. 조작 없음(목록은 스크롤·펼치기 없음).
    driving: Boolean = false,
    // 주행 중 제목 보이기 설정이 꺼져 있으면 제목·태그 대신 중립 문구만 보인다.
    hideTitles: Boolean = false,
    hiddenLabel: String = "나눈 대화",
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val full = DbTheme.layout == com.dongbantest.app.ui.v3.theme.DbLayout.Full
    Column(
        modifier.clip(DbShapes.card).background(c.paper2).padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.xs), verticalAlignment = Alignment.Bottom) {
            Text(title, style = t.title, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            Text(countText, style = t.bodySm, color = c.muted)
        }
        items.forEach { a ->
            Row(
                Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(a.time, style = t.bodySm, color = c.muted, modifier = Modifier.width(if (full) 64.dp else 52.dp).padding(top = Spacing.s3xs))
                val titleStyle = t.title.copy(fontWeight = FontWeight.Medium, lineHeight = t.title.fontSize * 1.4f)
                val note = @Composable { Text(nowNote, style = t.caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1) }
                // talk-rec 연동: 주행 중 제목은 한 줄 말줄임. 제목 숨김이면 중립 문구.
                val titleText = if (hideTitles) hiddenLabel else a.title
                val titleLines = if (driving) 1 else Int.MAX_VALUE
                val titleColor = if (hideTitles) c.muted else c.ink
                val tagRow = @Composable {
                    val shown = if (driving) a.tags.take(2) else a.tags
                    if (!hideTitles && shown.isNotEmpty()) {
                        Row(Modifier.padding(top = Spacing.s2xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            // MIXED: 첫 태그는 분류, 주행 중에는 분류 + 첫 키워드까지.
                            shown.forEachIndexed { index, tag -> Tag(tag, small = true, category = index == 0) }
                        }
                    }
                }
                if (full) {
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text(titleText, style = titleStyle, color = titleColor, maxLines = titleLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (a.now && !hideTitles) note()
                        }
                        tagRow()
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(titleText, style = titleStyle, color = titleColor, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
                        if (a.now && !hideTitles) note()
                        tagRow()
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        when {
            bottom != null -> bottom()
            footnote != null -> Row(Modifier.padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                DbIcon(DbIcons.Lock, 16.dp, c.muted, Modifier.padding(top = 1.dp))
                Text(footnote, style = t.caption, color = c.muted)
            }
        }
    }
}

/** 음성 확인 알림: 성공(파랑) / 못 들음(흰·회색) / 확인 필요(검정 + "아니야" 48dp 버튼). 콘텐츠를 밀지 않고 떠 있다. */
@Composable
fun VoiceToast(toast: VoiceToastUi, modifier: Modifier = Modifier, inPanel: Boolean = false, onSay: ((String) -> Unit)? = null) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val wide = DbTheme.layout.wide
    val (bg, fg, second) = when (toast.kind) {
        ToastKind.Success -> Triple(c.accent, c.accentInk, c.accentSoft)
        ToastKind.NotHeard -> Triple(if (wide) c.paper else c.paper3, c.ink, c.ink2)
        ToastKind.Confirm -> Triple(c.ink, c.paper, c.paper3)
    }
    val icon = when (toast.kind) {
        ToastKind.Success -> DbIcons.Check
        ToastKind.NotHeard -> DbIcons.MicOff
        ToastKind.Confirm -> DbIcons.Mic
    }
    val box = modifier
        .then(if (inPanel) Modifier else Modifier.floatShadow(DbShapes.card))
        .clip(DbShapes.card)
        .background(bg)
        .then(if (toast.kind == ToastKind.NotHeard) Modifier.border(1.dp, c.rule, DbShapes.card) else Modifier)
        .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
    val text = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(Spacing.s3xs)) {
            Text(toast.first, style = t.status, color = fg, maxLines = if (!wide && toast.kind == ToastKind.Confirm) 1 else Int.MAX_VALUE)
            Text(toast.second, style = t.bodySm, color = second, maxLines = if (!wide && toast.kind == ToastKind.Confirm) 1 else Int.MAX_VALUE)
        }
    }
    val sayButton = @Composable { m: Modifier ->
        toast.say?.let { s ->
            Row(
                m.heightIn(min = 48.dp).widthIn(min = 96.dp).clip(DbShapes.button).background(c.paper)
                    .clickable(role = Role.Button) { onSay?.invoke(s) }
                    .padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2xs, Alignment.CenterHorizontally),
            ) {
                DbIcon(DbIcons.Mic, 16.dp, c.ink)
                Text(quoted(s), style = t.status.copy(lineHeight = t.status.fontSize * 1.2f), color = c.ink)
            }
        }
    }
    if (inPanel) {
        Column(box.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                DbIcon(icon, 20.dp, fg)
                text(Modifier.weight(1f))
            }
            if (toast.say != null) sayButton(Modifier.padding(start = 32.dp, top = Spacing.sm))
        }
    } else {
        Row(
            box.padding(horizontal = Spacing.md, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DbIcon(icon, 20.dp, fg)
            text(Modifier.weight(1f))
            sayButton(Modifier)
        }
    }
}

