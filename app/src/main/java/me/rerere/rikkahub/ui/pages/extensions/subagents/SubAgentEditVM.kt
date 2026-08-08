package me.rerere.rikkahub.ui.pages.extensions.subagents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.SubAgent

class SubAgentEditVM(
    private val settingsStore: SettingsStore,
    private val skillManager: SkillManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.settingsFlow.value)

    val skills: List<SkillMetadata> = skillManager.listSkills()

    fun save(subAgent: SubAgent, isNew: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                if (isNew) {
                    settings.copy(subagents = settings.subagents + subAgent)
                } else {
                    settings.copy(
                        subagents = settings.subagents.map { existing ->
                            if (existing.id == subAgent.id) subAgent else existing
                        }
                    )
                }
            }
            onDone()
        }
    }
}
