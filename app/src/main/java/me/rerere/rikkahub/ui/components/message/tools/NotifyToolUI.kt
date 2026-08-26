package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.booleanOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * AI 主动通知: 标题展示正文截断, 摘要为一行正文,
 * 详情为投递状态徽章 + 标题/正文; 加载中占位, 仅执行异常回退默认 JSON 视图
 */
object NotifyToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 40

    override val toolName: String = "notify"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Megaphone01

    private fun messageOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("message")

    @Composable
    override fun title(context: ToolUIContext): String {
        val message = messageOf(context)?.replace("\n", " ")?.trim()
            ?: return stringResource(R.string.tool_ui_notify_default)
        val truncated = if (message.length > TITLE_MAX_CHARS) message.take(TITLE_MAX_CHARS) + "…" else message
        return stringResource(R.string.tool_ui_notify, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = messageOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val message = messageOf(context)?.replace("\n", " ") ?: return
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .shimmer(isLoading = context.loading),
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        // 仅执行异常（无输出且不在加载中）时回退通用 JSON 视图
        if (context.content == null && !context.loading) {
            DefaultToolPreview(context = context)
            return
        }
        if (context.content == null) {
            PendingPreview(stringResource(R.string.tool_ui_notify_pending))
            return
        }
        NotifyPreview(context)
    }

    /** 详情：投递状态徽章 + 标题/正文 */
    @Composable
    private fun NotifyPreview(context: ToolUIContext) {
        val content = context.content ?: return
        val delivered = content.jsonObjectOrNull?.get("delivered")
            ?.jsonPrimitiveOrNull?.booleanOrNull ?: false
        val error = content.getStringContent("error")
        val title = context.arguments.getStringContent("title").orEmpty()
        val message = context.arguments.getStringContent("message").orEmpty()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (delivered) DiffAddedColor else DiffRemovedColor),
                )
                Text(
                    text = if (delivered) "sent" else "failed",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!delivered) {
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
