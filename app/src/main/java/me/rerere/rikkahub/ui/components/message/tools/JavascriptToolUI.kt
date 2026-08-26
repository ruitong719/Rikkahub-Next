package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import me.rerere.highlight.CodeHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * eval_javascript 渲染器：标题与摘要展示执行的代码（截断），
 * 详情为完整代码 + console 输出 + 求值结果，加载中占位，
 * 仅执行异常（无输出且非加载中）才回退通用 JSON 视图。
 */
object JavascriptToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 40

    override val toolName: String = "eval_javascript"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    private fun codeOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("code")

    @Composable
    override fun title(context: ToolUIContext): String {
        val code = codeOf(context)?.replace("\n", " ")?.trim()
            ?: return stringResource(R.string.tool_ui_js_default)
        val truncated = if (code.length > TITLE_MAX_CHARS) code.take(TITLE_MAX_CHARS) + "…" else code
        return stringResource(R.string.tool_ui_js, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = codeOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val code = codeOf(context) ?: return
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .shimmer(isLoading = context.loading),
        ) {
            CodeHighlightText(
                code = code,
                language = "javascript",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
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
                text = stringResource(R.string.tool_ui_js_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .shimmer(isLoading = context.loading),
            )
            return
        }
        JavascriptPreview(context)
    }

    /** 详情：代码块 + console 输出 + 求值结果 */
    @Composable
    private fun JavascriptPreview(context: ToolUIContext) {
        val code = codeOf(context).orEmpty()
        val logs = context.content.getStringContent("logs")
        val result = context.content.getStringContent("result")
        Column(
            modifier = Modifier
                .fillMaxHeight(0.7f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HighlightCodeBlock(
                code = code,
                language = "javascript",
                modifier = Modifier.fillMaxWidth(),
            )
            if (!logs.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.tool_ui_js_logs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HighlightCodeBlock(
                    code = logs,
                    language = "bash",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!result.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.tool_ui_js_result),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HighlightCodeBlock(
                    code = result,
                    language = "json",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
