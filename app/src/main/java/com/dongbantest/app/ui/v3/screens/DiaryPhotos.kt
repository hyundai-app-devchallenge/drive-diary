package com.dongbantest.app.ui.v3.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dongbantest.app.ui.v3.components.DbIcon
import com.dongbantest.app.ui.v3.components.FULL_PX
import com.dongbantest.app.ui.v3.components.PhotoImage
import com.dongbantest.app.ui.v3.components.Text
import com.dongbantest.app.ui.v3.components.readPhotoAspect
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbLayout
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * S14 다이어리의 사진 칸(나눈 이야기 위).
 * 접힘: 정방형 썸네일 한 줄. 다 못 보이면 마지막 칸에 "+N", 머리줄에 "더보기".
 * 펼침: 원래 비율 그대로 엇갈려 쌓는 목록(masonry). 사진을 누르면 크게 보기(넘겨 보기).
 */

/** 접힌 줄의 썸네일 수. 중간 폭(Expanded)은 오른쪽 칸이 좁아 3장. */
@Composable
private fun thumbCount(): Int = if (DbTheme.layout == DbLayout.Expanded) 3 else 4

@Composable
internal fun DiaryPhotos(state: DiaryUiState, modifier: Modifier = Modifier) {
    val c = DbTheme.colors
    val t = DbTheme.type
    val r = state.record
    val photos = r.photos
    if (photos.isEmpty()) return
    var expanded by remember(r.id) { mutableStateOf(false) }
    var viewing by remember(r.id) { mutableStateOf<Int?>(null) }
    // 펼친 목록은 원래 비율로 쌓는다. 파일이 있으면 파일에서 비율을 읽는다(화면 스레드 밖).
    val aspects by produceState(photos.map { it.aspect }, photos) {
        value = withContext(Dispatchers.IO) { photos.map { p -> p.path?.let(::readPhotoAspect) ?: p.aspect } }
    }
    val n = thumbCount()
    val more = photos.size > n

    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(state.photosTitle, style = t.titleLg, color = c.ink, modifier = Modifier.semantics { heading() })
                Text("${photos.size}", style = t.titleLg, color = c.muted)
            }
            if (more) {
                MoreToggle(
                    if (expanded) state.photosLess else state.photosMore,
                    if (expanded) DbIcons.ChevronUp else DbIcons.ChevronDown,
                ) { expanded = !expanded }
            }
        }
        AnimatedContent(
            targetState = expanded,
            modifier = Modifier.padding(top = Spacing.sm).fillMaxWidth(),
            transitionSpec = {
                fadeIn(tween(220, delayMillis = 80)) togetherWith fadeOut(tween(100)) using
                    SizeTransform(clip = true) { _, _ -> tween(300) }
            },
            contentAlignment = Alignment.TopStart,
            label = "Diary photos",
        ) { open ->
            if (open) {
                PhotoMasonry(photos, aspects, onOpen = { viewing = it })
            } else {
                ThumbRow(photos, n, onOpen = { viewing = it }, onMore = { expanded = true })
            }
        }
    }
    viewing?.let { start -> PhotoViewer(photos, start, onClose = { viewing = null }) }
}

/** 머리줄 오른쪽 "더보기 ⌄ / 접기 ⌃" */
@Composable
private fun MoreToggle(label: String, icon: ImageVector, onClick: () -> Unit) {
    val c = DbTheme.colors
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(DbShapes.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = Spacing.sm, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2xs),
    ) {
        Text(label, style = DbTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold), color = c.ink2)
        DbIcon(icon, 18.dp, c.ink2)
    }
}

