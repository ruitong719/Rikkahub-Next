package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.slugify
import me.rerere.rikkahub.data.ai.uniqueToolName
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import kotlin.uuid.Uuid

/**
 * 输入框工具条上的 subagent 监看入口：当前助手启用了 subagent 时显示，
 * 角标 = 启用的 subagent 数量。样式对齐 TodoStatusButton（ToggleSurface）。
 */
@Composable
fun SubAgentMonitorButton(
    enabledCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleSurface(
        checked = enabledCount > 0,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = {
                    if (enabledCount > 0) {
                        Badge { Text(enabledCount.coerceAtMost(99).toString()) }
                    }
                },
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
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

/** subagent 最近一次调用的状态（由对话消息中的 subagent_* 工具调用推导） */
private enum class SubAgentRunStatus {
    NEVER,      // 本对话尚未调用
    RUNNING,    // 有调用但尚无结果输出（执行中/结果未回填）
    SUCCESS,    // 返回 status=success
    ERROR,      // 返回 status=error
    TIMEOUT,    // 返回 status=timeout 或提示超时
    DONE,       // 有输出但无法解析状态
}

/** subagent 监看面板：列出当前助手启用的 subagent 及其最近调用状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentMonitorSheet(
    subAgents: List<SubAgent>,
    messages: List<UIMessage>,
    onDismiss: () -> Unit,
    onManage: (String) -> Unit,
) {
    // 与 SubAgentTools.createSubAgentTools 相同的工具名生成规则，用于在消息里匹配调用
    val toolNames = remember(subAgents) { computeSubAgentToolNames(subAgents) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    val toolName = toolNames[subAgent.id]
                    val (status, preview) = toolName?.let { lastInvocation(it, messages) }
                        ?: (SubAgentRunStatus.NEVER to "")
                    ListItem(
                        headlineContent = {
                            Text(
                                text = subAgent.name.ifBlank { subAgent.id.toString() },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (subAgent.description.isNotBlank()) {
                                    Text(
                                        text = subAgent.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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
                                if (preview.isNotBlank()) {
                                    Text(
                                        text = preview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { onManage(subAgent.id.toString()) }) {
                                Icon(
                                    imageVector = HugeIcons.Settings02,
                                    contentDescription = stringResource(R.string.subagent_monitor_manage),
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/** 计算每个 subagent 的工具名（与 SubAgentTools 相同的 slug + 去重规则） */
private fun computeSubAgentToolNames(subAgents: List<SubAgent>): Map<Uuid, String> {
    val used = mutableSetOf<String>()
    return subAgents.associate { subAgent ->
        val slug = uniqueToolName(slugify(subAgent.name), used, subAgent.id)
        used += slug
        subAgent.id to "subagent_$slug"
    }
}

/** 从对话消息中取该工具最近一次调用 */
private fun lastInvocation(toolName: String, messages: List<UIMessage>): Pair<SubAgentRunStatus, String>? {
    var last: UIMessagePart.Tool? = null
    messages.forEach { message ->
        message.parts.forEach { part ->
            if (part is UIMessagePart.Tool && part.toolName == toolName) {
                last = part
            }
        }
    }
    val tool = last ?: return null

    // 结果未回填 = 执行中（或结果丢失）
    if (tool.output.isEmpty()) return SubAgentRunStatus.RUNNING to ""

    val text = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val status = when {
        "\"status\":\"error\"" in text -> SubAgentRunStatus.ERROR
        "\"status\":\"timeout\"" in text || "timed out" in text -> SubAgentRunStatus.TIMEOUT
        "\"status\":\"success\"" in text -> SubAgentRunStatus.SUCCESS
        else -> SubAgentRunStatus.DONE
    }
    val preview = text.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.take(120)
        .orEmpty()
    return status to preview
}

@Composable
private fun statusLabelRes(status: SubAgentRunStatus): Int = when (status) {
    SubAgentRunStatus.NEVER -> R.string.subagent_monitor_status_never
    SubAgentRunStatus.RUNNING -> R.string.subagent_monitor_status_running
    SubAgentRunStatus.SUCCESS -> R.string.subagent_monitor_status_success
    SubAgentRunStatus.ERROR -> R.string.subagent_monitor_status_error
    SubAgentRunStatus.TIMEOUT -> R.string.subagent_monitor_status_timeout
    SubAgentRunStatus.DONE -> R.string.subagent_monitor_status_done
}
