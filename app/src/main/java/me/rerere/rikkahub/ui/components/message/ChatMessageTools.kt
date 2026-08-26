package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.parseAskUserQuestions
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant

private const val ASK_USER_TOOL_NAME = "ask_user"

@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(tool: UIMessagePart.ServerTool) {
    val loading = !tool.isFinished
    ChainOfThoughtStep(
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Tools,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = stringResource(R.string.chat_message_tool_call_generic, tool.toolName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, alwaysAllow: Boolean) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    // ask_user 仅在 Pending 待答时走交互表单（需要 onToolAnswer 回调提交）；
    // 已答/YOLO 不可用等终态交给注册式渲染器 AskUserToolUI 展示
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        val interactive = tool.approvalState is ToolApprovalState.Pending && onToolAnswer != null
        if (interactive) {
            AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
            return
        }
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = loading,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    // 本次会话内全部同意：按该调用的目录/工具注册会话级授权，同批其余调用连带放行
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "", true) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Key01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve_always),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "", false) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else {
            null
        },
        onClick = if (context.content != null || isPending || images.isNotEmpty() || loading) {
            { showResult = true }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason, false)
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                renderer.Preview(
                    context = context,
                    onDismissRequest = { showResult = false },
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val arguments = tool.inputAsJson()

    // 新格式（header/options{label,description}/multiple/custom）+ 旧 selection_type 兼容解析
    val questions = remember(arguments) { parseAskUserQuestions(arguments) }

    // 单选答案：chip 选择与自定义文本共用（后写覆盖先写）
    val answers = remember { mutableStateMapOf<String, String>() }
    // 多选答案：选中的 label 集合
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }
    // 多选的自定义文本：提交时作为额外 label 追加
    val customAnswers = remember { mutableStateMapOf<String, String>() }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        q.header.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        // 选项 chips：多选时可点选多个；单选时点击即作为答案
                        if (q.options.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                q.options.forEach { option ->
                                    FilterChip(
                                        selected = if (q.multiple) {
                                            multiAnswers[q.id]?.contains(option.label) == true
                                        } else {
                                            answers[q.id] == option.label
                                        },
                                        onClick = {
                                            if (q.multiple) {
                                                val current = multiAnswers[q.id]?.toMutableSet() ?: mutableSetOf()
                                                if (!current.add(option.label)) current.remove(option.label)
                                                multiAnswers[q.id] = current
                                            } else {
                                                answers[q.id] = option.label
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = option.label,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
                            }
                            // 已选中选项的说明（description），帮助用户确认选择
                            val description = if (q.multiple) {
                                q.options.filter { multiAnswers[q.id]?.contains(it.label) == true }
                                    .mapNotNull { it.description.ifBlank { null } }
                                    .joinToString(", ")
                            } else {
                                q.options.firstOrNull { it.label == answers[q.id] }?.description.orEmpty()
                            }
                            if (description.isNotBlank()) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }

                        // custom（默认开）：手打答案输入框
                        if (q.custom) {
                            OutlinedTextField(
                                value = if (q.multiple) customAnswers[q.id] ?: "" else answers[q.id] ?: "",
                                onValueChange = {
                                    if (q.multiple) customAnswers[q.id] = it else answers[q.id] = it
                                },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                            )
                        }
                    }
                }

                // Submit button
                FilledTonalButton(
                    onClick = {
                        val answerPayload = buildJsonObject {
                            put("answers", buildJsonObject {
                                questions.forEach { q ->
                                    if (q.multiple) {
                                        // 多选：选中的 label 数组；自定义文本作为额外 label 追加
                                        val labels = multiAnswers[q.id].orEmpty().toMutableList()
                                        customAnswers[q.id]?.trim()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
                                        put(q.id, buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
                                    } else {
                                        put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                    }
                                }
                            })
                        }
                        onToolAnswer?.invoke(tool.toolCallId, answerPayload.toString())
                    },
                    enabled = questions.all { q ->
                        when {
                            q.multiple -> !multiAnswers[q.id].isNullOrEmpty() ||
                                !customAnswers[q.id].isNullOrBlank()
                            else -> !answers[q.id].isNullOrBlank()
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(
                        imageVector = HugeIcons.Tick01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.chat_message_tool_submit),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
