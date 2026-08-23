package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Task01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.BgTaskStatus
import me.rerere.rikkahub.data.files.WorkspaceBgManager
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

/**
 * 后台任务监看面板：列出当前工作区的后台任务（命令 / 状态 / 时间），行尾删除按钮。
 * 点击任务行打开 [BackgroundTaskOutputSheet] 查看实时输出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundTaskSheet(
    tasks: List<WorkspaceBgTaskInfo>,
    onTaskClick: (WorkspaceBgTaskInfo) -> Unit,
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
                        modifier = Modifier.clickable { onTaskClick(task) },
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
                    ) {
                        Text(
                            text = task.command.ifBlank { task.taskId },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
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

/** 输出详情轮询间隔与尾部窗口（stdout.log 可能持续增长，只取尾部渲染） */
private const val BG_TASK_OUTPUT_POLL_MS = 1_000L
private const val BG_TASK_OUTPUT_TAIL_LINES = 500
private const val BG_TASK_OUTPUT_MAX_BYTES = 64 * 1024

/**
 * 后台任务输出详情：点击任务列表行打开。
 *
 * 运行中的任务每 [BG_TASK_OUTPUT_POLL_MS] 轮询一次 stdout.log 尾部并自动吸底；
 * 任务结束（含查看期间结束）后停止轮询。输出只读，kill/delete 在列表行操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundTaskOutputSheet(
    task: WorkspaceBgTaskInfo,
    workspaceRoot: String,
    bgManager: WorkspaceBgManager,
    onDismiss: () -> Unit,
) {
    var info by remember(task.taskId) { mutableStateOf(task) }
    var output by remember(task.taskId) { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(task.taskId) {
        while (isActive) {
            info = runCatching { bgManager.taskInfo(workspaceRoot, task.taskId) }.getOrNull() ?: info
            output = runCatching {
                bgManager.output(
                    workspaceRoot = workspaceRoot,
                    taskId = task.taskId,
                    tailLines = BG_TASK_OUTPUT_TAIL_LINES,
                    maxBytes = BG_TASK_OUTPUT_MAX_BYTES,
                )
            }.getOrDefault(output)
            if (info.status != BgTaskStatus.RUNNING) break
            delay(BG_TASK_OUTPUT_POLL_MS)
        }
    }

    // 运行中新输出到达时吸底；已结束后允许自由回看
    LaunchedEffect(output, info.status) {
        if (info.status == BgTaskStatus.RUNNING && scrollState.maxValue > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = task.command.ifBlank { task.taskId },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(statusLabelRes(info.status)),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (info.status) {
                        BgTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        BgTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
                Text(
                    text = info.taskId.take(8),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (info.status == BgTaskStatus.RUNNING) {
                    Text(
                        text = stringResource(R.string.bg_task_output_live),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (info.stdoutSizeBytes > BG_TASK_OUTPUT_MAX_BYTES) {
                Text(
                    text = stringResource(R.string.bg_task_output_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(scrollState)
                        .padding(12.dp)
                ) {
                    Text(
                        text = output.ifBlank { stringResource(R.string.bg_task_output_empty) },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}
