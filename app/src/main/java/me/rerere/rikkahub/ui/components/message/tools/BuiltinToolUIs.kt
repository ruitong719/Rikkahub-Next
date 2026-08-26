package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.CodeHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject

/**
 * 网络搜索: 标题带查询词, 摘要显示 answer 与结果数, 详情为结果列表
 */
object SearchWebToolUI : ToolUIRenderer {
    override val toolName: String = "search_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_search_web,
        context.arguments.getStringContent("query") ?: ""
    )

    private fun items(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("items")?.jsonArray ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("answer") != null || items(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("answer")?.let { answer ->
            Text(
                text = answer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val items = items(context)
        if (items.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FaviconRow(
                    urls = items.mapNotNull { it.getStringContent("url") },
                    size = 18.dp,
                )
                Text(
                    text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        when {
            content != null -> SearchWebPreview(arguments = context.arguments, content = content)
            context.loading -> PendingPreview(stringResource(R.string.tool_ui_search_pending))
            else -> DefaultToolPreview(context = context)
        }
    }
}

/**
 * 网页抓取: 摘要显示 URL, 详情为各网页的 Markdown 内容
 */
object ScrapeWebToolUI : ToolUIRenderer {
    override val toolName: String = "scrape_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GlobalSearch

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_scrape_web)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("url") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        Text(
            text = context.arguments.getStringContent("url") ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        when {
            content != null -> ScrapeWebPreview(content = content)
            context.loading -> PendingPreview(stringResource(R.string.tool_ui_scrape_pending))
            else -> DefaultToolPreview(context = context)
        }
    }
}

/**
 * 获取时间信息
 */
object GetTimeInfoToolUI : ToolUIRenderer {
    override val toolName: String = "get_time_info"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Time02

    private fun field(context: ToolUIContext, key: String): String =
        context.content?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_get_time)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || context.content?.jsonObject?.containsKey("date") == true

    @Composable
    override fun Summary(context: ToolUIContext) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shimmer(isLoading = context.loading),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${field(context, "date")} · ${field(context, "time")}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = field(context, "weekday"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        when {
            context.content?.jsonObject?.containsKey("date") == true -> TimeInfoPreview(context)
            context.loading -> PendingPreview(stringResource(R.string.tool_ui_time_pending))
            else -> DefaultToolPreview(context = context)
        }
    }

    /** 详情：日期/星期/时间/时区 */
    @Composable
    private fun TimeInfoPreview(context: ToolUIContext) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = field(context, "date"),
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(field(context, "weekday"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = field(context, "time"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = "${field(context, "timezone")} (${field(context, "utc_offset")})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 剪贴板: 按 action 区分读/写标题
 */
object ClipboardToolUI : ToolUIRenderer {
    private const val ACTION_READ = "read"
    private const val ACTION_WRITE = "write"

    override val toolName: String = "clipboard_tool"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clipboard

    @Composable
    override fun title(context: ToolUIContext): String =
        when (context.arguments.getStringContent("action")) {
            ACTION_READ -> stringResource(R.string.chat_message_tool_clipboard_read)
            ACTION_WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
        }

    private fun textOf(context: ToolUIContext): String =
        context.content?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val text = textOf(context).replace("\n", " ")
        if (text.isBlank()) return
        Text(
            text = text,
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
        when {
            context.content != null -> ClipboardPreview(context)
            context.loading -> PendingPreview(stringResource(R.string.tool_ui_clipboard_pending))
            else -> DefaultToolPreview(context = context)
        }
    }

    /** 详情：按 action 区分读取结果 / 写入回执 */
    @Composable
    private fun ClipboardPreview(context: ToolUIContext) {
        val isWrite = context.arguments.getStringContent("action") == ACTION_WRITE
        val text = textOf(context)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (isWrite) R.string.chat_message_tool_clipboard_write
                    else R.string.chat_message_tool_clipboard_read
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                CodeHighlightText(
                    code = text.ifBlank { "…" },
                    language = "bash",
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 文本转语音: 摘要显示朗读文本与重播按钮
 */
object TextToSpeechToolUI : ToolUIRenderer {
    override val toolName: String = "text_to_speech"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.VolumeHigh

    @Composable
    override fun title(context: ToolUIContext): String {
        val preview = context.arguments.getStringContent("text")?.let { text ->
            if (text.length > 24) text.take(24) + "…" else text
        } ?: ""
        return stringResource(R.string.tool_ui_speaking, preview)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("text") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val eventBus: AppEventBus = koinInject()
        val scope = rememberCoroutineScope()
        val text = context.arguments.getStringContent("text") ?: ""
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.tool_ui_replay),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 技能调用: 标题显示技能名与路径
 */
object UseSkillToolUI : ToolUIRenderer {
    override val toolName: String = "use_skill"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val skillName = context.arguments.getStringContent("name") ?: ""
        val path = context.arguments.getStringContent("path")
        return if (path != null) "Skill: $skillName / $path" else "Skill: $skillName"
    }

    private fun rawOutputOf(context: ToolUIContext): String =
        context.tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("name") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val name = context.arguments.getStringContent("name") ?: return
        Text(
            text = name,
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
        // 技能正文是纯 markdown 文本（content 解析为空 JsonObject），从原始输出读取
        when {
            context.loading -> PendingPreview(stringResource(R.string.tool_ui_skill_pending))
            context.content.getStringContent("error") != null -> SkillErrorPreview(context)
            else -> SkillContentPreview(context)
        }
    }

    @Composable
    private fun SkillErrorPreview(context: ToolUIContext) {
        Text(
            text = context.content.getStringContent("error").orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp),
        )
    }

    /** 详情：技能文档正文（markdown 渲染） */
    @Composable
    private fun SkillContentPreview(context: ToolUIContext) {
        val body = rawOutputOf(context)
        if (body.isBlank()) {
            DefaultToolPreview(context = context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            MarkdownBlock(content = body, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SearchWebPreview(
    arguments: JsonElement,
    content: JsonElement,
) {
    val context = LocalContext.current
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val answer = content.getStringContent("answer")
    val query = arguments.getStringContent("query") ?: ""
    val images = content.jsonObject["images"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(stringResource(R.string.chat_message_tool_search_prefix, query))
        }

        if (answer != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    MarkdownBlock(
                        content = answer,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (images.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(images) { imageUrl ->
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .height(120.dp)
                                .width(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { context.openUrl(imageUrl) },
                        )
                    }
                }
            }
        }

        if (items.isNotEmpty()) {
            items(items) { item ->
                val url = item.getStringContent("url") ?: return@items
                val title = item.getStringContent("title") ?: return@items
                val text = item.getStringContent("text") ?: return@items

                Card(
                    onClick = { context.openUrl(url) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(
                            url = url,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(text = title, maxLines = 1)
                            Text(
                                text = text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = url,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        } else {
            item {
                CodeHighlightText(
                    code = JsonInstantPretty.encodeToString(content),
                    language = "json",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ScrapeWebPreview(content: JsonElement) {
    val urls = content.jsonObject["urls"]?.jsonArray ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(
                    R.string.chat_message_tool_scrape_prefix,
                    urls.joinToString(", ") { it.getStringContent("url") ?: "" }
                )
            )
        }

        items(urls) { url ->
            val urlObject = url.jsonObject
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = urlObject["url"]?.jsonPrimitive?.content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                )
                Card {
                    MarkdownBlock(
                        content = urlObject["content"]?.jsonPrimitive?.content ?: "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}


/** 渲染详情的加载占位：替代此前「加载中点开闪 JSON」的行为 */
@Composable
internal fun PendingPreview(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(16.dp)
            .shimmer(isLoading = true),
    )
}
