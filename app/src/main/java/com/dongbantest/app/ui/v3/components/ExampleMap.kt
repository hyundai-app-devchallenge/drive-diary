package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing
import kotlin.math.hypot

/** 지도 위 기록 경로. from/to 는 지도 폭·높이에 대한 0–1 비율, bend 는 곡선이 휘는 정도. */
@Immutable
data class MapRoute(
    val id: String,
    val title: String,
    val tone: TripTone,
    val from: Offset,
    val to: Offset,
    val bend: Float,
)

/** 지명 라벨. at 비율 + (dx, dy)dp. routeId 가 있으면 누를 때 그 경로를 연다. */
@Immutable
data class MapLabel(val text: String, val at: Offset, val dx: Float, val dy: Float, val routeId: String? = null)

/** 진행 중 경로: routeId 경로를 progress(0–1) 지점까지 점선과 차 표식으로, 라벨은 차 표식 위. */
@Immutable
data class MapLive(val routeId: String, val progress: Float, val label: String)

private fun ctrl(r: MapRoute, w: Float, h: Float): Triple<Offset, Offset, Offset> {
    val p0 = Offset(r.from.x * w, r.from.y * h)
    val p2 = Offset(r.to.x * w, r.to.y * h)
    val dx = p2.x - p0.x
    val dy = p2.y - p0.y
    return Triple(p0, Offset((p0.x + p2.x) / 2 + dy * r.bend, (p0.y + p2.y) / 2 - dx * r.bend), p2)
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
private fun quad(p0: Offset, c: Offset, p2: Offset, t: Float) = lerp(lerp(p0, c, t), lerp(c, p2, t), t)

/**
 * 예시 지도(웹 SVG를 Canvas로). 외부 타일 없이 땅·바다·길과 기록 경로를 그린다.
 * onOpen 이 있으면 경로(48dp 폭)나 라벨을 눌러 그 기록을 연다. live 경로는 onLive.
 */
@Composable
fun ExampleMap(
    routes: List<MapRoute>,
    labels: List<MapLabel>,
    modifier: Modifier = Modifier,
    live: MapLive? = null,
    liveRoute: MapRoute? = null,
    caption: String = "예시 지도",
    description: String = "기록 경로 예시 지도",
    onOpen: ((String) -> Unit)? = null,
    onLive: (() -> Unit)? = null,
) {
    val c = DbTheme.colors
    val labelStyle = DbTheme.type.label.copy(color = c.ink)
    val measurer = rememberTextMeasurer()
    val flag = rememberVectorPainter(DbIcons.Flag)
    val carIcon = rememberVectorPainter(DbIcons.Car)
    val routeColors = routes.associate { it.id to it.tone.color }
    val liveColor = liveRoute?.tone?.color ?: c.accent
    Box(
        modifier
            .clip(DbShapes.card)
            .background(c.mapLand)
            .semantics { contentDescription = description },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(routes, live, onOpen, onLive) {
                    if (onOpen == null && onLive == null) return@pointerInput
                    detectTapGestures { p ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        // 라벨 먼저
                        labels.forEach { l ->
                            val id = l.routeId ?: return@forEach
                            val pos = Offset(l.at.x * w + l.dx.dp.toPx(), l.at.y * h + l.dy.dp.toPx())
                            if (hypot(p.x - pos.x, p.y - pos.y) < 24.dp.toPx()) {
                                if (live != null && id == live.routeId) onLive?.invoke() else onOpen?.invoke(id)
                                return@detectTapGestures
                            }
                        }
                        var best: String? = null
                        var bestD = 24.dp.toPx()
                        val all = routes.map { it.id to ctrl(it, w, h) } +
                            listOfNotNull(liveRoute?.let { "live" to ctrl(it, w, h) })
                        all.forEach { (id, k) ->
                            val end = if (id == "live") live?.progress ?: 1f else 1f
                            for (i in 0..40) {
                                val q = quad(k.first, k.second, k.third, end * i / 40f)
                                val d = hypot(p.x - q.x, p.y - q.y)
                                if (d < bestD) { bestD = d; best = id }
                            }
                        }
                        when (best) {
                            null -> Unit
                            "live" -> onLive?.invoke()
                            else -> onOpen?.invoke(best!!)
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            // 바다
            val sea = Path().apply {
                moveTo(0.9f * w, 0f)
                cubicTo(0.85f * w, 0.22f * h, 0.93f * w, 0.42f * h, 0.88f * w, 0.64f * h)
                cubicTo(0.7f * w, 0.72f * h, 0.4f * w, 0.64f * h, 0f, 0.7f * h)
                lineTo(0f, h); lineTo(w, h); lineTo(w, 0f); close()
            }
            drawPath(sea, c.mapSea)
            val ry = maxOf(0.065f * h, 14.dp.toPx())
            drawOval(c.mapLand, topLeft = Offset(0.3f * w - 0.16f * w, 0.86f * h - ry), size = Size(0.32f * w, ry * 2))
            val road = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            listOf(
                floatArrayOf(0f, 0.42f, 0.3f, 0.4f, 0.55f, 0.5f, 0.86f, 0.46f),
                floatArrayOf(0.3f, 0f, 0.32f, 0.25f, 0.26f, 0.45f, 0.3f, 0.66f),
                floatArrayOf(0.58f, 0f, 0.6f, 0.2f, 0.66f, 0.4f, 0.62f, 0.67f),
                floatArrayOf(0.14f, 0.88f, 0.24f, 0.8f, 0.36f, 0.82f, 0.44f, 0.87f),
            ).forEach { a ->
                drawPath(
                    Path().apply { moveTo(a[0] * w, a[1] * h); cubicTo(a[2] * w, a[3] * h, a[4] * w, a[5] * h, a[6] * w, a[7] * h) },
                    c.mapRoad, style = road,
                )
            }
            routes.forEach { r ->
                val color = routeColors.getValue(r.id)
                val (p0, k, p2) = ctrl(r, w, h)
                drawPath(Path().apply { moveTo(p0.x, p0.y); quadraticTo(k.x, k.y, p2.x, p2.y) }, color, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                ring(p0, 5f, c.paper, color)
                ring(p2, 13f, c.paper, color)
                icon(flag, p2, color)
            }
            if (live != null && liveRoute != null) {
                val (p0, k, p2) = ctrl(liveRoute, w, h)
                val t = live.progress
                val q0 = lerp(p0, k, t)
                val car = quad(p0, k, p2, t)
                drawPath(
                    Path().apply { moveTo(p0.x, p0.y); quadraticTo(q0.x, q0.y, car.x, car.y) }, liveColor,
                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 9.dp.toPx()))),
                )
                ring(p0, 5f, c.paper, liveColor)
                ring(p2, 5f, c.paper, liveColor)
                drawCircle(liveColor, 13.dp.toPx(), car)
                icon(carIcon, car, c.paper)
            }
            labels.forEach { l ->
                var pos = Offset(l.at.x * w + l.dx.dp.toPx(), l.at.y * h + l.dy.dp.toPx())
                label(measurer, l.text, labelStyle, pos, c.paper, w)
            }
            if (live != null && liveRoute != null) {
                val (p0, k, p2) = ctrl(liveRoute, w, h)
                val car = quad(p0, k, p2, live.progress)
                label(measurer, live.label, labelStyle, Offset(car.x, car.y - 27.dp.toPx()), c.paper, w, clamp = true)
            }
        }
        Text(
            caption,
            style = DbTheme.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            color = c.muted,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.sm, bottom = Spacing.xs),
        )
    }
}

private fun DrawScope.ring(center: Offset, rDp: Float, fill: Color, stroke: Color) {
    drawCircle(fill, rDp.dp.toPx(), center)
    drawCircle(stroke, rDp.dp.toPx() - 1.dp.toPx(), center, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.icon(painter: VectorPainter, center: Offset, color: Color) {
    val s = 14.dp.toPx()
    translate(center.x - s / 2, center.y - s / 2) {
        with(painter) { draw(Size(s, s), colorFilter = ColorFilter.tint(color)) }
    }
}

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    center: Offset,
    bg: Color,
    mapWidth: Float,
    clamp: Boolean = false,
) {
    val m = measurer.measure(text, style)
    val bw = m.size.width + 16.dp.toPx()
    val bh = 22.dp.toPx()
    var x = center.x
    if (clamp) x = x.coerceIn(bw / 2 + 6.dp.toPx(), mapWidth - bw / 2 - 6.dp.toPx())
    drawRoundRect(bg, topLeft = Offset(x - bw / 2, center.y - bh / 2), size = Size(bw, bh), cornerRadius = CornerRadius(bh / 2))
    drawText(m, topLeft = Offset(x - m.size.width / 2f, center.y - m.size.height / 2f))
}
