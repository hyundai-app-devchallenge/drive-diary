package com.dongbantest.app.ui.v3.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.components.AppShell
import com.dongbantest.app.ui.v3.components.CircleIconButton
import com.dongbantest.app.ui.v3.components.DbTab
import com.dongbantest.app.ui.v3.components.Text
import com.dongbantest.app.ui.v3.components.TopBarFrame
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

@Composable
fun SettingsScreen(
    micBoostEnabled: Boolean,
    restricted: Boolean,
    onMicBoostChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onTab: (DbTab) -> Unit,
) {
    val colors = DbTheme.colors
    val type = DbTheme.type
    AppShell(
        selectedTab = DbTab.Home,
        onTab = onTab,
        topBar = {
            TopBarFrame(divider = true) {
                CircleIconButton(DbIcons.ArrowLeft, "뒤로", onBack)
                Text("설정", style = type.titleMd, color = colors.ink, modifier = Modifier.semantics { heading() })
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .padding(top = Spacing.lg)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(DbShapes.card)
                .background(colors.paper2)
                .toggleable(micBoostEnabled, enabled = !restricted, role = Role.Switch, onValueChange = onMicBoostChange)
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2xs)) {
                Text("마이크 증폭", style = type.titleMd, color = colors.ink)
                val description = if (restricted) {
                    "정차 후 바꿀 수 있어요."
                } else {
                    "작게 들리는 목소리를 키워 주행 중 대화 인식을 도와요."
                }
                Text(description, style = type.bodySm, color = colors.muted)
            }
            Switch(checked = micBoostEnabled, onCheckedChange = null, enabled = !restricted)
        }
    }
}
