package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.longOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.modifier.shimmer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * create_backup 渲染器：标题固定；摘要为产物一行（文件名 · 大小）；
 * 详情为成功卡片（产物名 + 路径 + 大小 · 时间 + 说明文案），加载中占位，
 * 仅执行异常（无输出且非加载中）才回退通用 JSON 视图。
 */
object BackupToolUI : ToolUIRenderer {
    override val toolName: String = "create_backup"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Package01

    override fun hasSummary(context: ToolUIContext): Boolean {
        if (!context.loading && context.content == null) return false
        return true
    }

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.tool_ui_backup)

    @Composable
    override fun Summary(context: ToolUIContext) {
        val name = context.content.getStringContent("name")
        if (name == null) {
            Text(
                text = stringResource(R.string.tool_ui_backup_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.shimmer(isLoading = context.loading),
            )
            return
        }
        val sizeBytes = context.content?.jsonObjectOrNull?.get("sizeBytes")
            ?.jsonPrimitiveOrNull?.longOrNull
        Text(
            text = buildString {
                append(name)
                if (sizeBytes != null) append(" · ${formatSize(sizeBytes)}")
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
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
            Text(
                text = stringResource(R.string.tool_ui_backup_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .shimmer(isLoading = context.loading),
            )
            return
        }
        BackupPreview(context.content)
    }

    /** 成功卡片：产物名 + 路径 + 大小 · 本地时间 + 说明文案 */
    @Composable
    private fun BackupPreview(content: JsonElement) {
        val path = content.getStringContent("path").orEmpty()
        val name = content.getStringContent("name").orEmpty()
        val sizeBytes = content.jsonObjectOrNull?.get("sizeBytes")?.jsonPrimitiveOrNull?.longOrNull
        val createdAt = content.jsonObjectOrNull?.get("createdAt")?.jsonPrimitiveOrNull?.longOrNull
        val message = content.getStringContent("message")
        Column(
            modifier = Modifier
                .fillMaxHeight(0.4f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (path.isNotBlank()) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sizeBytes != null || createdAt != null) {
                Text(
                    text = buildString {
                        if (sizeBytes != null) append(formatSize(sizeBytes))
                        if (createdAt != null) {
                            append(" · ")
                            append(
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(Date(createdAt))
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "${bytes}B"
    }
}
