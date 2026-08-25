package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.slugify
import me.rerere.rikkahub.data.ai.uniqueToolName
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.isGeneralSubagent
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import kotlin.uuid.Uuid

private val invocationJson = Json { ignoreUnknownKeys = true }

/**
 * 输入框工具条上的 subagent 监看入口：当前助手启用了 subagent 时显示，
 * 角标 = 正在被调用的 subagent 数量（有调用才显示数字）。样式对齐 TodoStatusButton（ToggleSurface）。
 */
@Composable
fun SubAgentMonitorButton(
    runningCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleSurface(
        checked = runningCount > 0,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = {
                    if (runningCount > 0) {
                        Badge { Text(runningCount.coerceAtMost(99).toString()) }
                    }
                },
            ) {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.AiBrain01,
                        contentDescription = stringResource(R.string.subagent_monitor_button),
                    )
                }
            }
        }
    }
}

/** 统计当前正在被调用的 subagent 实例数量（所有调用中结果尚未回填的 = 执行中） */
fun countRunningSubAgents(subAgents: List<SubAgent>, messages: List<UIMessage>): Int {
    val toolNames = computeSubAgentToolNames(subAgents).values.toSet()
    return messages.sumOf { message ->
        message.parts.count { part ->
            part is UIMessagePart.Tool && part.toolName in toolNames && part.output.isEmpty()
        }
    }
}

/** subagent 一次调用的状态（由对话消息中的 subagent_* 工具调用推导） */
private enum class SubAgentRunStatus {
    RUNNING,    // 有调用但尚无结果输出（执行中/结果未回填）
    SUCCESS,    // 返回 status=success
    ERROR,      // 返回 status=error
    TIMEOUT,    // 返回 status=timeout 或提示超时
    DONE,       // 有输出但无法解析状态
}

/** 面板里一行的展示信息（对应一次发派调用） */
private data class InvocationView(
    val status: SubAgentRunStatus,
    /** 执行中显示 task 预览；结束后显示结果首行预览 */
    val preview: String,
    /** 调用时的 label 参数（General 并行实例区分用），可能为 null */
    val label: String?,
    /** 结果 JSON 携带的轨迹 id；旧消息没有则回退定义 id */
    val runId: String?,
)

/**
 * subagent 监看面板：按实例逐行列出当前助手启用的 subagent 的每次调用，
 * 并行多实例以 label 或 -1/-2/-3 后缀区分；点击条目查看该次运行的执行轨迹。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentMonitorSheet(
    subAgents: List<SubAgent>,
    messages: List<UIMessage>,
    onDismiss: () -> Unit,
    onOpenTrace: (String) -> Unit,
    onManage: (String) -> Unit,
) {
    // 与 SubAgentTools.createSubAgentTools 相同的工具名生成规则，用于在消息里匹配调用
    val toolNames = remember(subAgents) { computeSubAgentToolNames(subAgents) }
    // 定义数 × 历史调用数都可能超出屏幕：整列可滚动
    val scrollState = rememberScrollState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.subagent_monitor_sheet_title, subAgents.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (subAgents.isEmpty()) {
                Text(
                    text = stringResource(R.string.subagent_monitor_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                subAgents.forEach { subAgent ->
                    val invocations = toolNames[subAgent.id]
                        ?.let { collectInvocations(it, messages) }
                        .orEmpty()
                    if (invocations.isEmpty()) {
                        InvocationRow(
                            title = subAgent.name.ifBlank { subAgent.id.toString() },
                            description = subAgent.description,
                            view = null,
                            onOpenTrace = { onOpenTrace(subAgent.id.toString()) },
                            onManage = { onManage(subAgent.id.toString()) },
                        )
                    } else {
                        invocations.forEachIndexed { index, view ->
                            val title = when {
                                invocations.size == 1 -> subAgent.name.ifBlank { subAgent.id.toString() }
                                !view.label.isNullOrBlank() -> view.label
                                else -> "${subAgent.name.ifBlank { "subagent" }}-${index + 1}"
                            }
                            InvocationRow(
                                title = title,
                                description = null,
                                view = view,
                                onOpenTrace = { onOpenTrace(view.runId ?: subAgent.id.toString()) },
                                onManage = { onManage(subAgent.id.toString()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvocationRow(
    title: String,
    description: String?,
    view: InvocationView?,
    onOpenTrace: () -> Unit,
    onManage: () -> Unit,
) {
    val status = view?.status
    ListItem(
        onClick = onOpenTrace,
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (status == SubAgentRunStatus.RUNNING) {
                    Text(
                        text = stringResource(R.string.subagent_monitor_running_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status != null) {
                    Text(
                        text = stringResource(statusLabelRes(status)),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (status) {
                            SubAgentRunStatus.ERROR, SubAgentRunStatus.TIMEOUT ->
                                MaterialTheme.colorScheme.error
                            SubAgentRunStatus.RUNNING ->
                                MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                }
                if (!view?.preview.isNullOrBlank()) {
                    Text(
                        text = view.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onManage) {
                    Icon(
                        imageVector = HugeIcons.Settings02,
                        contentDescription = stringResource(R.string.subagent_monitor_manage),
                    )
                }
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 计算每个 subagent 的工具名（与 SubAgentTools 相同的 slug + 去重规则；General 固定为 general） */
private fun computeSubAgentToolNames(subAgents: List<SubAgent>): Map<Uuid, String> {
    val used = mutableSetOf<String>("general")
    return subAgents.associate { subAgent ->
        val slug = if (isGeneralSubagent(subAgent.id)) {
            "general"
        } else {
            uniqueToolName(slugify(subAgent.name), used, subAgent.id).also { used += it }
        }
        subAgent.id to "subagent_$slug"
    }
}

