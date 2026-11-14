package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbColors
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing
import kotlin.math.abs
import kotlin.math.hypot

/** 지도·타임라인 띠에 놓는 순간의 종류 */
enum class MomentKind { Talk, Song }

/**
 * 지도와 타임라인 띠에 놓는 한 순간(이야기 하나 또는 노래 한 곡).
 * start/end 는 여행 시간(출발 0 → 도착 1) 안의 비율. 예시 지도에서는 경로 위 위치도 이 비율로 정한다.
 * label 은 고른 핀 위 말풍선 글자.
 */
@Immutable
data class MomentPin(val id: String, val kind: MomentKind, val start: Float, val end: Float, val label: String)

/** 노래 화면에만 쓰는 아이콘. DbIcons 와 같은 Lucide 선 아이콘(Music). */
object MomentIcons {
    val Music: ImageVector by lazy {
        val b = ImageVector.Builder("Music", 24.dp, 24.dp, 24f, 24f)
        listOf("M9 18V5l12-2v13", "M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0").forEach { d ->
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        b.build()
    }
}

/**
 * 기록 상세 "지도" 탭의 예시 지도. [ExampleMap] 위에 노래가 흐른 길(경로 옆 점선), 노래 핀, 이야기 핀을 얹는다.
 * 핀을 누르면 onSelect(id), 빈 곳을 누르면 onSelect(null). 고른 핀은 커지고 위에 말풍선이 뜬다.
 * 실제 기록은 나중에 RouteMap(Mapbox)에 같은 모양으로 그린다(M00 설계 4.4).
 */
@Composable
fun MomentMap(
    route: MapRoute,
    labels: List<MapLabel>,
    pins: List<MomentPin>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    caption: String = "예시 지도",
) {
    val c = DbTheme.colors
    val tone = route.tone.color
    val paint = rememberMomentPaint(tone)
    val talks = pins.count { it.kind == MomentKind.Talk }
    val songs = pins.count { it.kind == MomentKind.Song }
    Box(modifier.clip(DbShapes.card)) {
        ExampleMap(
            routes = listOf(route), labels = labels, modifier = Modifier.fillMaxSize(), caption = caption,
            description = "${route.title} 경로 지도. 이야기 ${talks}개, 노래 ${songs}곡",
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(pins, route) {
                    detectTapGestures { p ->
                        val (p0, k, p2) = ctrl(route, size.width.toFloat(), size.height.toFloat())
                        // 이야기 핀이 노래 핀 위에 그려지므로, 거리가 비슷하면 이야기를 먼저 고른다.
                        val hit = pins
                            .map { it to quad(p0, k, p2, it.start) }
                            .minByOrNull { (pin, at) ->
                                hypot(p.x - at.x, p.y - at.y) - if (pin.kind == MomentKind.Talk) 6.dp.toPx() else 0f
                            }
                        val d = hit?.let { hypot(p.x - it.second.x, p.y - it.second.y) } ?: Float.MAX_VALUE
                        onSelect(if (hit != null && d < 28.dp.toPx()) hit.first.id else null)
                    }
                },
        ) {
            val (p0, k, p2) = ctrl(route, size.width, size.height)
            // 1) 노래가 흐른 길: 경로 왼쪽 옆으로 7dp 띄운 점선
            pins.filter { it.kind == MomentKind.Song }.forEach { s ->
                val path = Path()
                val steps = 24
                for (i in 0..steps) {
                    val t = s.start + (s.end - s.start) * i / steps
                    val at = quad(p0, k, p2, t)
                    val n = normal(p0, k, p2, t) * 7.dp.toPx()
                    if (i == 0) path.moveTo(at.x + n.x, at.y + n.y) else path.lineTo(at.x + n.x, at.y + n.y)
                }
                drawPath(
                    path, if (s.id == selected) c.music else c.music.copy(alpha = 0.55f),
                    style = Stroke(
                        width = (if (s.id == selected) 3.5f else 2.5f).dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 6.dp.toPx())),
                    ),
                )
            }
            // 2) 핀과 고른 핀 위 말풍선
            drawMomentPins(pins.map { it to quad(p0, k, p2, it.start) }, selected, paint)
        }
    }
}

