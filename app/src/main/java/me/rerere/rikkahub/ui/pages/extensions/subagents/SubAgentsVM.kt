package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.SubAgent
import kotlin.uuid.Uuid

class SubAgentsVM(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val subagents: StateFlow<List<SubAgent>> = settingsStore.settingsFlow
        .map { it.subagents }
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

    /** 删除定义，并清理所有 assistant 对该 subagent 的引用 */
    fun deleteSubAgent(id: Uuid) {
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

    /** 复制预设/已有定义（新 id、名称加 Copy），方便按需修改 */
    fun duplicateSubAgent(id: Uuid) {
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
