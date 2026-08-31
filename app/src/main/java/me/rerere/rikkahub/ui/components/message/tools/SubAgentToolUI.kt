package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * 子代理渲染器（subagent_general 精确名 + subagent_<slug> 经注册表前缀匹配）：
 * 标题展示 label/slug；摘要为状态徽章 + 结果首行；
 * 详情为任务原文 + result 正文 + steps/tokens/runId 元信息，
 * 仅执行异常（无输出且非加载中）才回退通用 JSON 视图。
 */
object SubAgentToolUI : ToolUIRenderer {
    override val toolName: String = "subagent_general"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.AiBrain01

    private fun statusOf(context: ToolUIContext): String? =
        context.content.getStringContent("status")

    /** 展示名：general 用入参 label，预设用工具名去掉前缀的 slug */
    private fun displayNameOf(context: ToolUIContext): String =
        context.arguments.getStringContent("label")
            ?: context.tool.toolName.removePrefix("subagent_")

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.tool_ui_subagent, displayNameOf(context))

    @Composable
    private fun statusColor(status: String?) = when (status) {
        "success" -> DiffAddedColor
        null -> MaterialTheme.colorScheme.tertiary
        else -> DiffRemovedColor // error / timeout 及未知状态一律红
    }

    override fun hasSummary(context: ToolUIContext): Boolean {
        if (!context.loading && context.content == null) return false
        return true
    }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val status = statusOf(context)
        val steps = context.content?.jsonObjectOrNull?.get("steps")?.jsonPrimitiveOrNull?.intOrNull
        val result = context.content.getStringContent("result")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .shimmer(isLoading = context.loading),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(status)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = buildString {
                        append(status ?: "…")
                        if (steps != null) append(" · $steps steps")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!result.isNullOrBlank()) {
                    Text(
                        text = remember(result) { result.lines().firstOrNull().orEmpty() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
                text = stringResource(R.string.tool_ui_subagent_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .shimmer(isLoading = context.loading),
            )
            return
        }
        SubAgentPreview(context)
    }

    /** 详情：状态徽章 + 任务原文 + result 正文 + 元信息行 */
    @Composable
    private fun SubAgentPreview(context: ToolUIContext) {
        val content = context.content ?: return
        val status = content.getStringContent("status")
        val result = content.getStringContent("result").orEmpty()
        val steps = content.jsonObjectOrNull?.get("steps")?.jsonPrimitiveOrNull?.intOrNull
        val totalTokens = content.jsonObjectOrNull?.get("usage")
            ?.jsonObjectOrNull?.get("totalTokens")?.jsonPrimitiveOrNull?.longOrNull
        val runId = content.getStringContent("runId")
        val task = context.arguments.getStringContent("task").orEmpty()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.7f)
                .verticalScroll(rememberScrollState())
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
                        .background(statusColor(status)),
                )
                Text(
                    text = status ?: "…",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (task.isNotBlank()) {
                Text(
                    text = task,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.isNotBlank()) {
                // 报告以 markdown 渲染（与聊天正文一致），代替原来的带行号代码块
                MarkdownBlock(
                    content = result,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = buildString {
                    append("${steps ?: 0} steps")
                    if (totalTokens != null) append(" · $totalTokens tokens")
                    if (!runId.isNullOrBlank()) append(" · ${runId.take(8)}")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
