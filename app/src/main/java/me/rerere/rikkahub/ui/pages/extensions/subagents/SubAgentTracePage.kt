package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.SubAgentRunMonitor
import me.rerere.rikkahub.data.ai.SubAgentRunStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * subagent 执行轨迹页：展示最近一次运行的实时轨迹
 * （状态 / 任务 / 工具调用步骤 / 最终结果）。运行中自动刷新。
 */
@Composable
fun SubAgentTracePage(id: String) {
    val monitor = koinInject<SubAgentRunMonitor>()
    val runs by monitor.runs.collectAsStateWithLifecycle()
    val subAgentId = runCatching { Uuid.parse(id) }.getOrNull()
    val run = subAgentId?.let { runs[it] }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.subagents_trace_title)) },
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
                    style = MaterialTheme.typography.titleLarge,
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
                item {
                    Text(
                        text = stringResource(R.string.subagents_trace_steps, run.steps.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(run.steps, key = { "${it.toolName}-${it.inputPreview}" }) { step ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = step.toolName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (step.inputPreview.isNotBlank()) {
                            Text(
                                text = step.inputPreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        if (step.outputPreview.isNotBlank()) {
                            Text(
                                text = step.outputPreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 3,
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            if (run.result.isNotBlank()) {
                item {
                    CardGroup(title = { Text(stringResource(R.string.subagents_trace_result)) }) {
                        item(headlineContent = {
                            Text(
                                text = run.result,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabelRes(status: SubAgentRunStatus): Int = when (status) {
    SubAgentRunStatus.RUNNING -> R.string.subagents_trace_status_running
    SubAgentRunStatus.SUCCESS -> R.string.subagents_trace_status_success
    SubAgentRunStatus.ERROR -> R.string.subagents_trace_status_error
    SubAgentRunStatus.TIMEOUT -> R.string.subagents_trace_status_timeout
}
