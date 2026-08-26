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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.highlight.CodeHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.TodoItem
import me.rerere.rikkahub.data.ai.tools.local.TodoStatus
import me.rerere.rikkahub.ui.components.ai.TodoRow
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant

/**
 * todowrite 渲染器（精简形态）：折叠标题显示完成进度；
 * 摘要为进度条 + 当前进行项；详情复用 TodoSheet 的分组清单。
 * todowrite 每次调用都是全量替换快照，渲染「本次提交后的列表」即可。
 */
object TodoWriteToolUI : ToolUIRenderer {
    override val toolName: String = "todowrite"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.LeftToRightListBullet

    private fun todosOf(context: ToolUIContext): List<TodoItem> {
        // 输出优先(已执行结果)，回退入参(未执行/审批中也能预览 AI 打算建立的清单)
        val array = context.content?.jsonObjectOrNull?.get("todos") as? JsonArray
            ?: context.arguments.jsonObjectOrNull?.get("todos") as? JsonArray
            ?: return emptyList()
        return runCatching {
            JsonInstant.decodeFromString<List<TodoItem>>(array.toString())
        }.getOrDefault(emptyList())
    }

    @Composable
    override fun title(context: ToolUIContext): String {
        val todos = remember(context) { todosOf(context) }
        if (todos.isEmpty()) return stringResource(R.string.tool_ui_todowrite_default)
        val done = todos.count { it.status == TodoStatus.COMPLETED || it.status == TodoStatus.CANCELLED }
        return stringResource(R.string.tool_ui_todowrite_progress, done, todos.size)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = todosOf(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val todos = remember(context) { todosOf(context) }
        if (todos.isEmpty()) return
        val done = todos.count { it.status == TodoStatus.COMPLETED || it.status == TodoStatus.CANCELLED }
        val active = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LinearProgressIndicator(
                progress = { done.toFloat() / todos.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small),
            )
            if (active != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                    Text(
                        text = active.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val todos = remember(context) { todosOf(context) }
        val active = todos.filter { it.status == TodoStatus.PENDING || it.status == TodoStatus.IN_PROGRESS }
        val done = todos.filter { it.status == TodoStatus.COMPLETED || it.status == TodoStatus.CANCELLED }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.7f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.todo_sheet_title, active.size, todos.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
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

/**
 * bgt_start 渲染器：标题与摘要展示启动的命令，详情为命令 + 返回的任务说明。
 */
object BgtStartToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 40

    override val toolName: String = "bgt_start"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Time02

    private fun commandOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("command")

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = commandOf(context)?.replace("\n", " ")?.trim()
            ?: return stringResource(R.string.tool_ui_bgt_start_default)
        val truncated = if (command.length > TITLE_MAX_CHARS) command.take(TITLE_MAX_CHARS) + "…" else command
        return stringResource(R.string.tool_ui_bgt_start, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = commandOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val command = commandOf(context) ?: return
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .shimmer(isLoading = context.loading),
        ) {
            CodeHighlightText(
                code = command,
                language = "bash",
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
        Column(
            modifier = Modifier
                .fillMaxHeight(0.6f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HighlightCodeBlock(
                code = commandOf(context) ?: "",
                language = "bash",
                modifier = Modifier.fillMaxWidth(),
            )
            context.content?.getStringContent("message")?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * bgt 渲染器（status/output/kill/list 合并入口）：
 * 标题按 action 区分；摘要就地展示结果要点（状态徽章/输出尾部/任务数），
 * output 的详情为完整输出代码块。
 */
object BgtToolUI : ToolUIRenderer {
    override val toolName: String = "bgt"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Refresh01

    private fun actionOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("action")

    private fun bgIdOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("bg_id")?.take(8)

    @Composable
    override fun title(context: ToolUIContext): String {
        val base = when (actionOf(context)) {
            "status" -> stringResource(R.string.tool_ui_bgt_status)
            "output" -> stringResource(R.string.tool_ui_bgt_output)
            "kill" -> stringResource(R.string.tool_ui_bgt_kill)
            "list" -> stringResource(R.string.tool_ui_bgt_list)
            else -> return stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
        return bgIdOf(context)?.let { "$base · $it" } ?: base
    }

    @Composable
    private fun statusColor(status: String?) = when (status) {
        "done" -> DiffAddedColor
        "failed" -> DiffRemovedColor
        else -> MaterialTheme.colorScheme.tertiary
    }

    override fun hasSummary(context: ToolUIContext): Boolean {
        if (!context.loading && context.content == null) return false
        return true
    }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content
        when (actionOf(context)) {
            "status" -> {
                val status = content?.getStringContent("status")
                val exitCode = content?.let { parseExitCode(it) } ?: -1
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
                    Text(
                        text = buildString {
                            append(status ?: "…")
                            if (exitCode != 0 && status != null && status != "running") {
                                append(" (exit $exitCode)")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            "output" -> {
                val output = content?.getStringContent("output")
                if (output != null) {
                    val tail = remember(output) {
                        output.lines().takeLast(OUTPUT_SUMMARY_LINES).joinToString("\n")
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        CodeHighlightText(
                            code = tail,
                            language = "bash",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = OUTPUT_SUMMARY_LINES,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.tool_ui_bgt_output_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.shimmer(isLoading = context.loading),
                    )
                }
            }

            "kill" -> {
                Text(
                    text = content?.getStringContent("message")
                        ?: stringResource(R.string.tool_ui_bgt_kill_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.shimmer(isLoading = context.loading),
                )
            }

            "list" -> {
                val count = content?.let { parseCount(it) }
                if (count != null) {
                    Text(
                        text = stringResource(R.string.tool_ui_bgt_tasks_count, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val output = context.content?.getStringContent("output")
        if (actionOf(context) == "output" && output != null) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HighlightCodeBlock(
                    code = output,
                    language = "bash",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            DefaultToolPreview(context = context)
        }
    }

    private fun parseExitCode(content: kotlinx.serialization.json.JsonElement): Int =
        content.jsonObjectOrNull?.get("exitCode")?.jsonPrimitiveOrNull?.intOrNull ?: -1

    private fun parseCount(content: kotlinx.serialization.json.JsonElement): Int? =
        content.jsonObjectOrNull?.get("count")?.jsonPrimitiveOrNull?.intOrNull

    private const val OUTPUT_SUMMARY_LINES = 6
}
