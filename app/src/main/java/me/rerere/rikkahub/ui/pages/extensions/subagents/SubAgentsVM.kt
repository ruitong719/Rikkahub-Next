package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.buildGoalEvaluatorSubAgent
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.isGeneralSubagent
import me.rerere.rikkahub.data.model.isGoalEvaluatorSubagent
import kotlin.uuid.Uuid

class SubAgentsVM(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    /** 用户定义 + 内置 GOAL 评估器（后者仅用于展示，不写入 settings.subagents） */
    val subagents: StateFlow<List<SubAgent>> = settingsStore.settingsFlow
        .map { settings ->
            val list = settings.subagents
            if (list.any { isGoalEvaluatorSubagent(it.id) }) list
            else list + buildGoalEvaluatorSubAgent()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addSubAgent(name: String, description: String, systemPrompt: String) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    subagents = settings.subagents + SubAgent(
                        name = name.trim(),
                        description = description.trim(),
                        systemPrompt = systemPrompt.trim(),
                    )
                )
            }
        }
    }

    /** 删除定义，并清理所有 assistant 对该 subagent 的引用（内置 General / GOAL 评估器不可删除） */
    fun deleteSubAgent(id: Uuid) {
        if (isGeneralSubagent(id) || isGoalEvaluatorSubagent(id)) return
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    subagents = settings.subagents.filterNot { it.id == id },
                    assistants = settings.assistants.map { assistant ->
                        assistant.copy(subagentIds = assistant.subagentIds - id)
                    },
                )
            }
        }
    }

    /** 复制预设/已有定义（新 id、名称加 Copy），方便按需修改；内置 GOAL 评估器不可复制 */
    fun duplicateSubAgent(id: Uuid) {
        if (isGoalEvaluatorSubagent(id)) return
        viewModelScope.launch {
            settingsStore.update { settings ->
                val source = settings.subagents.find { it.id == id } ?: return@update settings
                settings.copy(
                    subagents = settings.subagents + source.copy(
                        id = Uuid.random(),
                        name = "${source.name} Copy",
                    )
                )
            }
        }
    }
}
