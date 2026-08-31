package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.CommandLine
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.SubAgentRunMonitor
import me.rerere.rikkahub.data.ai.SubAgentRunStatus
import me.rerere.rikkahub.data.ai.SubAgentRunStep
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private val reportJson = Json { ignoreUnknownKeys = true }

/**
 * subagent 执行轨迹页：展示最近一次运行的实时轨迹
 * （状态 / 任务 / 中间过程 / 最终报告）。运行中自动刷新。
 *
 * 中间过程是独立的限高卡片：内部条目（tool_call / 中间输出 / thinking）以圆角小框
 * 顺序排列，超出限高时在卡片内上下滑动，不影响页面其他部分。
 */
@Composable
fun SubAgentTracePage(id: String) {
    val monitor = koinInject<SubAgentRunMonitor>()
    val runs by monitor.runs.collectAsStateWithLifecycle()
    val parsedId = runCatching { Uuid.parse(id) }.getOrNull()
    // 兼容两种入参：runId（列表页/监看面板跳转）或 subAgent 定义 id（回退到其最近一次运行）
    val run = runs[parsedId] ?: parsedId?.let { sid ->
        runs.values.filter { it.subAgentId == sid }.maxByOrNull { it.startedAt }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = run?.displayName?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.subagents_trace_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (run == null) {
                item {
                    Text(
                        text = stringResource(R.string.subagents_trace_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
                return@LazyColumn
            }

            item {
                Text(
                    text = stringResource(statusLabelRes(run.status)),
                    style = MaterialTheme.typography.titleMedium,
                    color = when (run.status) {
                        SubAgentRunStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        SubAgentRunStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                        SubAgentRunStatus.ERROR, SubAgentRunStatus.TIMEOUT ->
                            MaterialTheme.colorScheme.error
                    },
                )
            }

            if (run.task.isNotBlank()) {
                item {
                    CardGroup(title = { Text(stringResource(R.string.subagents_trace_task)) }) {
                        item(headlineContent = {
                            Text(
                                text = run.task,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        })
                    }
                }
            }

            if (run.steps.isNotEmpty()) {
                val toolCallCount = run.steps.count { it is SubAgentRunStep.ToolCall }
                item {
                    Text(
                        text = stringResource(R.string.subagents_trace_process, toolCallCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    ProcessCard(steps = run.steps)
                }
            }

            val finalReport = displayReport(run.result)
            if (!finalReport.isNullOrBlank()) {
                item {
                    CardGroup(title = { Text(stringResource(R.string.subagents_trace_result)) }) {
                        item(headlineContent = {
                            Text(
                                text = finalReport,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        })
                    }
                }
            }
        }
    }
}

/** 中间过程卡片：限高，条目在卡片内上下滑动 */
@Composable
private fun ProcessCard(steps: List<SubAgentRunStep>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            steps.forEach { step ->
                when (step) {
                    is SubAgentRunStep.Thinking -> EntryBox {
                        ThinkingText(step.text)
                    }

                    is SubAgentRunStep.IntermediateText -> EntryBox {
                        IntermediateText(step.text)
                    }

                    is SubAgentRunStep.ToolCall -> ToolCallBox(step)
                }
            }
        }
    }
}

/** 过程条目统一的圆角小框 */
@Composable
private fun EntryBox(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            content()
        }
    }
}

/** thinking 块：淡化、小字号、限行 */
@Composable
private fun ThinkingText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 11.sp,
            fontStyle = FontStyle.Italic,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 工具调用之间的中间叙述：小字灰显，不限行 */
@Composable
private fun IntermediateText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 工具调用条目（悬浮球样式）：图标 + 等宽工具名与入参，两行省略，不做点击响应 */
@Composable
private fun ToolCallBox(step: SubAgentRunStep.ToolCall) {
    EntryBox {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = when {
                    step.executed && step.succeeded -> HugeIcons.CheckmarkCircle02
                    step.executed -> HugeIcons.Cancel01
                    else -> HugeIcons.CommandLine
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = when {
                    step.executed && step.succeeded -> MaterialTheme.colorScheme.primary
                    step.executed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                },
            )
            Text(
                text = buildString {
                    append(step.toolName)
                    if (step.input.isNotBlank()) {
                        append(' ')
                        append(step.input.replace('\n', ' '))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 从结果信封 JSON 中取出 report 正文展示，避免直接把 JSON 展示给用户 */
private fun displayReport(result: String): String? {
    if (result.isBlank()) return null
    val parsed = runCatching {
        reportJson
            .parseToJsonElement(result)
            .jsonObject
            .get("result")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()
    return parsed?.takeIf { it.isNotBlank() } ?: result
}

@Composable
private fun statusLabelRes(status: SubAgentRunStatus): Int = when (status) {
    SubAgentRunStatus.RUNNING -> R.string.subagents_trace_status_running
    SubAgentRunStatus.SUCCESS -> R.string.subagents_trace_status_success
    SubAgentRunStatus.ERROR -> R.string.subagents_trace_status_error
    SubAgentRunStatus.TIMEOUT -> R.string.subagents_trace_status_timeout
}
