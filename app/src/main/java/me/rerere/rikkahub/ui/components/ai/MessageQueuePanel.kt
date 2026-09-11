package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.QueuedUserMessage

/**
 * 生成期间排队消息的气泡列表。
 *
 * 每条排队消息渲染为一个右对齐气泡（内含消息内容）：点击气泡展开/收起；
 * 展开后气泡尾部出现删除按钮，点击移除该条排队消息。同时只展开一个。
 * 队列条数角标由 ChatInput 自带的 BadgedBox 展示。
 */
@Composable
internal fun MessageQueuePanel(
    state: List<QueuedUserMessage>,
    onRemove: (Uuid) -> Unit,
) {
    if (state.isEmpty()) return
    var expandedId by remember { mutableStateOf<Uuid?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("chat_message_queue"),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.forEach { message ->
            val expanded = expandedId == message.id
            QueueBubble(
                message = message,
                expanded = expanded,
                onClick = { expandedId = if (expanded) null else message.id },
                onRemove = {
                    onRemove(message.id)
                    if (expanded) expandedId = null
                },
            )
        }
    }
}

@Composable
private fun QueueBubble(
    message: QueuedUserMessage,
    expanded: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = message.queuePreview(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (expanded) 204.dp else 256.dp),
            )
            if (expanded) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.chat_page_queue_remove),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 拼接气泡预览文本：正文原样输出，附件按类型显示占位标签 */
@Composable
private fun QueuedUserMessage.queuePreview(): String {
    val attachmentLabels = listOf(
        stringResource(R.string.chat_page_queue_image),
        stringResource(R.string.chat_page_queue_file),
        stringResource(R.string.chat_page_queue_audio),
        stringResource(R.string.chat_page_queue_video),
    )
    return parts.joinToString(" ") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Image -> attachmentLabels[0]
            is UIMessagePart.Document -> attachmentLabels[1]
            is UIMessagePart.Audio -> attachmentLabels[2]
            is UIMessagePart.Video -> attachmentLabels[3]
            else -> ""
        }
    }.trim().ifBlank { "…" }
}
