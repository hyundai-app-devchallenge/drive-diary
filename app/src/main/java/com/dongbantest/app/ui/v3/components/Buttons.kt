package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

enum class ButtonKind { Primary, Outline, Text, Plain }

/**
 * 버튼(.btn). 높이 48dp 이상, 글자 16/600.
 * block = 폭을 채운다(.btn-block). 넓은 화면에서는 웹처럼 최대 480dp(capWide = false면 제한 없음).
 */
@Composable
fun DbButton(
    text: String,
    onClick: () -> Unit,
    kind: ButtonKind,
    modifier: Modifier = Modifier,
    block: Boolean = false,
    capWide: Boolean = true,
    enabled: Boolean = true,
    /** 편집처럼 아직 안 되는 동작(.btn-off): 회색 글자, 누르지 않음 */
    off: Boolean = false,
) {
    val c = DbTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg = when (kind) {
        ButtonKind.Primary -> if (pressed) c.actionPressed else c.action
        ButtonKind.Outline -> if (pressed) c.paper3 else c.paper
        ButtonKind.Text, ButtonKind.Plain -> if (pressed && !off) c.paper3 else Color.Transparent
    }
    val fg = when {
        off -> c.muted
        kind == ButtonKind.Primary -> c.accentInk
        else -> c.ink
    }
    val wide = DbTheme.layout.wide
    var m = modifier
    if (block) m = (if (wide && capWide) m.widthIn(max = 480.dp) else m).fillMaxWidth()
    Box(
        m
            .heightIn(min = 48.dp)
            .clip(DbShapes.button)
            .background(bg)
            .then(if (kind == ButtonKind.Outline) Modifier.border(1.dp, c.ink, DbShapes.button) else Modifier)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled && !off,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (kind == ButtonKind.Plain) Spacing.sm else Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = DbTheme.type.button.copy(
                textDecoration = if (kind == ButtonKind.Text) TextDecoration.Underline else null,
            ),
            color = if (enabled) fg else fg.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, block: Boolean = true, capWide: Boolean = true) =
    DbButton(text, onClick, ButtonKind.Primary, modifier, block, capWide)

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, block: Boolean = true, capWide: Boolean = true) =
    DbButton(text, onClick, ButtonKind.Outline, modifier, block, capWide)

/** 밑줄 글자 버튼(.btn-text). 보조 동작. */
@Composable
fun DbTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, block: Boolean = true, capWide: Boolean = true) =
    DbButton(text, onClick, ButtonKind.Text, modifier, block, capWide)

/** 48dp 터치 영역 안의 40dp 원 버튼(.circle). 뒤로, 인원 +/- */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    face: Color = DbTheme.colors.paper3,
) {
    val c = DbTheme.colors
    Box(
        modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, indication = null, interactionSource = null, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(DbShapes.pill).background(face))
        DbIcon(icon, 20.dp, if (enabled) c.ink else c.ink.copy(alpha = 0.55f))
    }
}

/** 다이어리 재생 버튼(.play): 48dp 채운 원 */
@Composable
fun PlayButton(playing: Boolean, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = DbTheme.colors
    Box(
        modifier
            .size(48.dp)
            .clip(DbShapes.pill)
            .background(if (playing) c.actionPressed else c.action)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        DbIcon(if (playing) com.dongbantest.app.ui.v3.theme.DbIcons.Pause else com.dongbantest.app.ui.v3.theme.DbIcons.Play, 20.dp, c.accentInk)
    }
}

/** 두 버튼을 반씩(.actions-row) */
@Composable
fun ButtonRow(gap: Dp = Spacing.sm, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), content = content)
}
