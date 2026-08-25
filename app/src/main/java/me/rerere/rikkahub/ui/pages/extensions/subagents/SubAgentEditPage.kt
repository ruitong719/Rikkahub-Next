package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolCategory
import me.rerere.rikkahub.data.model.isGeneralSubagent
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/** UI 上按类别勾选的工具分组（值 = SubAgent.toolAllowlist 中的类别） */
private data class ToolCategory(val category: SubAgentToolCategory, val label: String)

@Composable
fun SubAgentEditPage(id: String) {
    val navController = LocalNavController.current
    val vm = koinViewModel<SubAgentEditVM>()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val isNew = id == "new"
    val initial = if (isNew) SubAgent() else settings.subagents.find { it.id.toString() == id } ?: SubAgent()
    val isGeneral = !isNew && isGeneralSubagent(initial.id)

    var name by remember(initial) { mutableStateOf(initial.name) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    var systemPrompt by remember(initial) { mutableStateOf(initial.systemPrompt) }
    var allowlist by remember(initial) { mutableStateOf(initial.toolAllowlist) }
    var enabledSkills by remember(initial) { mutableStateOf(initial.enabledSkills) }
    var maxSteps by remember(initial) { mutableStateOf(initial.maxSteps.toString()) }
    var timeoutSec by remember(initial) { mutableStateOf((initial.timeoutMs / 1000).toString()) }
    var requiresApproval by remember(initial) { mutableStateOf(initial.requiresApproval) }

    val toaster = LocalToaster.current
    val context = LocalContext.current

    // General 的工具类别由主模型每次调用时通过 tools 参数指定，编辑页不提供勾选
    val categories = remember {
        listOf(
            ToolCategory(SubAgentToolCategory.READ, context.getString(R.string.subagents_edit_cat_read)),
            ToolCategory(SubAgentToolCategory.WRITE, context.getString(R.string.subagents_edit_cat_write)),
            ToolCategory(SubAgentToolCategory.SHELL, context.getString(R.string.subagents_edit_cat_shell)),
        )
    }

    fun buildSubAgent(): SubAgent {
        val base = if (isNew) SubAgent() else initial
        return base.copy(
            name = name.trim(),
            description = description.trim(),
            systemPrompt = systemPrompt.trim(),
            toolAllowlist = allowlist,
            enabledSkills = enabledSkills,
            maxSteps = maxSteps.toIntOrNull()?.coerceIn(1, 256) ?: 64,
            timeoutMs = (timeoutSec.toIntOrNull()?.coerceIn(10, 3600) ?: 120) * 1000L,
            requiresApproval = requiresApproval,
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(if (isNew) R.string.subagents_edit_title_new else R.string.subagents_edit_title_edit))
                },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = {
                        val subAgent = buildSubAgent()
                        if (subAgent.name.isBlank()) {
                            toaster.show(context.getString(R.string.subagents_edit_name_required))
                            return@TextButton
                        }
                        vm.save(subAgent, isNew) { navController.popBackStack() }
                    }) {
                        Text(stringResource(R.string.common_save))
                    }
                },
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
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subagents_edit_name)) },
                    placeholder = { Text(stringResource(R.string.subagents_edit_name_hint)) },
                    singleLine = true,
                    enabled = !isGeneral,
                )
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subagents_edit_description)) },
                    placeholder = { Text(stringResource(R.string.subagents_edit_description_hint)) },
                    minLines = 2,
                    maxLines = 4,
                )
            }
            item {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subagents_edit_system_prompt)) },
                    placeholder = { Text(stringResource(R.string.subagents_edit_system_prompt_hint)) },
                    minLines = 4,
                    maxLines = 10,
                )
            }
            item {
                if (isGeneral) {
                    // General 的类别由主模型调用时通过 tools 参数指定，编辑页不提供勾选
                    SectionCard(
                        title = stringResource(R.string.subagents_edit_tools),
                        desc = stringResource(R.string.subagents_edit_general_tools_hint)
                    ) {}
                } else {
                    SectionCard(title = stringResource(R.string.subagents_edit_tools), desc = stringResource(R.string.subagents_edit_tools_desc)) {
                        categories.forEach { category ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        allowlist =
                                            if (category.category in allowlist) allowlist - category.category
                                            else allowlist + category.category
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = category.category in allowlist,
                                    onCheckedChange = { checked ->
                                        allowlist =
                                            if (checked) allowlist + category.category
                                            else allowlist - category.category
                                    },
                                )
                                Text(category.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.subagents_edit_skills), desc = stringResource(R.string.subagents_edit_skills_desc)) {
                    if (vm.skills.isEmpty()) {
                        Text(
                            text = stringResource(R.string.subagents_page_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    vm.skills.forEach { skill ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    enabledSkills = if (skill.name in enabledSkills) enabledSkills - skill.name else enabledSkills + skill.name
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = skill.name in enabledSkills,
                                onCheckedChange = { checked ->
                                    enabledSkills = if (checked) enabledSkills + skill.name else enabledSkills - skill.name
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                                if (skill.description.isNotBlank()) {
                                    Text(
                                        text = skill.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.subagents_edit_limits)) {
                    OutlinedTextField(
                        value = maxSteps,
                        onValueChange = { maxSteps = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.subagents_edit_max_steps)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = timeoutSec,
                        onValueChange = { timeoutSec = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.subagents_edit_timeout)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.subagents_edit_requires_approval),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = requiresApproval,
                            onCheckedChange = { requiresApproval = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    desc: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (desc != null) {
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}
