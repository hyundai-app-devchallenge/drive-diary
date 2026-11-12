package com.dongbantest.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dongbantest.app.domain.model.RouteRibbon
import com.dongbantest.app.ui.theme.Cocoa
import com.dongbantest.app.ui.theme.CocoaSoft
import com.dongbantest.app.ui.theme.DongbantestTheme

/**
 * 길 리본: 출발지 ●━━━━━●───── 목적지 (PRD 12장 브랜드 요소, S05 진행 카드).
 * 디자인이 확정되면 색·두께만 바꾸면 된다. 데이터는 [RouteRibbon] 하나로 받는다.
 */
@Composable
fun RouteRibbonView(
    ribbon: RouteRibbon,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "🚩 ${ribbon.originName ?: "출발"}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Cocoa,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "📍 ${ribbon.destinationName ?: "목적지 미정"}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Cocoa,
                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        RibbonBar(ribbon.progress, color, Modifier.fillMaxWidth().height(22.dp))
        Text(ribbonCaption(ribbon), style = MaterialTheme.typography.bodyMedium, color = CocoaSoft)
    }
}

/** 막대: 회색 전체 길 위에 지나온 만큼 색을 칠하고, 지금 위치에 점을 찍는다. */
@Composable
private fun RibbonBar(progress: Float?, color: Color, modifier: Modifier) {
    val track = Color(0x33000000)
    Canvas(modifier) {
        val y = size.height / 2
        val stroke = 6.dp.toPx()
        val r = 6.dp.toPx()
        val start = Offset(r, y)
        val end = Offset(size.width - r, y)
        drawLine(track, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(color, r, start)
        drawCircle(color, r, end, style = Stroke(width = 2.dp.toPx()))
        if (progress != null) {
            val here = Offset(start.x + (end.x - start.x) * progress, y)
            drawLine(color, start, here, strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(Color.White, r + 2.dp.toPx(), here)
            drawCircle(color, r, here)
        }
    }
}

/** "오후 6:58 출발 · 65% · 남은 시간 8분(1.5km) · 함께 2명" */
private fun ribbonCaption(ribbon: RouteRibbon): String = buildList {
    add("${formatTime(ribbon.startedAt)} 출발")
    ribbon.progress?.let { add("${(it * 100).toInt()}%") }
    if (ribbon.remainSeconds != null && ribbon.remainMeters != null) {
        add("남은 시간 ${formatElapsed(ribbon.remainSeconds * 1000L)}(${formatKm(ribbon.remainMeters.toDouble())}km)")
    }
    if (ribbon.companionCount > 0) add("함께 ${ribbon.companionCount}명")
    if (ribbon.progress == null) add("내비에 목적지를 설정하면 진행률이 보여요")
}.joinToString(" · ")

@Preview(widthDp = 520, showBackground = true)
@Composable
private fun RouteRibbonPreview() {
    DongbantestTheme {
        Column {
            RouteRibbonView(RouteRibbon("수정구 시흥동", "경기도판교테크노밸리", System.currentTimeMillis(), 0.65f, 800, 300, 2))
            RouteRibbonView(RouteRibbon("수정구 시흥동", null, System.currentTimeMillis(), null, null, null, 2))
        }
    }
}
