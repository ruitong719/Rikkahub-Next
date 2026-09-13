package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.TodoItem
import me.rerere.rikkahub.data.ai.tools.local.TodoStatus
import me.rerere.rikkahub.data.model.GoalState
import me.rerere.rikkahub.data.model.GoalStatus
import me.rerere.rikkahub.data.model.GoalVerdict
import me.rerere.rikkahub.data.model.GoalVerdictKind

/**
 * GOAL 模式面板：从底栏模式选单点 GOAL 卡片打开。
 * 展示目标条件、状态、已评估轮数、待办完成度与逐轮评估判决历史，并提供「停止」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalPanel(
    goal: GoalState?,
    todos: List<TodoItem>,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.goal_panel_title),
                style = MaterialTheme.typography.titleLarge,
            )

            if (goal == null) {
                Text(
                    text = stringResource(R.string.goal_panel_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // 状态 + 轮数
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusContainerColor(goal.status),
                ) {
                    Text(
                        text = stringResource(statusLabelRes(goal.status)),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusContentColor(goal.status),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.goal_panel_turns, goal.turnCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LabeledBlock(title = stringResource(R.string.goal_panel_condition)) {
                Text(
                    text = goal.condition.ifBlank { stringResource(R.string.goal_panel_condition_unset) },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (goal.request.isNotBlank() && goal.request != goal.condition) {
                LabeledBlock(title = stringResource(R.string.goal_panel_request)) {
                    Text(
                        text = goal.request,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (todos.isNotEmpty()) {
                val done = todos.count {
                    it.status == TodoStatus.COMPLETED || it.status == TodoStatus.CANCELLED
                }
                LabeledBlock(title = stringResource(R.string.goal_panel_todos)) {
                    Text(
                        text = stringResource(R.string.goal_panel_todos_progress, done, todos.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (goal.history.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.goal_panel_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                goal.history.asReversed().forEach { verdict -> GoalVerdictCard(verdict) }
            }

            if (goal.status == GoalStatus.ACTIVE) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.goal_panel_stop))
                }
            }
        }
    }
}

@Composable
private fun LabeledBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/** E3：单轮评估判决卡（故意不进消息 schema，避免序列化迁移） */
@Composable
private fun GoalVerdictCard(verdict: GoalVerdict) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.goal_panel_verdict_turn, verdict.atTurn),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(verdictLabelRes(verdict.kind)),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (verdict.kind) {
                        GoalVerdictKind.ACHIEVED -> MaterialTheme.colorScheme.primary
                        GoalVerdictKind.IMPOSSIBLE -> MaterialTheme.colorScheme.error
                        GoalVerdictKind.NOT_MET -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (verdict.reason.isNotBlank()) {
                Text(
                    text = verdict.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun statusLabelRes(status: GoalStatus): Int = when (status) {
    GoalStatus.ACTIVE -> R.string.goal_status_active
    GoalStatus.ACHIEVED -> R.string.goal_status_achieved
    GoalStatus.IMPOSSIBLE -> R.string.goal_status_impossible
    GoalStatus.STOPPED -> R.string.goal_status_stopped
}

private fun verdictLabelRes(kind: GoalVerdictKind): Int = when (kind) {
    GoalVerdictKind.NOT_MET -> R.string.goal_verdict_not_met
    GoalVerdictKind.ACHIEVED -> R.string.goal_verdict_achieved
    GoalVerdictKind.IMPOSSIBLE -> R.string.goal_verdict_impossible
}

@Composable
private fun statusContainerColor(status: GoalStatus) = when (status) {
    GoalStatus.ACTIVE -> MaterialTheme.colorScheme.tertiaryContainer
    GoalStatus.ACHIEVED -> MaterialTheme.colorScheme.primaryContainer
    GoalStatus.IMPOSSIBLE -> MaterialTheme.colorScheme.errorContainer
    GoalStatus.STOPPED -> MaterialTheme.colorScheme.surfaceContainerHigh
}

@Composable
private fun statusContentColor(status: GoalStatus) = when (status) {
    GoalStatus.ACTIVE -> MaterialTheme.colorScheme.onTertiaryContainer
    GoalStatus.ACHIEVED -> MaterialTheme.colorScheme.onPrimaryContainer
    GoalStatus.IMPOSSIBLE -> MaterialTheme.colorScheme.onErrorContainer
    GoalStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}
