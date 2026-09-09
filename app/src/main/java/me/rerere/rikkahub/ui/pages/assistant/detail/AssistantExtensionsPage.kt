package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.ai.QuickMessagesContent
import me.rerere.rikkahub.ui.components.ai.SkillsContent
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantExtensionsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { 4 }
    var showTimeReminderIntervalDialog by remember { mutableStateOf(false) }
    var timeReminderIntervalInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_extensions_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SecondaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.assistant_extensions_page_tab_quick_messages)) }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.assistant_extensions_page_tab_skills)) }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = { Text(stringResource(R.string.assistant_extensions_page_tab_subagents)) }
                )
                Tab(
                    selected = pagerState.currentPage == 3,
                    onClick = { scope.launch { pagerState.animateScrollToPage(3) } },
                    text = { Text(stringResource(R.string.assistant_extensions_page_tab_reminder)) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (page) {
                    0 -> {
                        if (settings.quickMessages.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_quick_messages),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_extensions),
                                onAction = { navController.navigate(Screen.QuickMessages) },
                            )
                        } else {
                            Column {
                                QuickMessagesContent(
                                    modifier = Modifier.weight(1f),
                                    quickMessages = settings.quickMessages,
                                    selectedIds = assistant.quickMessageIds,
                                    onToggle = { quickMessageId, checked ->
                                        val newIds = if (checked) assistant.quickMessageIds + quickMessageId
                                        else assistant.quickMessageIds - quickMessageId
                                        vm.update(assistant.copy(quickMessageIds = newIds))
                                    },
                                )
                                TextButton(
                                    onClick = { navController.navigate(Screen.QuickMessages) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.assistant_extensions_page_goto_extensions))
                                }
                            }
                        }
                    }

                    1 -> {
                        if (skills.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_skills),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_extensions),
                                onAction = { navController.navigate(Screen.Skills) },
                            )
                        } else {
                            Column {
                                SkillsContent(
                                    modifier = Modifier.weight(1f),
                                    skills = skills,
                                    enabledSkills = assistant.enabledSkills,
                                    onToggle = { name, checked ->
                                        val newSkills = if (checked) assistant.enabledSkills + name
                                        else assistant.enabledSkills - name
                                        vm.update(assistant.copy(enabledSkills = newSkills))
                                    },
                                )
                                TextButton(
                                    onClick = { navController.navigate(Screen.Skills) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.assistant_extensions_page_goto_extensions))
                                }
                            }
                        }
                    }

                    2 -> {
                        if (settings.subagents.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_subagents),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_extensions),
                                onAction = { navController.navigate(Screen.SubAgents) },
                            )
                        } else {
                            Column {
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    items(settings.subagents, key = { it.id.toString() }) { subAgent ->
                                        ListItem(
                                            supportingContent = if (subAgent.description.isNotBlank()) {
                                                {
                                                    Text(
                                                        text = subAgent.description,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            } else null,
                                            trailingContent = {
                                                Switch(
                                                    checked = subAgent.id in assistant.subagentIds,
                                                    onCheckedChange = { checked ->
                                                        val newIds = if (checked) {
                                                            assistant.subagentIds + subAgent.id
                                                        } else {
                                                            assistant.subagentIds - subAgent.id
                                                        }
                                                        vm.update(assistant.copy(subagentIds = newIds))
                                                    },
                                                )
                                            },
                                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        ) {
                                            Text(subAgent.name.ifBlank { subAgent.id.toString() })
                                        }
                                    }
                                }
                                TextButton(
                                    onClick = { navController.navigate(Screen.SubAgents) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.assistant_extensions_page_goto_extensions))
                                }
                            }
                        }
                    }

                    3 -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(PaddingValues(16.dp)),
                        ) {
                            // 时间提醒总开关（fork 3029165a 自研功能，UI 入口随记忆页删除而丢失，本次补回）
                            ListItem(
                                supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = assistant.enableTimeReminder,
                                        onCheckedChange = { checked ->
                                            vm.update(assistant.copy(enableTimeReminder = checked))
                                        },
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            ) {
                                Text(stringResource(R.string.assistant_page_time_reminder))
                            }

                            // 间隔配置：仅在开关打开时显示
                            if (assistant.enableTimeReminder) {
                                ListItem(
                                    selected = false,
                                    onClick = {
                                        timeReminderIntervalInput = assistant.timeReminderIntervalMinutes.toString()
                                        showTimeReminderIntervalDialog = true
                                    },
                                    supportingContent = {
                                        Text(stringResource(R.string.assistant_page_time_reminder_interval_desc))
                                    },
                                    trailingContent = {
                                        Text(
                                            stringResource(
                                                R.string.assistant_page_time_reminder_interval_value,
                                                assistant.timeReminderIntervalMinutes
                                            )
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                ) {
                                    Text(stringResource(R.string.assistant_page_time_reminder_interval))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTimeReminderIntervalDialog) {
        val interval = timeReminderIntervalInput.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { showTimeReminderIntervalDialog = false },
            title = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
            text = {
                TextField(
                    value = timeReminderIntervalInput,
                    onValueChange = { timeReminderIntervalInput = it },
                    label = { Text(stringResource(R.string.assistant_page_time_reminder_interval_label)) },
                    supportingText = { Text(stringResource(R.string.assistant_page_time_reminder_interval_hint)) },
                    isError = interval == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = interval != null,
                    onClick = {
                        interval?.let {
                            vm.update(assistant.copy(timeReminderIntervalMinutes = it))
                        }
                        showTimeReminderIntervalDialog = false
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeReminderIntervalDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }
}
