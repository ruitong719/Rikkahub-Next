package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.TodoItem
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 输入框工具条上的 todo 入口：图标 + 未完成数角标。
 * 样式对齐 ReasoningButton（ToggleSurface），无用户交互之外的语义——面板纯展示，
 * 完成/取消完成只由模型通过 todo_complete 工具更新。
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
                    modifier = Modifier.size(24.dp),
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

/** 对话内 todo 列表面板：纯只读展示，无任何可点交互。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoSheet(
    todos: List<TodoItem>,
    onDismiss: () -> Unit,
) {
    val active = todos.filter { !it.completed }
    val done = todos.filter { it.completed }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.todo_sheet_title, active.size, todos.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

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
                    done.forEach { TodoRow(it, completed = true) }
                }
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, completed: Boolean = item.completed) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 纯展示的状态圆点：实心 = 已完成，空心 = 未完成
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(
                    if (completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (completed) {
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
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                color = if (completed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatCreatedAt(item.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun formatCreatedAt(iso: String): String = runCatching {
    OffsetDateTime.parse(iso)
        .atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M/d HH:mm"))
}.getOrDefault(iso)
