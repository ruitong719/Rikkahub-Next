package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * MCP 工具调用（动态名 `mcp__<server>__<tool>` 经注册表前缀匹配）：
 * 折叠标题展示 server / tool，摘要取输出首行；详情沿用通用 JSON 视图
 * （MCP 输出结构由第三方 server 定义，不做假设）。
 */
object McpToolUI : ToolUIRenderer {
    override val toolName: String = "mcp"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.McpServer

    private fun rawTextOf(context: ToolUIContext): String =
        context.tool.output
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()

    @Composable
    override fun title(context: ToolUIContext): String {
        val (server, tool) = splitName(context.tool.toolName)
        return if (server != null && tool != null) {
            stringResource(R.string.tool_ui_mcp, server, tool)
        } else {
            stringResource(R.string.tool_ui_mcp_default)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = rawTextOf(context).isNotBlank()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val firstLine = rawTextOf(context).lineSequence().firstOrNull { it.isNotBlank() } ?: return
        Text(
            text = firstLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .shimmer(isLoading = context.loading),
        )
    }

    /** `mcp__<server>__<tool>` -> server / tool；格式不符返回 (null, null) */
    private fun splitName(name: String): Pair<String?, String?> {
        val rest = name.removePrefix("mcp__")
        val index = rest.indexOf("__")
        if (index <= 0 || index + 2 >= rest.length) return null to null
        return rest.take(index) to rest.substring(index + 2)
    }
}