/** 핀을 그리는 데 필요한 색·아이콘·글자 도구 묶음 */
internal class MomentPaint(
    val tone: Color,
    val c: DbColors,
    val talkIcon: VectorPainter,
    val noteIcon: VectorPainter,
    val measurer: TextMeasurer,
    val bubbleStyle: TextStyle,
)

@Composable
internal fun rememberMomentPaint(tone: Color): MomentPaint {
    val c = DbTheme.colors
    val measurer = rememberTextMeasurer()
    val talkIcon = rememberVectorPainter(DbIcons.MessageCircle)
    val noteIcon = rememberVectorPainter(MomentIcons.Music)
    val bubbleStyle = DbTheme.type.label.copy(color = c.ink, fontWeight = FontWeight.SemiBold)
    return MomentPaint(tone, c, talkIcon, noteIcon, measurer, bubbleStyle)
}

/**
 * 화면 좌표에 놓인 핀을 그린다. 노래 → 이야기 → 고른 핀 순으로 위에 쌓고, 고른 핀 위에 말풍선을 띄운다.
 * 예시 지도(MomentMap)와 실제 지도(RouteMap) 둘 다 이 함수로 그려 모양이 같다.
 */
internal fun DrawScope.drawMomentPins(placed: List<Pair<MomentPin, Offset>>, selected: String?, p: MomentPaint) {
    val c = p.c
    placed.sortedBy { (pin, _) -> if (pin.id == selected) 2 else if (pin.kind == MomentKind.Talk) 1 else 0 }
        .forEach { (pin, at) ->
            val sel = pin.id == selected
            when (pin.kind) {
                MomentKind.Talk -> talkPin(at, sel, p.tone, c.paper, c.shadow, p.talkIcon)
                MomentKind.Song -> songPin(at, sel, c.music, c.musicInk, c.paper, c.shadow, p.noteIcon)
            }
        }
    placed.firstOrNull { it.first.id == selected }?.let { (pin, at) ->
        val r = if (pin.kind == MomentKind.Talk) 19.dp.toPx() else 14.dp.toPx()
        bubble(
            p.measurer, pin.label, p.bubbleStyle, Offset(at.x, at.y - r - 18.dp.toPx()),
            c.paper, if (pin.kind == MomentKind.Talk) p.tone else c.music, size.width,
        )
    }
}

/** 2차 곡선의 t 지점에서 진행 방향의 왼쪽 법선(길이 1) */
private fun normal(p0: Offset, k: Offset, p2: Offset, t: Float): Offset {
    val dx = 2 * (1 - t) * (k.x - p0.x) + 2 * t * (p2.x - k.x)
    val dy = 2 * (1 - t) * (k.y - p0.y) + 2 * t * (p2.y - k.y)
    val len = hypot(dx, dy).takeIf { it > 0f } ?: return Offset.Zero
    return Offset(dy / len, -dx / len)
}

private fun DrawScope.talkPin(at: Offset, sel: Boolean, tone: Color, paper: Color, shadow: Color, icon: VectorPainter) {
    val s = if (sel) 1.3f else 1f
    val r = 15.dp.toPx() * s
    drawCircle(shadow, r + 3.dp.toPx(), at + Offset(0f, 1.5f.dp.toPx()))
    drawCircle(paper, r + 2.5f.dp.toPx(), at)
    drawCircle(tone, r, at)
    pinIcon(icon, at, 14.dp * s, paper)
}

private fun DrawScope.songPin(at: Offset, sel: Boolean, ring: Color, ink: Color, paper: Color, shadow: Color, icon: VectorPainter) {
    val s = if (sel) 1.3f else 1f
    val r = 11.dp.toPx() * s
    drawCircle(shadow, r + 2.dp.toPx(), at + Offset(0f, 1.dp.toPx()))
    drawCircle(if (sel) ring else paper, r, at)
    drawCircle(ring, r, at, style = Stroke((if (sel) 2.5f else 1.5f).dp.toPx()))
    pinIcon(icon, at, 12.dp * s, if (sel) paper else ink)
}

private fun DrawScope.pinIcon(painter: VectorPainter, center: Offset, size: Dp, color: Color) {
    val px = size.toPx()
    translate(center.x - px / 2, center.y - px / 2) {
        with(painter) { draw(Size(px, px), colorFilter = ColorFilter.tint(color)) }
    }
}

