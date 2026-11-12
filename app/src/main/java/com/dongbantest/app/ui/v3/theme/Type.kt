package com.dongbantest.app.ui.v3.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.dongbantest.app.R

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/** 웹 글자 크기 토큰(1rem = 16px = 16sp). */
object DbTextSize {
    val xs = 12.sp
    val caption = 13.sp
    val sm = 14.sp
    val hint = 15.sp
    val md = 16.sp
    val pill = 18.sp
    val titleMd = 20.sp
    val titleLg = 22.sp
    val q = 26.sp
    val display = 28.sp
    val qLg = 32.sp
    val qXl = 34.sp
    val signal = 64.sp
}

/** CSS line-height(배수)와 같은 줄 높이. 위아래 여백을 반씩 나눠 CSS처럼 가운데 둔다. */
fun dbStyle(size: TextUnit, weight: FontWeight, lineHeight: Float): TextStyle = TextStyle(
    // 한국어는 웹의 word-break: keep-all 처럼 띄어쓰기에서만 줄을 바꾼다(어절 단위). 제목은 줄 길이를 고르게.
    lineBreak = if (weight >= FontWeight.Bold) LineBreak.Heading else LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase),
    fontFamily = Pretendard,
    fontWeight = weight,
    fontSize = size,
    lineHeight = (size.value * lineHeight).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** 웹 클래스 이름 그대로의 글자 스타일. 색은 쓰는 곳에서 정한다. */
@Immutable
data class DbTypography(
    /** .display 28 / 700 / 1.25 */
    val display: TextStyle = dbStyle(DbTextSize.display, FontWeight.Bold, 1.25f),
    /** .title-lg 22 / 700 / 1.25 */
    val titleLg: TextStyle = dbStyle(DbTextSize.titleLg, FontWeight.Bold, 1.25f),
    /** 20 / 700 (조용한 면 제목, 태블릿 진행 알약) */
    val titleMd: TextStyle = dbStyle(DbTextSize.titleMd, FontWeight.Bold, 1.35f),
    /** .title 16 / 600 / 1.3 */
    val title: TextStyle = dbStyle(DbTextSize.md, FontWeight.SemiBold, 1.3f),
    /** .body 16 / 400 / 1.5 */
    val body: TextStyle = dbStyle(DbTextSize.md, FontWeight.Normal, 1.5f),
    /** .body-sm 14 / 400 / 1.43 */
    val bodySm: TextStyle = dbStyle(DbTextSize.sm, FontWeight.Normal, 1.43f),
    /** .caption 13 / 500 / 1.3 */
    val caption: TextStyle = dbStyle(DbTextSize.caption, FontWeight.Medium, 1.3f),
    /** 15 / 400 / 1.45 (말풍선, 안내 본문) */
    val hint: TextStyle = dbStyle(DbTextSize.hint, FontWeight.Normal, 1.45f),
    /** 15 / 600 (상태 줄, 녹음 칩) */
    val status: TextStyle = dbStyle(DbTextSize.hint, FontWeight.SemiBold, 1.3f),
    /** 18 / 700 (진행 알약 제목) */
    val pill: TextStyle = dbStyle(DbTextSize.pill, FontWeight.Bold, 1.3f),
    /** .q 질문 22 / 700 / 1.3 (폰 주행 26, 태블릿 32는 question*) */
    val question: TextStyle = dbStyle(DbTextSize.titleLg, FontWeight.Bold, 1.3f),
    val questionPhone: TextStyle = dbStyle(DbTextSize.q, FontWeight.Bold, 1.25f),
    val questionTablet: TextStyle = dbStyle(DbTextSize.qLg, FontWeight.Bold, 1.3f),
    /** .signal 64 / 700 */
    val signal: TextStyle = dbStyle(DbTextSize.signal, FontWeight.Bold, 1.05f),
    /** 12 / 600 (탭 이름, 지도 라벨) */
    val label: TextStyle = dbStyle(DbTextSize.xs, FontWeight.SemiBold, 1.3f),
    /** 버튼 16 / 600 / 1.2 */
    val button: TextStyle = dbStyle(DbTextSize.md, FontWeight.SemiBold, 1.2f),
)

val LocalDbTypography = staticCompositionLocalOf { DbTypography() }

/** 같은 스케일을 Material3 역할에 매핑(Material 컴포넌트를 쓸 때). */
fun materialTypography(t: DbTypography) = Typography(
    displayLarge = t.signal,
    displayMedium = dbStyle(DbTextSize.qXl, FontWeight.Bold, 1.25f),
    displaySmall = t.questionTablet,
    headlineLarge = t.display,
    headlineMedium = t.questionPhone,
    headlineSmall = t.titleLg,
    titleLarge = t.titleMd,
    titleMedium = t.pill,
    titleSmall = t.title,
    bodyLarge = t.body,
    bodyMedium = t.hint,
    bodySmall = t.bodySm,
    labelLarge = t.button,
    labelMedium = t.caption,
    labelSmall = t.label,
)
