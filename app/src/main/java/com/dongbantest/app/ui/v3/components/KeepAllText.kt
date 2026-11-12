package com.dongbantest.app.ui.v3.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text as M3Text

private fun Char.isHangul() = this in '가'..'힣' || this in 'ᄀ'..'ᇿ' || this in '㄰'..'㆏'

/**
 * 웹의 word-break: keep-all 처럼 한국어 어절 안에서는 줄을 바꾸지 않게 한다.
 * 한글이 낀 두 글자 사이(공백 아님)에 WORD JOINER(U+2060, 보이지 않음)를 넣는다.
 */
fun keepAll(s: String): String {
    if (s.none { it.isHangul() }) return s
    val b = StringBuilder(s.length + s.length / 2)
    for (i in s.indices) {
        val c = s[i]
        b.append(c)
        val n = s.getOrNull(i + 1) ?: continue
        if (!c.isWhitespace() && !n.isWhitespace() && (c.isHangul() || n.isHangul())) b.append('⁠')
    }
    return b.toString()
}

/** v3 화면의 글자. Material3 Text 와 같고 한국어 줄바꿈만 어절 단위다. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle,
) {
    val shown = remember(text) { keepAll(text) }
    M3Text(shown, modifier, color = color, textAlign = textAlign, overflow = overflow, maxLines = maxLines, style = style)
}