private fun DrawScope.bubble(
    measurer: TextMeasurer, text: String, style: TextStyle, center: Offset, bg: Color, border: Color, mapWidth: Float,
) {
    val m = measurer.measure(text, style, maxLines = 1)
    val bw = m.size.width + 20.dp.toPx()
    val bh = 28.dp.toPx()
    val x = center.x.coerceIn(bw / 2 + 6.dp.toPx(), mapWidth - bw / 2 - 6.dp.toPx())
    val top = Offset(x - bw / 2, (center.y - bh / 2).coerceAtLeast(6.dp.toPx()))
    drawRoundRect(bg, top, Size(bw, bh), CornerRadius(bh / 2))
    drawRoundRect(border, top, Size(bw, bh), CornerRadius(bh / 2), style = Stroke(1.5f.dp.toPx()))
    drawText(m, topLeft = Offset(x - m.size.width / 2f, top.y + (bh - m.size.height) / 2f))
}

/**
 * 출발~도착 시간을 가로로 편 띠. 막대나 마커를 누르면 onSelect(id). 고른 순간 자리에는 세로선이 선다.
 * - 이야기만 있으면(노래 기록을 쓰지 않음) 한 줄: 이야기 길이만큼의 막대 위, 시작 자리에 이야기 마커(말풍선 원).
 * - 노래도 있으면 두 줄: 위 줄은 이야기(여행 색), 아래 줄은 노래(베리색).
 * ticks: (0–1 비율, "10:30") 사이 눈금. 양 끝은 startLabel / endLabel.
 */
