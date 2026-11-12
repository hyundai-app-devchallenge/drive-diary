package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dongbantest.app.R
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/** 동반이(마스코트). 장식이라 접근성 트리에서 뺀다. 원본 비율 99:85. */
@Composable
fun Mascot(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.v3_dongbani),
        contentDescription = null,
        modifier = modifier.width(size).aspectRatio(99f / 85f).clearAndSetSemantics { },
    )
}

/** 장식 아이콘(설명 없음). 뜻이 있는 아이콘은 contentDescription을 준다. */
@Composable
fun DbIcon(icon: ImageVector, size: Dp, tint: Color, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Icon(icon, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/**
 * 데모 지름길용 누르기: 버튼 모양·포커스·물결 없이 탭만 받는다(웹의 음성 예시, 진행 알약, 말풍선과 같음).
 * onTap이 null이면 아무 것도 하지 않는다.
 */
fun Modifier.demoTap(onTap: (() -> Unit)?): Modifier =
    if (onTap == null) this else pointerInput(onTap) { detectTapGestures { onTap() } }

/** 위·아래·오른쪽 1dp 선(CSS border-top 등). */
fun Modifier.topRule(color: Color, width: Dp = 1.dp) = drawBehind {
    drawRect(color, size = size.copy(height = width.toPx()))
}

fun Modifier.bottomRule(color: Color, width: Dp = 1.dp) = drawBehind {
    val w = width.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - w), size = size.copy(height = w))
}

fun Modifier.endRule(color: Color, width: Dp = 1.dp) = drawBehind {
    val w = width.toPx()
    drawRect(color, topLeft = Offset(size.width - w, 0f), size = size.copy(width = w))
}

/** --shadow-float: 아주 옅은 떠 있는 그림자. */
@Composable
fun Modifier.floatShadow(shape: Shape): Modifier {
    val color = DbTheme.colors.shadow
    // CSS 그림자(0 0 0 1px 2%, 0 2px 6px 4%, 0 4px 8px 10%)를 겹친 반투명 윤곽으로 그린다. 기기·미리보기에서 같게 보인다.
    return drawBehind {
        fun layer(spread: Float, dy: Float, alpha: Float) {
            val o = shape.createOutline(Size(size.width + spread * 2, size.height + spread * 2), layoutDirection, this)
            translate(-spread, -spread + dy) { drawOutline(o, color.copy(alpha = alpha)) }
        }
        val px = 1.dp.toPx()
        layer(px, 0f, 0.02f / 0.1f * color.alpha)
        for (i in 1..4) layer(i * 1.5f * px, 2f * px, 0.012f)
        for (i in 1..4) layer(i * 2f * px, 4f * px, 0.018f)
    }
}

/** 가는 가로선(.hair) */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = DbTheme.colors.rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** 관계 태그(.tag): accent-soft 바탕 알약. small = .tag-sm */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, small: Boolean = false, height: Dp = if (small) 22.dp else 26.dp) {
    val c = DbTheme.colors
    Box(
        modifier
            .height(height)
            .clip(DbShapes.pill)
            .background(c.accentSoft)
            .padding(horizontal = if (small) Spacing.xs else Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DbTheme.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.accent)
    }
}

/** 아이콘 + 글자 알약(.badge): accent-soft 바탕. */
@Composable
fun Badge(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val c = DbTheme.colors
    Row(
        modifier.height(32.dp).clip(DbShapes.pill).background(c.accentSoft).padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2xs),
    ) {
        DbIcon(icon, 16.dp, c.accent)
        Text(text, style = DbTheme.type.bodySm.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.accent)
    }
}

/** 사진 자리(.photo-slot). tile = 다이어리 사진 칸(높이 88). */
@Composable
fun PhotoSlot(label: String, modifier: Modifier = Modifier, tile: Boolean = false, aspect: Float = 4f / 3f) {
    val c = DbTheme.colors
    Column(
        (if (tile) modifier.fillMaxWidth().height(88.dp) else modifier.fillMaxWidth().aspectRatio(aspect))
            .clip(DbShapes.card)
            .background(c.paper2),
        verticalArrangement = Arrangement.spacedBy(if (tile) Spacing.s2xs else Spacing.xs, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DbIcon(com.dongbantest.app.ui.v3.theme.DbIcons.Image, if (tile) 20.dp else 24.dp, c.muted)
        Text(label, style = DbTheme.type.caption, color = c.muted)
    }
}

/** 기록 색 점(.dot, 8dp) */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(DbShapes.pill).background(color))
}
