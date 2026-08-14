package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

class PromptVM(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /** 删除模式注入，并同步清理所有助手与会话中的悬挂引用 */
    fun deleteModeInjection(id: Uuid) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    modeInjections = settings.modeInjections.filterNot { it.id == id },
                    assistants = settings.assistants.map { assistant ->
                        if (id in assistant.modeInjectionIds) {
                            assistant.copy(modeInjectionIds = assistant.modeInjectionIds - id)
                        } else {
                            assistant
                        }
                    },
                )
            }
            conversationRepository.removeInjectionIdsFromAllConversations(
                removedModeInjectionIds = setOf(id)
            )
        }
    }

    /** 删除世界书，并同步清理所有助手与会话中的悬挂引用 */
    fun deleteLorebook(id: Uuid) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    lorebooks = settings.lorebooks.filterNot { it.id == id },
                    assistants = settings.assistants.map { assistant ->
                        if (id in assistant.lorebookIds) {
                            assistant.copy(lorebookIds = assistant.lorebookIds - id)
                        } else {
                            assistant
                        }
                    },
                )
            }
            conversationRepository.removeInjectionIdsFromAllConversations(
                removedLorebookIds = setOf(id)
            )
        }
    }
}
