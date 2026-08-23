package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.TodoStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import kotlin.uuid.Uuid

/**
 * 终端命令执行记录，供悬浮球展开窗口「实时输出」标签展示。
 */
data class TerminalCommand(
    val command: String,
    val output: String,
    val isRunning: Boolean,
)

/**
 * 悬浮球展开窗口展示的 AI 活动状态快照。
 *
 * [realTodos] 来自 [TodoStore]，反映当前对话下 todo 的真实持久化状态，
 * 包括用户在聊天界面手动完成/取消完成操作带来的变更。
 */
data class FloatingActivityState(
    val isGenerating: Boolean = false,
    val senderName: String = "",
    val status: String = "",
    val liveText: String = "",
    val reasoning: String = "",
    val realTodos: List<TodoStoreItem> = emptyList(),
    val terminalCommands: List<TerminalCommand> = emptyList(),
) {
    val hasContent: Boolean
        get() = liveText.isNotBlank() || reasoning.isNotBlank() ||
            realTodos.isNotEmpty() || terminalCommands.isNotEmpty()
}

/**
 * 给悬浮窗展示的轻量 todo 模型，由 [me.rerere.rikkahub.data.ai.tools.local.TodoItem] 映射而来。
 */
data class TodoStoreItem(
    val id: String,
    val title: String,
    val description: String,
    val completed: Boolean,
)

/**
 * 全局 AI 活动状态中心。
 *
 * 订阅 [AppEventBus] 上的聊天生成事件，把流式输出的文本、推理、终端命令整理成
 * 单一 [FloatingActivityState]；同时跟踪当前对话 ID，从 [TodoStore] 订阅该对话的
 * 真实待办列表，供悬浮球展开窗口展示。
 * 与 [ChatNotificationManager] 同源消费，二者互不影响。
 */
class FloatingActivityHub(
    private val appScope: AppScope,
    eventBus: AppEventBus,
    private val json: Json,
    private val todoStore: TodoStore,
) {
    private val _state = MutableStateFlow(FloatingActivityState())
    val state: StateFlow<FloatingActivityState> = _state.asStateFlow()

    // 跟踪当前对话 ID，从 ChatGenerationUpdate/Ended 事件中提取
    private val currentConversationId = MutableStateFlow<Uuid?>(null)

    init {
        // 订阅生成事件，更新 AI 活动状态
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate -> {
                        currentConversationId.value = event.conversationId
                        _state.value = _state.value.copy(
                            isGenerating = true,
                            senderName = event.senderName,
                            status = inferStatus(event.lastMessage.parts),
                            liveText = extractLiveText(event.lastMessage),
                            reasoning = extractReasoning(event.lastMessage),
                            terminalCommands = extractTerminalCommands(event.lastMessage.parts),
                        )
                    }
                    is AppEvent.ChatGenerationEnded -> {
                        currentConversationId.value = event.conversationId
                        _state.value = _state.value.copy(isGenerating = false, status = "")
                    }
                    else -> {}
                }
            }
        }

        // 订阅当前对话的真实 todos（来自 TodoStore）
        appScope.launch(Dispatchers.Default) {
            currentConversationId
                .flatMapLatest { conversationId ->
                    if (conversationId != null) {
                        todoStore.todos(conversationId)
                    } else {
                        flowOf(emptyList())
                    }
                }
                .collect { todos ->
                    _state.value = _state.value.copy(
                        realTodos = todos.map {
                            TodoStoreItem(
                                id = it.id,
                                title = it.title,
                                description = it.description,
                                completed = it.completed,
                            )
                        }
                    )
                }
        }
    }

    private fun extractLiveText(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }

    private fun extractReasoning(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Reasoning>()
            .lastOrNull()?.reasoning.orEmpty()

    private fun inferStatus(parts: List<UIMessagePart>): String {
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()
        return when {
            lastTool != null && !lastTool.isExecuted -> "执行中"
            lastReasoning != null && lastReasoning.finishedAt == null -> "思考中"
            lastText != null -> "输出中"
            else -> "生成中"
        }
    }

    private fun extractTerminalCommands(parts: List<UIMessagePart>): List<TerminalCommand> {
        return parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolName.contains("shell", ignoreCase = true) }
            .map { tool ->
                val command = runCatching {
                    json.parseToJsonElement(tool.input).jsonObject["command"]
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: tool.input
                val output = tool.output
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { textPart ->
                        runCatching {
                            val obj = json.parseToJsonElement(textPart.text).jsonObject
                            val stdout = obj["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val stderr = obj["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
                        }.getOrNull() ?: textPart.text
                    }
                TerminalCommand(
                    command = command,
                    output = output,
                    isRunning = !tool.isExecuted,
                )
            }
    }
}