@Composable
fun MomentTimeline(
    pins: List<MomentPin>,
    selected: String?,
    onSelect: (String?) -> Unit,
    startLabel: String,
    endLabel: String,
    tone: Color,
    modifier: Modifier = Modifier,
    ticks: List<Pair<Float, String>> = emptyList(),
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val talks = pins.count { it.kind == MomentKind.Talk }
    val songs = pins.count { it.kind == MomentKind.Song }
    val twoLanes = songs > 0
    val labelStyle = if (twoLanes) t.caption else t.bodySm.copy(fontWeight = FontWeight.Medium)
    val talkIcon = rememberVectorPainter(DbIcons.MessageCircle)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (twoLanes) 18.dp else 22.dp)) {
            val w = maxWidth
            Text(startLabel, style = labelStyle, color = c.muted, modifier = Modifier.align(Alignment.CenterStart), maxLines = 1)
            Text(endLabel, style = labelStyle, color = c.muted, modifier = Modifier.align(Alignment.CenterEnd), maxLines = 1)
            // 양 끝 글자와 겹치는 눈금 글자는 숨긴다(좁은 화면에서 "09:20 서울 마포"와 "10:00"이 겹침).
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val startW = with(density) { measurer.measure(keepAll(startLabel), labelStyle).size.width.toDp() }
            val endW = with(density) { measurer.measure(keepAll(endLabel), labelStyle).size.width.toDp() }
            val gap = Spacing.s2xs
            ticks.filter { (f, _) -> w * f - 28.dp > startW + gap && w * f + 28.dp < w - endW - gap }.forEach { (f, label) ->
                Text(
                    label, style = labelStyle, color = c.muted, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.width(56.dp).offset(x = w * f - 28.dp).align(Alignment.CenterStart),
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (twoLanes) 48.dp else 72.dp)
                .clip(DbShapes.button)
                .background(c.paper2)
                .semantics { contentDescription = if (twoLanes) "시간 띠: 이야기 ${talks}개, 노래 ${songs}곡" else "시간 띠: 이야기 ${talks}개" }
                .pointerInput(pins, twoLanes) {
                    detectTapGestures { p ->
                        val w = size.width.toFloat()
                        val kind = if (!twoLanes || p.y < size.height / 2f) MomentKind.Talk else MomentKind.Song
                        val slop = if (twoLanes) 8.dp.toPx() else MARKER_R.dp.toPx() + 4.dp.toPx()
                        val hit = pins.filter { it.kind == kind }
                            .filter { p.x in (it.start * w - slop)..(maxOf(it.end * w, it.start * w) + slop) }
                            .minByOrNull { abs(p.x - it.start * w) }
                        onSelect(hit?.id)
                    }
                },
        ) {
            val w = size.width
            ticks.forEach { (f, _) -> drawLine(c.rule, Offset(f * w, 0f), Offset(f * w, size.height), 1.dp.toPx()) }
            if (twoLanes) {
                val lane = 13.dp.toPx()
                val minW = 6.dp.toPx()
                pins.forEach { pin ->
                    val top = if (pin.kind == MomentKind.Talk) 8.dp.toPx() else 27.dp.toPx()
                    val x0 = pin.start * w
                    val bw = maxOf((pin.end - pin.start) * w, minW)
                    val sel = pin.id == selected
                    val color = when {
                        pin.kind == MomentKind.Talk -> tone
                        sel -> c.music
                        else -> c.musicSoft
                    }
                    drawRoundRect(color, Offset(x0, top), Size(bw, lane), CornerRadius(4.dp.toPx()))
                    if (sel) {
                        drawRoundRect(c.ink, Offset(x0 - 2.dp.toPx(), top - 2.dp.toPx()), Size(bw + 4.dp.toPx(), lane + 4.dp.toPx()),
                            CornerRadius(5.dp.toPx()), style = Stroke(1.5f.dp.toPx()))
                    }
                }
                pins.firstOrNull { it.id == selected }?.let { pin ->
                    val x = pin.start * w
                    drawLine(c.ink, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                }
            } else {
                // 한 줄: 가운데 길(옅은 선) 위에 이야기 길이 막대, 시작 자리에 이야기 마커
                val cy = size.height / 2f
                val bar = 10.dp.toPx()
                drawLine(c.rule, Offset(0f, cy), Offset(w, cy), 2.dp.toPx(), StrokeCap.Round)
                val r = MARKER_R.dp.toPx()
                val talkPins = pins.filter { it.kind == MomentKind.Talk }
                talkPins.forEach { pin ->
                    val sel = pin.id == selected
                    val x0 = pin.start * w
                    val bw = maxOf((pin.end - pin.start) * w, 6.dp.toPx())
                    drawRoundRect(
                        if (selected == null || sel) tone else tone.copy(alpha = 0.35f),
                        Offset(x0, cy - bar / 2), Size(bw, bar), CornerRadius(bar / 2),
                    )
                }
                pins.firstOrNull { it.id == selected }?.let { pin ->
                    val x = pin.start * w
                    drawLine(c.ink, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                }
                // 마커는 막대·세로선 위에. 고른 것을 맨 위에.
                talkPins.sortedBy { it.id == selected }.forEach { pin ->
                    val sel = pin.id == selected
                    val s = if (sel) 1.2f else 1f
                    val at = Offset((pin.start * w).coerceIn(r * s + 2f, w - r * s - 2f), cy)
                    drawCircle(c.shadow, r * s + 2.dp.toPx(), at + Offset(0f, 1.dp.toPx()))
                    drawCircle(if (sel) c.ink else c.paper, r * s + 2.dp.toPx(), at)
                    drawCircle(if (selected == null || sel) tone else tone.copy(alpha = 0.55f), r * s, at)
                    pinIcon(talkIcon, at, (13 * s).dp, c.paper)
                }
            }
        }
        if (twoLanes) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Legend("이야기") { Box(Modifier.size(12.dp, 8.dp).clip(DbShapes.button).background(tone)) }
                Legend("노래") { Box(Modifier.size(12.dp, 8.dp).clip(DbShapes.button).background(c.musicSoft)) }
                Legend("노래가 흐른 길") {
                    Canvas(Modifier.size(16.dp, 8.dp)) {
                        drawLine(
                            c.music, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5f.dp.toPx(), StrokeCap.Round,
                            PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx())),
                        )
                    }
                }
            }
        }
    }
}

/** 한 줄 시간 띠의 이야기 마커 반지름(dp) */
private const val MARKER_R = 14f

@Composable
private fun Legend(text: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s2xs)) {
        swatch()
        Text(text, style = DbTheme.type.caption, color = DbTheme.colors.muted)
    }
}
