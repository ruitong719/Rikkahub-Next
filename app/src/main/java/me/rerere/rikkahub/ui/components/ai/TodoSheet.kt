package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.TodoItem
import me.rerere.rikkahub.data.ai.tools.local.TodoStatus
import me.rerere.rikkahub.ui.components.ui.ToggleSurface

/**
 * 输入框工具条上的 todo 入口：图标 + 未完成数角标。
 * 样式对齐 ReasoningButton（ToggleSurface），无用户交互之外的语义——面板纯展示，
 * 完成状态只由模型通过 todowrite 工具更新。
 */
@Composable
fun TodoStatusButton(
    activeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleSurface(
        checked = activeCount > 0,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = {
                    if (activeCount > 0) {
                        Badge { Text(activeCount.coerceAtMost(99).toString()) }
                    }
                },
            ) {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.LeftToRightListBullet,
                        contentDescription = stringResource(R.string.todo_status_button),
                    )
                }
            }
        }
    }
}

/** 对话内 todo 列表面板：展示列表，支持清空整个当前 todolist。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoSheet(
    todos: List<TodoItem>,
    onDismiss: () -> Unit,
    onClearTodos: () -> Unit = {},
) {
    val active = todos.filter { it.status == TodoStatus.PENDING || it.status == TodoStatus.IN_PROGRESS }
    val done = todos.filter { it.status == TodoStatus.COMPLETED || it.status == TodoStatus.CANCELLED }
    var showClearConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // todo 多时内容会超出屏幕：整列可滚动，否则底部被裁剪且拉不动
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.todo_sheet_title, active.size, todos.size),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (todos.isNotEmpty()) {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            imageVector = HugeIcons.Delete02,
                            contentDescription = stringResource(R.string.todo_sheet_clear),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (todos.isEmpty()) {
                Text(
                    text = stringResource(R.string.todo_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                active.forEach { TodoRow(it) }
                if (done.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.todo_sheet_completed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    done.forEach { TodoRow(it) }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.todo_sheet_clear_confirm_title)) },
            text = { Text(stringResource(R.string.todo_sheet_clear_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearTodos()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.todo_sheet_clear_confirm_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.todo_sheet_clear_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun TodoRow(item: TodoItem) {
    val finished = item.status == TodoStatus.COMPLETED || item.status == TodoStatus.CANCELLED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 状态圆点：primary 实心=已完成，tertiary=进行中，灰空心=待办/已取消
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(
                    when (item.status) {
                        TodoStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        TodoStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (finished) {
                Icon(
                    imageVector = HugeIcons.Tick02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (finished) TextDecoration.LineThrough else null,
                color = if (finished) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
