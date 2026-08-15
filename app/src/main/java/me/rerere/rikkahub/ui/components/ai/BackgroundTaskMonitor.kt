package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Task01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.BgTaskStatus
import me.rerere.rikkahub.data.files.WorkspaceBgTaskInfo
import me.rerere.rikkahub.ui.components.ui.ToggleSurface

/**
 * 输入框工具条上的后台任务入口：仅在存在后台任务时显示，
 * 角标 = 任务总数。样式对齐 SubAgentMonitorButton（ToggleSurface）。
 */
@Composable
fun BackgroundTaskButton(
    runningCount: Int,
    totalCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleSurface(
        checked = runningCount > 0,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = {
                    if (totalCount > 0) {
                        Badge { Text(totalCount.coerceAtMost(99).toString()) }
                    }
                },
            ) {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.Task01,
                        contentDescription = stringResource(R.string.bg_task_button),
                    )
                }
            }
        }
    }
}

/** 后台任务监看面板：列出当前工作区的后台任务（命令 / 状态 / 时间），行尾删除按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundTaskSheet(
    tasks: List<WorkspaceBgTaskInfo>,
    onKill: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.bg_task_sheet_title, tasks.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (tasks.isEmpty()) {
                Text(
                    text = stringResource(R.string.bg_task_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                tasks.forEach { task ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = task.command.ifBlank { task.taskId },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(statusLabelRes(task.status)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (task.status) {
                                        BgTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                                        BgTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.outline
                                    },
                                )
                                Text(
                                    text = task.taskId.take(8),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (task.status == BgTaskStatus.RUNNING) {
                                    IconButton(onClick = { onKill(task.taskId) }) {
                                        Icon(
                                            imageVector = HugeIcons.Cancel01,
                                            contentDescription = stringResource(R.string.bg_task_kill),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                IconButton(onClick = { onDelete(task.taskId) }) {
                                    Icon(
                                        imageVector = HugeIcons.Delete01,
                                        contentDescription = stringResource(R.string.bg_task_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = HugeIcons.Refresh01,
                        contentDescription = stringResource(R.string.bg_task_refresh),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLabelRes(status: BgTaskStatus): Int = when (status) {
    BgTaskStatus.RUNNING -> R.string.bg_task_status_running
    BgTaskStatus.DONE -> R.string.bg_task_status_done
    BgTaskStatus.FAILED -> R.string.bg_task_status_failed
}
