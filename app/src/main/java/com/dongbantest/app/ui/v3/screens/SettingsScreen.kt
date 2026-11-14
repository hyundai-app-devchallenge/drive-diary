package com.dongbantest.app.ui.v3.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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

/** 질문 뒤 녹음하지 않는 시간 선택지(초). 0 = 빼지 않음. */
val AnswerWindowChoices = listOf(0, 30, 60, 90, 180, 300)

fun answerWindowLabel(seconds: Int): String = when {
    seconds == 0 -> "빼지 않음"
    seconds >= 120 && seconds % 60 == 0 -> "${seconds / 60}분"
    else -> "${seconds}초"
}

@Composable
fun SettingsScreen(
    micBoostEnabled: Boolean,
    restricted: Boolean,
    onMicBoostChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onTab: (DbTab) -> Unit,
    questionsEnabled: Boolean = true,
    onQuestionsEnabledChange: (Boolean) -> Unit = {},
    answerWindowSeconds: Int = 90,
    onAnswerWindowChange: (Int) -> Unit = {},
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
        val stoppedNote = "정차 후 바꿀 수 있어요."
        ToggleRow(
            title = "마이크 증폭",
            description = if (restricted) stoppedNote else "작게 들리는 목소리를 키워 주행 중 대화 인식을 도와요.",
            checked = micBoostEnabled,
            enabled = !restricted,
            onChange = onMicBoostChange,
            modifier = Modifier.padding(top = Spacing.lg),
        )
        // 조용할 때만 건네는 대화 질문 (talk-rec/docs/question-suggest)
        ToggleRow(
            title = "조용할 때 질문하기",
            description = if (restricted) stoppedNote else "차 안이 오래 조용하면 화면으로 먼저 묻고, 대답하면 질문을 하나 건네요.",
            checked = questionsEnabled,
            enabled = !restricted,
            onChange = onQuestionsEnabledChange,
            modifier = Modifier.padding(top = Spacing.md),
        )
        AnswerWindowRow(
            selected = answerWindowSeconds,
            enabled = !restricted,
            description = if (restricted) stoppedNote else "질문을 건넨 뒤 이 시간 동안 시작된 대화는 녹음·기록하지 않아요.",
            onSelect = onAnswerWindowChange,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DbTheme.colors
    val type = DbTheme.type
    Row(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(DbShapes.card)
            .background(colors.paper2)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2xs)) {
            Text(title, style = type.titleMd, color = colors.ink)
            Text(description, style = type.bodySm, color = colors.muted)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerWindowRow(
    selected: Int,
    enabled: Boolean,
    description: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DbTheme.colors
    val type = DbTheme.type
    Column(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(DbShapes.card)
            .background(colors.paper2)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("질문 뒤 녹음하지 않는 시간", style = type.titleMd, color = colors.ink)
        Text(description, style = type.bodySm, color = colors.muted)
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            AnswerWindowChoices.forEach { seconds ->
                val on = seconds == selected
                Text(
                    answerWindowLabel(seconds),
                    style = type.body,
                    color = if (on) colors.paper else colors.ink,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(DbShapes.pill)
                        .background(if (on) colors.ink else colors.paper)
                        .border(1.dp, colors.rule, DbShapes.pill)
                        .selectable(on, enabled = enabled, role = Role.RadioButton) { onSelect(seconds) }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }
    }
}
