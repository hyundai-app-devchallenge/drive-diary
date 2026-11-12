package com.dongbantest.app.ui.v3.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** --radius-* 토큰 */
object DbShapes {
    val button = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(14.dp)
    val dialog = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val pill = CircleShape
}

/** --space-* 토큰 */
object Spacing {
    val s3xs = 2.dp
    val s2xs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}
