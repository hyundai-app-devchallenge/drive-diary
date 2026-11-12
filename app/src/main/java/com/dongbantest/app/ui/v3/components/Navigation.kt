package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

private fun DbTab.icon() = when (this) {
    DbTab.Home -> DbIcons.House
    DbTab.Trip -> DbIcons.Car
    DbTab.Record -> DbIcons.MapIcon
}

@Composable
private fun TabItem(tab: DbTab, selected: Boolean, onTab: (DbTab) -> Unit, modifier: Modifier) {
    val c = DbTheme.colors
    val fg = if (selected) c.ink else c.muted
    Column(
        modifier
            .selectable(selected = selected, role = Role.Tab, onClick = { onTab(tab) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s3xs, Alignment.CenterVertically),
    ) {
        DbIcon(tab.icon(), 24.dp, fg)
        Text(tab.label, style = DbTheme.type.label, color = fg)
        Box(Modifier.width(24.dp).height(2.dp).clip(DbShapes.pill).background(if (selected) c.ink else Color.Transparent))
    }
}

/** 하단 탭바(Compact): 높이 60, 위 선, 3칸 같은 폭 */
@Composable
fun DbNavigationBar(selected: DbTab, onTab: (DbTab) -> Unit, modifier: Modifier = Modifier) {
    val c = DbTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(c.paper)
            .topRule(c.rule),
    ) {
        DbTab.entries.forEach { t ->
            TabItem(t, t == selected, onTab, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** 왼쪽 레일(Expanded·Full): 폭 88, 오른쪽 선, 칸 높이 72 */
@Composable
fun DbNavigationRail(selected: DbTab, onTab: (DbTab) -> Unit, modifier: Modifier = Modifier) {
    val c = DbTheme.colors
    Column(
        modifier
            .width(88.dp)
            .fillMaxHeight()
            .background(c.paper)
            .endRule(c.rule)
            .padding(vertical = Spacing.md, horizontal = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        DbTab.entries.forEach { t ->
            TabItem(t, t == selected, onTab, Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(DbShapes.button))
        }
    }
}