/** 按出现顺序收集该工具的所有调用（并行多实例各占一条） */
private fun collectInvocations(toolName: String, messages: List<UIMessage>): List<InvocationView> =
    buildList {
        messages.forEach { message ->
            message.parts.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == toolName) {
                    add(part.toInvocationView())
                }
            }
        }
    }

private fun UIMessagePart.Tool.toInvocationView(): InvocationView {
    val input = runCatching {
        invocationJson.parseToJsonElement(input.ifBlank { "{}" }).jsonObject
    }.getOrNull()
    val label = input?.get("label")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val taskPreview = input?.get("task")?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.replace('\n', ' ')
        ?.take(100)
        .orEmpty()

    val outputText = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val outputJson = runCatching {
        invocationJson.parseToJsonElement(outputText).jsonObject
    }.getOrNull()
    val runId = outputJson?.get("runId")?.jsonPrimitive?.contentOrNull

    val status = when {
        output.isEmpty() -> SubAgentRunStatus.RUNNING
        "\"status\":\"error\"" in outputText -> SubAgentRunStatus.ERROR
        "\"status\":\"timeout\"" in outputText || "timed out" in outputText -> SubAgentRunStatus.TIMEOUT
        "\"status\":\"success\"" in outputText -> SubAgentRunStatus.SUCCESS
        else -> SubAgentRunStatus.DONE
    }
    // 执行中没有结果可预览，改看任务内容；结束后看结果首行
    val preview = if (status == SubAgentRunStatus.RUNNING) {
        taskPreview
    } else {
        outputText.lineSequence().firstOrNull { it.isNotBlank() }?.take(120).orEmpty()
    }
    return InvocationView(status = status, preview = preview, label = label, runId = runId)
}

@Composable
private fun statusLabelRes(status: SubAgentRunStatus): Int = when (status) {
    SubAgentRunStatus.RUNNING -> R.string.subagent_monitor_status_running
    SubAgentRunStatus.SUCCESS -> R.string.subagent_monitor_status_success
    SubAgentRunStatus.ERROR -> R.string.subagent_monitor_status_error
    SubAgentRunStatus.TIMEOUT -> R.string.subagent_monitor_status_timeout
    SubAgentRunStatus.DONE -> R.string.subagent_monitor_status_done
}
