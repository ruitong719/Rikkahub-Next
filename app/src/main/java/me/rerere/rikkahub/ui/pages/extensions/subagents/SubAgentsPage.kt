package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Activity02
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.SubAgentRunMonitor
import me.rerere.rikkahub.data.ai.SubAgentRunStatus
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun SubAgentsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SubAgentsVM>()
    val subagents by vm.subagents.collectAsStateWithLifecycle()
    val runMonitor = koinInject<SubAgentRunMonitor>()
    val runs by runMonitor.runs.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var deleteTarget by remember { mutableStateOf<SubAgent?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.subagents_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                navController.navigate(Screen.SubAgentEdit("new"))
            }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (subagents.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.subagents_page_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.subagents_page_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                items(subagents, key = { it.id.toString() }) { subAgent ->
                    val run = runs[subAgent.id]
                    SubAgentCard(
                        subAgent = subAgent,
                        running = run?.status == SubAgentRunStatus.RUNNING,
                        hasTrace = run != null,
                        onClick = {
                            // 正在执行的智能体 -> 查看执行轨迹；否则进入编辑页
                            if (run?.status == SubAgentRunStatus.RUNNING) {
                                navController.navigate(Screen.SubAgentTrace(subAgent.id.toString()))
                            } else {
                                navController.navigate(Screen.SubAgentEdit(subAgent.id.toString()))
                            }
                        },
                        onShowTrace = {
                            navController.navigate(Screen.SubAgentTrace(subAgent.id.toString()))
                        },
                        onDuplicate = { vm.duplicateSubAgent(subAgent.id) },
                        onDelete = { deleteTarget = subAgent },
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.subagents_page_delete_title),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.deleteSubAgent(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.subagents_page_delete_message, target.name))
        }
    }
}

@Composable
private fun SubAgentCard(
    subAgent: SubAgent,
    running: Boolean,
    hasTrace: Boolean,
    onClick: () -> Unit,
    onShowTrace: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subAgent.name.ifBlank { stringResource(R.string.subagents_page_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (running) {
                        Text(
                            text = stringResource(R.string.subagents_page_running),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    text = subAgent.description.ifBlank { "-" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.subagents_page_meta,
                        subAgent.toolAllowlist.size,
                        subAgent.enabledSkills.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (hasTrace) {
                IconButton(onClick = onShowTrace) {
                    Icon(
                        imageVector = HugeIcons.Activity02,
                        contentDescription = stringResource(R.string.subagents_trace_title),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onDuplicate) {
                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = stringResource(R.string.subagents_page_duplicate),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(18.dp),
                )
            }
            Icon(
                imageVector = HugeIcons.PencilEdit01,
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(16.dp),
            )
        }
    }
}