/** 정방형 썸네일 한 줄. 사진이 더 있으면 마지막 칸을 어둡게 덮고 "+N"(누르면 펼침). */
@Composable
private fun ThumbRow(photos: List<PhotoUi>, n: Int, onOpen: (Int) -> Unit, onMore: () -> Unit) {
    val c = DbTheme.colors
    val more = photos.size - n
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        repeat(n) { i ->
            val p = photos.getOrNull(i)
            Box(Modifier.weight(1f).aspectRatio(1f)) {
                if (p == null) return@Box
                val cover = more > 0 && i == n - 1
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(DbShapes.button)
                        .clickable(role = Role.Button, onClickLabel = if (cover) "사진 ${more}장 더 보기" else null) {
                            if (cover) onMore() else onOpen(i)
                        }
                        .semantics { contentDescription = if (cover) "사진 ${more}장 더" else photoLabel(p) },
                ) {
                    PhotoImage(p.path, null, Modifier.fillMaxSize())
                    if (cover) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Text("+$more", style = DbTheme.type.titleLg, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** 원래 비율대로 엇갈려 쌓는다: 사진마다 지금 가장 짧은 열에 넣는다. 넓으면 3열, 아니면 2열. */
@Composable
private fun PhotoMasonry(photos: List<PhotoUi>, aspects: List<Float>, onOpen: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = if (maxWidth >= 560.dp) 3 else 2
        val columns = remember(photos, aspects, cols) {
            val heights = FloatArray(cols)
            val out = List(cols) { mutableListOf<Int>() }
            photos.indices.forEach { i ->
                val col = heights.indices.minBy { heights[it] }
                out[col] += i
                heights[col] += 1f / aspects.getOrElse(i) { 1f }.coerceIn(0.3f, 3f)
            }
            out
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            columns.forEach { col ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    col.forEach { i ->
                        val p = photos[i]
                        PhotoImage(
                            p.path, photoLabel(p),
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspects.getOrElse(i) { 1f }.coerceIn(0.3f, 3f))
                                .clip(DbShapes.button)
                                .clickable(role = Role.Button, onClickLabel = "크게 보기") { onOpen(i) },
                        )
                    }
                }
            }
        }
    }
}

private fun photoLabel(p: PhotoUi) = listOf(p.time, p.caption).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "사진" }

/** 사진 크게 보기: 어두운 바탕에 사진 하나, 옆으로 넘겨 다음 사진. 닫기·뒤로 가기로 닫는다. */
@Composable
private fun PhotoViewer(photos: List<PhotoUi>, start: Int, onClose: () -> Unit) {
    // 대화상자는 새 창이라 기기 밀도로 돌아간다. v3 화면 배율(창 크기로 정한 LocalDensity)을 그대로 넘긴다.
    val density = LocalDensity.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) { CompositionLocalProvider(LocalDensity provides density) {
        BackHandler(onBack = onClose)
        val pager = rememberPagerState(initialPage = start) { photos.size }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f))) {
            HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = Spacing.md) { page ->
                val p = photos[page]
                Box(
                    // 사진 바깥 빈 곳을 누르면 닫는다.
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                        .padding(horizontal = 72.dp, vertical = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (p.path != null) {
                        PhotoImage(p.path, photoLabel(p), Modifier.fillMaxSize(), contentScale = ContentScale.Fit, maxPx = FULL_PX, placeholder = false)
                    } else {
                        // 예시 화면: 사진 자리를 그 비율로
                        PhotoImage(null, photoLabel(p), Modifier.fillMaxWidth(0.6f).aspectRatio(p.aspect).clip(DbShapes.card))
                    }
                }
            }
            val p = photos[pager.currentPage]
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = Spacing.xs)) {
                    Text("${pager.currentPage + 1} / ${photos.size}", style = DbTheme.type.title, color = Color.White)
                    val sub = listOf(p.time, p.caption).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(sub, style = DbTheme.type.bodySm, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                ViewerButton(DbIcons.X, "닫기", onClick = onClose)
            }
            if (pager.currentPage > 0) {
                ViewerButton(DbIcons.ChevronLeft, "이전 사진", Modifier.align(Alignment.CenterStart).padding(start = Spacing.md)) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                }
            }
            if (pager.currentPage < photos.lastIndex) {
                ViewerButton(DbIcons.ChevronRight, "다음 사진", Modifier.align(Alignment.CenterEnd).padding(end = Spacing.md)) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
            }
        }
    }
    }
}

/** 크게 보기의 둥근 버튼(누르는 곳 48dp) */
@Composable
private fun ViewerButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(48.dp)
            .clip(DbShapes.pill)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        DbIcon(icon, 22.dp, Color.White)
    }
}

