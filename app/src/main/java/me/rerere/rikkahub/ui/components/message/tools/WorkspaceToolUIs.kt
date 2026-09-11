package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.CodeHighlightText
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkahub.ui.components.richtext.DiffView
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.parseDiffStats
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.ai.ShellRunMonitor
import me.rerere.rikkahub.data.ai.ShellRunState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.java.KoinJavaComponent.getKoin

/**
 * 工作空间编辑文件: 摘要显示增删统计与精简 diff, 详情为完整 diff view
 */
object EditFileToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_LINES = 10

    override val toolName: String = "edit"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileEdit

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_edit_file, path) else stringResource(R.string.tool_ui_edit_file_default)
    }

    /**
     * 执行后读取输出部件 metadata 中的全文件 diff;
     * 未执行 (如等待审批) 时基于入参的 old_text/new_text 片段生成预览 diff
     */
    private fun diffOf(context: ToolUIContext): String? {
        if (context.tool.isExecuted) {
            return context.tool.output.firstOrNull()?.metadataAs<DiffMetadata>()?.diff
        }
        val path = context.arguments.getStringContent("path") ?: return null
        val oldText = context.arguments.getStringContent("old_text") ?: return null
        val newText = context.arguments.getStringContent("new_text") ?: return null
        return generateUnifiedDiff(oldText, newText, path)
    }

    // 内联摘要只在「等待审批」或「已执行」时展示：
    // 自动执行时参数补全的瞬间会先用 old_text/new_text 合成预览（≤10 行）撑大卡片，
    // 紧接着执行完成又换成行数不同的 metadata diff 缩回，看起来像跳动；
    // 等待审批时保留预览（此时用户本来就需要看到将要发生的改动），执行后再切到真实 diff。
    override fun hasSummary(context: ToolUIContext): Boolean =
        (context.tool.isPending || context.tool.isExecuted) && diffOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val diff = remember(context) { diffOf(context) } ?: return
        val stats = remember(diff) { parseDiffStats(diff) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "+${stats.additions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffAddedColor,
            )
            Text(
                text = "-${stats.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = DiffRemovedColor,
            )
        }
        DiffView(
            diff = diff,
            modifier = Modifier.fillMaxWidth(),
            maxLines = SUMMARY_MAX_LINES,
            showFileHeader = false,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val diff = remember(context) { diffOf(context) }
        if (diff == null) {
            DefaultToolPreview(context = context)
            return
        }
        val stats = remember(diff) { parseDiffStats(diff) }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = context.arguments.getStringContent("path") ?: toolName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "+${stats.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffAddedColor,
                )
                Text(
                    text = "-${stats.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = DiffRemovedColor,
                )
            }
            DiffView(
                diff = diff,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 工作空间读取: 文件摘要显示内容首部预览、详情为带语法高亮的完整内容;
 * 目录则展示条目列表（文件夹/文件图标），不再回退到 JSON 兜底渲染。
 * 注意: 以上仅影响 UI 展示，read 工具返回给模型的内容保持不变。
 */
object ReadFileToolUI : ToolUIRenderer {
    override val toolName: String = "read"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileView

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_read_file, path) else stringResource(R.string.tool_ui_read_file_default)
    }

    /** 已执行时从输出 JSON 读取文件内容（输出字段为 content，带行号前缀） */
    private fun textOf(context: ToolUIContext): String? =
        context.content.getStringContent("content")

    /** 展示用文件内容：去掉 `N: ` 行号前缀。仅 UI 层剥离，模型侧输出不变。 */
    private fun displayTextOf(context: ToolUIContext): String? {
        val text = textOf(context) ?: return null
        return text.replace(LINE_NUMBER_PREFIX, "")
    }

    /** 已执行且输出为目录时解析条目信息，否则返回 null */
    private fun directoryOf(context: ToolUIContext): DirectoryInfo? {
        val content = context.content ?: return null
        val obj = content.jsonObjectOrNull ?: return null
        if (obj.getStringContent("type") != "directory") return null
        val entries = obj.get("entries")?.jsonArrayOrNull
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
        val totalEntries = obj.getStringContent("totalEntries")?.toIntOrNull() ?: entries.size
        val truncated = obj.getStringContent("truncated")?.toBooleanStrictOrNull() ?: false
        return DirectoryInfo(entries = entries, totalEntries = totalEntries, truncated = truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        displayTextOf(context) != null || directoryOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val directory = remember(context) { directoryOf(context) }
        if (directory != null) {
            DirectorySummary(
                info = directory,
                loading = context.loading,
            )
            return
        }
        val text = remember(context) { displayTextOf(context) } ?: return
        FileContentSummary(
            text = text,
            path = context.arguments.getStringContent("path"),
            loading = context.loading,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val directory = remember(context) { directoryOf(context) }
        if (directory != null) {
            DirectoryPreview(
                context = context,
                info = directory,
            )
            return
        }
        val text = remember(context) { displayTextOf(context) }
        if (text == null) {
            DefaultToolPreview(context = context)
            return
        }
        FileContentPreview(path = context.arguments.getStringContent("path"), code = text)
    }
}

/** read 工具输出为目录时的解析结果（entries 中目录项以 `/` 结尾） */
private data class DirectoryInfo(
    val entries: List<String>,
    val totalEntries: Int,
    val truncated: Boolean,
)

private const val DIRECTORY_SUMMARY_MAX_ENTRIES = 8

/** `N: ` 行号前缀（read 工具输出的行号引用格式，仅 UI 展示时剥离） */
private val LINE_NUMBER_PREFIX = Regex("""^\d+: """, RegexOption.MULTILINE)

/** 目录内联摘要：条目数 + 前若干条目（文件夹/文件图标） */
@Composable
private fun DirectorySummary(info: DirectoryInfo, loading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .shimmer(isLoading = loading),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DirectoryEntryCountLine(info)
            info.entries.take(DIRECTORY_SUMMARY_MAX_ENTRIES).forEach { entry ->
                DirectoryEntryRow(
                    entry = entry,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

/** 目录详情（BottomSheet）：路径 + 条目数 + 可滚动条目列表 */
@Composable
private fun DirectoryPreview(context: ToolUIContext, info: DirectoryInfo) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = context.arguments.getStringContent("path") ?: stringResource(R.string.tool_ui_file),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        DirectoryEntryCountLine(info)
        if (info.entries.isEmpty()) {
            Text(
                text = stringResource(R.string.tool_ui_read_directory_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(info.entries) { entry ->
                    DirectoryEntryRow(
                        entry = entry,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

/** 条目数（截断时提示 +） */
@Composable
private fun DirectoryEntryCountLine(info: DirectoryInfo) {
    Text(
        text = stringResource(
            if (info.truncated) R.string.tool_ui_read_directory_count_truncated
            else R.string.tool_ui_read_directory_count,
            info.totalEntries,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 单条目录项：文件夹/文件图标 + 名称 */
@Composable
private fun DirectoryEntryRow(entry: String, fontSize: TextUnit, lineHeight: TextUnit) {
    val isDirectory = entry.endsWith("/")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = entry,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 工作空间写入文件: 内容取自入参 (未执行也可预览), 摘要为内容首部, 详情为完整内容
 */
object WriteFileToolUI : ToolUIRenderer {
    override val toolName: String = "write"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileAdd

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path != null) stringResource(R.string.tool_ui_write_file, path) else stringResource(R.string.tool_ui_write_file_default)
    }

    private fun textOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("text")

    override fun hasSummary(context: ToolUIContext): Boolean = textOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = remember(context) { textOf(context) } ?: return
        FileContentSummary(
            text = text,
            path = context.arguments.getStringContent("path"),
            loading = context.loading,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val text = remember(context) { textOf(context) }
        if (text == null) {
            DefaultToolPreview(context = context)
            return
        }
        FileContentPreview(path = context.arguments.getStringContent("path"), code = text)
    }
}

/** 内联摘要: 按扩展名语法高亮展示文件内容首部若干行 */
@Composable
private fun FileContentSummary(text: String, path: String?, loading: Boolean) {
    val preview = remember(text) {
        text.lineSequence().take(FILE_SUMMARY_MAX_LINES).joinToString("\n")
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .shimmer(isLoading = loading),
    ) {
        CodeHighlightText(
            code = preview,
            language = languageOf(path),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = FILE_SUMMARY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** BottomSheet 详情: 文件路径 + 按扩展名语法高亮的完整内容 */
@Composable
private fun FileContentPreview(path: String?, code: String) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = path ?: stringResource(R.string.tool_ui_file),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        HighlightCodeBlock(
            code = code,
            language = languageOf(path),
            modifier = Modifier.fillMaxWidth(),
            // read 展示的内容已剥离行号前缀，行号槽数字对不上文件真实行号，强制关闭
            showLineNumbers = false,
        )
    }
}

/**
 * 工作空间执行 Shell: 摘要显示退出状态与输出首部, 详情为命令 + stdout/stderr
 */
object ShellToolUI : ToolUIRenderer {
    private const val TITLE_MAX_CHARS = 40
    private const val SUMMARY_MAX_LINES = 8

    override val toolName: String = "bash"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String {
        val command = context.arguments.getStringContent("command") ?: return stringResource(R.string.tool_ui_shell_default)
        val preview = command.replace("\n", " ").trim()
        val truncated = if (preview.length > TITLE_MAX_CHARS) preview.take(TITLE_MAX_CHARS) + "…" else preview
        return stringResource(R.string.tool_ui_shell, truncated)
    }

    // loading 态也保留摘要位, 用于显示直播单行(LiveOutputSummaryLine)
    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content != null || context.loading

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content
        if (content == null) {
            LiveOutputSummaryLine(context)
            return
        }
        val combined = remember(content) {
            listOf(content.getStringContent("stdout"), content.getStringContent("stderr"))
                .filterNot { it.isNullOrBlank() }
                .joinToString("\n")
                .trim()
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShellExitStatus(content, MaterialTheme.typography.labelSmall)
            if (combined.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .shimmer(isLoading = context.loading),
                ) {
                    Text(
                        text = combined.lineSequence().take(SUMMARY_MAX_LINES).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = SUMMARY_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        val command = context.arguments.getStringContent("command").orEmpty()
        val cwd = context.arguments.getStringContent("cwd")
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_shell_default),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (content != null) {
                    ShellExitStatus(content, MaterialTheme.typography.labelMedium)
                } else {
                    Text(
                        text = stringResource(
                            if (context.loading) R.string.tool_ui_shell_running
                            else R.string.tool_ui_shell_pending
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            HighlightCodeBlock(
                code = if (cwd.isNullOrBlank()) command else "# cwd: $cwd\n$command",
                language = "bash",
                modifier = Modifier.fillMaxWidth(),
            )
            if (content == null) {
                LiveOutputSection(context, scrollState)
            }
            if (content != null) {
                // 已执行完成：展示完整输出；执行中打开时命令结束后会自动刷新到这里
                val stdout = content.getStringContent("stdout").orEmpty()
                val stderr = content.getStringContent("stderr").orEmpty()
                if (stdout.isNotEmpty()) {
                    Text(text = "stdout", style = MaterialTheme.typography.labelMedium)
                    HighlightCodeBlock(
                        code = stdout,
                        language = "plaintext",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (stderr.isNotEmpty()) {
                    Text(
                        text = "stderr",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    HighlightCodeBlock(
                        code = stderr,
                        language = "plaintext",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Shell 退出状态文本: exit code 为 0 显示绿色, 超时或非零显示错误色 */
@Composable
private fun ShellExitStatus(content: JsonElement, style: androidx.compose.ui.text.TextStyle) {
    val exitCode = content.int("exitCode")
    val timedOut = content.boolean("timedOut") ?: false
    val ok = !timedOut && exitCode == 0
    Text(
        text = when {
            timedOut -> stringResource(R.string.tool_ui_shell_timeout)
            else -> stringResource(R.string.tool_ui_shell_exit, exitCode?.toString() ?: "?")
        },
        style = style,
        color = if (ok) DiffAddedColor else MaterialTheme.colorScheme.error,
    )
}

/**
 * 执行中(loading)的 shell 直播订阅: 按 toolCallId 精确查找运行状态
 * (执行侧经 ShellRunKey 注入, 见 ShellRunMonitor)。
 * 实验性开关关闭或无直播时返回 null。
 */
@Composable
private fun LiveShellRun(context: ToolUIContext): ShellRunState? {
    if (!LocalSettings.current.displaySetting.enableShellLiveOutput) return null
    val monitor = remember { getKoin().get<ShellRunMonitor>() }
    val runs by monitor.runs.collectAsStateWithLifecycle()
    return runs[context.tool.toolCallId]?.takeIf { it.running }
}

/** 折叠态摘要: 直播输出的最新一行 */
@Composable
private fun LiveOutputSummaryLine(context: ToolUIContext) {
    val run = LiveShellRun(context) ?: return
    val lastLine = remember(run.stdoutTail, run.stderrTail) {
        sequenceOf(run.stdoutTail, run.stderrTail)
            .flatMap { it.lineSequence() }
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(120)
            .orEmpty()
    }
    if (lastLine.isEmpty()) return
    Text(
        text = lastLine,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 详情弹窗: 执行中实时显示尾部输出, 新输出到达时自动吸底滚动 */
@Composable
private fun LiveOutputSection(context: ToolUIContext, scrollState: ScrollState) {
    val run = LiveShellRun(context) ?: return
    LaunchedEffect(run.stdoutTail, run.stderrTail) {
        if (scrollState.maxValue > 0) scrollState.animateScrollTo(scrollState.maxValue)
    }
    Text(
        text = stringResource(R.string.tool_ui_shell_live_output),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
    if (run.stdoutTail.isNotEmpty()) {
        Text(text = "stdout", style = MaterialTheme.typography.labelMedium)
        HighlightCodeBlock(
            code = run.stdoutTail,
            language = "plaintext",
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (run.stderrTail.isNotEmpty()) {
        Text(
            text = "stderr",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        HighlightCodeBlock(
            code = run.stderrTail,
            language = "plaintext",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 从工具输出 JSON 读取布尔字段 */
private fun JsonElement?.boolean(key: String): Boolean? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.booleanOrNull

/** 从工具输出 JSON 读取整型字段 */
private fun JsonElement?.int(key: String): Int? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.intOrNull

/** 从工具输出 JSON 读取长整型字段 */
private fun JsonElement?.long(key: String): Long? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.longOrNull

private const val FILE_SUMMARY_MAX_LINES = 10

/** 由文件扩展名推断语法高亮语言 */
private fun languageOf(path: String?): String = when (
    path?.substringAfterLast('.', "")?.lowercase().orEmpty()
) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts" -> "typescript"
    "tsx" -> "tsx"
    "jsx" -> "jsx"
    "py" -> "python"
    "rb" -> "ruby"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "cc", "cxx", "hpp", "hxx" -> "cpp"
    "cs" -> "csharp"
    "swift" -> "swift"
    "php" -> "php"
    "sh", "bash", "zsh" -> "bash"
    "json" -> "json"
    "xml" -> "xml"
    "html", "htm" -> "html"
    "css" -> "css"
    "scss" -> "scss"
    "yaml", "yml" -> "yaml"
    "toml" -> "toml"
    "md", "markdown" -> "markdown"
    "sql" -> "sql"
    "gradle" -> "groovy"
    else -> "plaintext"
}

// ---- 只读检索工具（glob / grep / git） ----

/** glob / grep 结果的一行：path 必有，grep 额外带 line/text */
private data class SearchRow(
    val path: String,
    val line: Int? = null,
    val text: String? = null,
)

private const val SEARCH_SUMMARY_MAX_ROWS = 8

/** 解析 glob/grep 输出里的 matches 数组（path / line / text） */
private fun JsonElement?.searchRows(): List<SearchRow> =
    this?.jsonObjectOrNull?.get("matches")?.jsonArrayOrNull?.mapNotNull { element ->
        val obj = element.jsonObjectOrNull ?: return@mapNotNull null
        val path = obj.getStringContent("path") ?: return@mapNotNull null
        SearchRow(
            path = path,
            line = obj["line"]?.jsonPrimitiveOrNull?.intOrNull,
            text = obj.getStringContent("text"),
        )
    } ?: emptyList()

private fun JsonElement?.searchCount(fallback: Int): Int =
    this?.jsonObjectOrNull?.get("count")?.jsonPrimitiveOrNull?.intOrNull ?: fallback

private fun SearchRow.displayText(): String = buildString {
    append(path)
    line?.let { append(':').append(it) }
    text?.trim()?.takeIf { it.isNotEmpty() }?.let { append("  ").append(it) }
}

@Composable
private fun SearchRowLine(row: SearchRow, fontSize: TextUnit, lineHeight: TextUnit) {
    Text(
        text = row.displayText(),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        fontSize = fontSize,
        lineHeight = lineHeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** glob/grep 共用的折叠摘要：命中条数 + 前若干行 */
@Composable
private fun SearchSummary(total: Int, rows: List<SearchRow>, loading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .shimmer(isLoading = loading),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.tool_ui_search_result_count, total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            rows.take(SEARCH_SUMMARY_MAX_ROWS).forEach { row ->
                SearchRowLine(row, fontSize = 11.sp, lineHeight = 14.sp)
            }
            if (rows.size > SEARCH_SUMMARY_MAX_ROWS) {
                Text(
                    text = stringResource(R.string.tool_ui_search_more, rows.size - SEARCH_SUMMARY_MAX_ROWS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

/** glob/grep 共用的详情：pattern + 全部命中行（可滚动） */
@Composable
private fun SearchPreview(header: String, pattern: String?, total: Int, rows: List<SearchRow>) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.tool_ui_search_result_count, total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (!pattern.isNullOrBlank()) {
            Text(
                text = pattern,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rows) { row ->
                SearchRowLine(row, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

/** glob 工具：标题显示 pattern，摘要/详情列出命中路径 */
object GlobToolUI : ToolUIRenderer {
    override val toolName: String = "glob"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Folder01

    @Composable
    override fun title(context: ToolUIContext): String {
        val pattern = context.arguments.getStringContent("pattern")
        return if (pattern.isNullOrBlank()) {
            stringResource(R.string.tool_ui_glob_default)
        } else {
            stringResource(R.string.tool_ui_glob, pattern)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val rows = remember(context) { context.content.searchRows() }
        SearchSummary(
            total = context.content.searchCount(rows.size),
            rows = rows,
            loading = context.loading,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val rows = remember(context) { context.content.searchRows() }
        if (rows.isEmpty()) {
            DefaultToolPreview(context = context)
            return
        }
        SearchPreview(
            header = stringResource(R.string.tool_ui_glob_default),
            pattern = context.arguments.getStringContent("pattern"),
            total = context.content.searchCount(rows.size),
            rows = rows,
        )
    }
}

/** grep 工具：标题显示 pattern，摘要/详情按 path:line 展示命中行 */
object GrepToolUI : ToolUIRenderer {
    override val toolName: String = "grep"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String {
        val pattern = context.arguments.getStringContent("pattern")
        return if (pattern.isNullOrBlank()) {
            stringResource(R.string.tool_ui_grep_default)
        } else {
            stringResource(R.string.tool_ui_grep, pattern)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val rows = remember(context) { context.content.searchRows() }
        SearchSummary(
            total = context.content.searchCount(rows.size),
            rows = rows,
            loading = context.loading,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val rows = remember(context) { context.content.searchRows() }
        if (rows.isEmpty()) {
            DefaultToolPreview(context = context)
            return
        }
        SearchPreview(
            header = stringResource(R.string.tool_ui_grep_default),
            pattern = context.arguments.getStringContent("pattern"),
            total = context.content.searchCount(rows.size),
            rows = rows,
        )
    }
}

/** git 工具（只读）：标题显示子命令，摘要/详情展示 stdout/stderr */
object GitToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_LINES = 10

    override val toolName: String = "git"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GitFork

    @Composable
    override fun title(context: ToolUIContext): String {
        val action = context.arguments.getStringContent("action")
        return if (action.isNullOrBlank()) {
            stringResource(R.string.tool_ui_git_default)
        } else {
            stringResource(R.string.tool_ui_git, action)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val content = context.content ?: return
        val stdout = remember(content) { content.getStringContent("stdout").orEmpty().trim() }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShellExitStatus(content, MaterialTheme.typography.labelSmall)
            if (stdout.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .shimmer(isLoading = context.loading),
                ) {
                    Text(
                        text = stdout.lineSequence().take(SUMMARY_MAX_LINES).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = SUMMARY_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        val dir = context.arguments.getStringContent("path")
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_git_default),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (content != null) {
                    ShellExitStatus(content, MaterialTheme.typography.labelMedium)
                } else {
                    Text(
                        text = stringResource(
                            if (context.loading) R.string.tool_ui_git_running else R.string.tool_ui_git_pending
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (!dir.isNullOrBlank()) {
                Text(
                    text = "# path: $dir",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (content == null) return@Column
            content.getStringContent("hint")?.takeIf { it.isNotBlank() }?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val stdout = content.getStringContent("stdout").orEmpty()
            val stderr = content.getStringContent("stderr").orEmpty()
            if (stdout.isNotEmpty()) {
                Text(text = "stdout", style = MaterialTheme.typography.labelMedium)
                HighlightCodeBlock(
                    code = stdout,
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (stderr.isNotEmpty()) {
                Text(
                    text = "stderr",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                HighlightCodeBlock(
                    code = stderr,
                    language = "plaintext",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (stdout.isEmpty() && stderr.isEmpty() && content.getStringContent("hint").isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.tool_ui_git_no_output),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}
