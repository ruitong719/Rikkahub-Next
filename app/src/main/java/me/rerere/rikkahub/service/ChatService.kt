package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.PermissionModePolicy
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.SubAgentRunMonitor
import me.rerere.rikkahub.data.ai.context.RollingContextSummary
import me.rerere.rikkahub.data.ai.context.createRollingContextPlan
import me.rerere.rikkahub.data.ai.context.rollingContextWindowStartIndex
import me.rerere.rikkahub.data.ai.SubAgentRunner
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createSubAgentTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.AgentMdTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.VisionImageToTextTransformer
import me.rerere.rikkahub.data.ai.transformers.BackgroundTaskReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.PermissionModePromptTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.WorkspaceBgManager
import me.rerere.rikkahub.data.files.WorkspaceMountManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/** 后台任务完成自动拉起的轮询间隔 */
private const val BG_AUTO_RESUME_POLL_MS = 2_000L

/** 自动拉起连续触发上限：防止模型每轮都开新后台任务，形成无用户输入的自我续跑 */
private const val BG_AUTO_RESUME_MAX_STREAK = 3

/** 自动拉起连续失败上限：任务始终未被消费说明生成在注入提醒前就失败了，停止重试等用户介入 */
private const val BG_AUTO_RESUME_MAX_FAILURES = 3

private const val ROLLING_CONTEXT_SUMMARY_PROMPT = """
    Summarize the following conversation excerpt concisely in 2-4 sentences.
    Preserve key facts, decisions, names, numbers, and any unresolved questions.
    Write the summary in the same language as the conversation.
    Only output the summary text, nothing else.
    Conversation excerpt:
"""

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

/** 流式自动重连事件（实验性）：UI 层按会话过滤后以轻提示展示 */
data class StreamReconnectNotice(
    val conversationId: Uuid,
    val attempt: Int,
    val maxAttempts: Int,
)

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val generationHandler: GenerationHandler,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val workspaceBgManager: WorkspaceBgManager,
    private val workspaceMountManager: WorkspaceMountManager,
    private val subAgentRunMonitor: SubAgentRunMonitor,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository, workspaceMountManager)

    // AGENTS.md 注入：/agent 目录下全部 *.md（agent.md 优先），否则用设置里的全局文本
    private val agentMdTransformer = AgentMdTransformer()

    // 视觉模型降级：主模型不支持图片时，用视觉模型把图片转成文字描述
    private val visionImageToTextTransformer = VisionImageToTextTransformer(providerManager)

    // subagent 嵌套执行核心（复用 GenerationHandler，无需 Koin 注册）
    private val subAgentRunner = SubAgentRunner(generationHandler, subAgentRunMonitor)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    init {
        startBgTaskAutoResumeWatcher()
    }

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    // 流式自动重连事件（实验性）：extraBufferCapacity 保证 tryEmit 在无订阅者时也不丢
    private val _reconnectNotices = MutableSharedFlow<StreamReconnectNotice>(extraBufferCapacity = 8)
    val reconnectNotices: SharedFlow<StreamReconnectNotice> = _reconnectNotices.asSharedFlow()
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    /** 生成中用户补充消息队列（驱动输入区角标） */
    fun getQueuedMessagesFlow(conversationId: Uuid): Flow<List<QueuedUserMessage>> {
        val session = sessions[conversationId] ?: return flowOf(emptyList())
        return session.queuedMessages
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        // 用 getOrCreateSession 保证 UI 观察的是生成循环写入的同一份 Flow，否则状态永远到不了界面
        return getOrCreateSession(conversationId).processingStatus
    }

    /** 沿 cause 链找网络层异常并给出本地化原因；找不到 IOException 时按"连接中断"兜底 */
    private fun Throwable.networkErrorMessage(context: Context): String {
        val io = generateSequence<Throwable>(this) { it.cause }
            .filterIsInstance<IOException>()
            .firstOrNull()
        val res = when (io) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(res)
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    /**
     * 发送消息。生成中调用时消息进入队列（不打断），由生成循环在工具轮次间隙插入，
     * 或生成结束后兜底发送；非生成状态直接发送。
     *
     * @return true 表示消息已入队（生成中），false 表示已直接发送
     */
    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true): Boolean {
        if (content.isEmptyInputMessage()) return false

        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) {
            // 入队前先做用户输入预处理（正则变量等），保证快路径/flush 与直接发送行为一致
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.getAssistantById(session.state.value.assistantId)
                ?: settings.getCurrentAssistant()
            session.enqueue(
                message = UIMessage(
                    role = MessageRole.USER,
                    parts = preprocessUserInputParts(content, assistant),
                ),
                answer = answer,
            )
            Log.i(TAG, "sendMessage: queued message for $conversationId while generating")
            return true
        }
        // 用户主动发送，清零自动拉起限流计数
        session.resetAutoResumeGuards()
        sendMessageInternal(conversationId, content, answer)
        return false
    }

    private fun sendMessageInternal(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = launchSendUserMessage(conversationId, content, answer, waitPrevious = true)
        session.setJob(job)
    }

    /**
     * 生成结束后发送队列残留消息（快路径在生成循环内已消费，这里兜底）。
     * 在生成 job 的 finally 中调用：正常结束 / 报错 / 被打断统一触发，
     * 因此"打断并立即发送队列"无需额外逻辑。
     */
    private fun flushQueuedMessages(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val queued = session.drainQueue()
        if (queued.isEmpty()) return
        Log.i(TAG, "flushQueuedMessages: sending ${queued.size} queued messages for $conversationId")
        // 合并为一条发送：逐条发送会引发 N 连生成；任一条要求生成即触发生成
        val job = launchSendUserMessage(
            conversationId = conversationId,
            content = queued.flatMap { it.message.parts },
            answer = queued.any { it.answer },
            waitPrevious = false,
            // 入队时已做过用户输入预处理，这里跳过避免正则重复应用
            alreadyProcessed = true,
        )
        session.setJob(job)
    }

    /**
     * 用户消息发送 job 主体。waitPrevious=false 用于队列兜底 flush：
     * 此时上一个 job 正在 finally 中调用本方法，不能 cancel/join 它（会自杀/死锁）。
     */
    private fun launchSendUserMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean,
        waitPrevious: Boolean,
        alreadyProcessed: Boolean = false,
    ): Job {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        return appScope.launch {
            try {
                if (waitPrevious) {
                    runCatching { previousJob?.join() }
                }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = if (alreadyProcessed) content else preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            } finally {
                // 兜底：生成结束（正常/失败/打断）后发送队列残留（快路径已消费的队列为空）
                flushQueuedMessages(conversationId)
            }
        }
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            } finally {
                flushQueuedMessages(conversationId)
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        alwaysAllow: Boolean = false,
    ) {
        val session = getOrCreateSession(conversationId)
        // 用户主动操作（审批继续生成），清零自动拉起限流计数
        session.resetAutoResumeGuards()
        session.getJob()?.cancel()

        // 「本次会话内全部同意」：按该调用的相关路径注册目录授权（无路径概念的工具按工具名），
        // 同批其他 Pending 调用在生成恢复后的下一轮经 applyConversationGrants 自动放行
        if (approved && alwaysAllow && answer == null) {
            val toolCall = session.state.value.messageNodes
                .flatMap { it.messages }
                .flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Tool>()
                .firstOrNull { it.toolCallId == toolCallId }
            if (toolCall != null) {
                val paths = ToolGrants.relevantPaths(toolCall.toolName, toolCall.inputAsJson())
                if (paths.isEmpty()) {
                    session.toolGrants.grantTool(toolCall.toolName)
                } else {
                    paths.forEach { session.toolGrants.grantDir(ToolGrants.parentDir(it)) }
                }
            }
        }

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            } finally {
                flushQueuedMessages(conversationId)
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)

            // 滚动摘要上下文：当对话 token 超出阈值时，压缩早期消息为摘要
            val rollingThresholdTokens = assistant.rollingContextCompressionThresholdTokens
            val currentMessages = conversation.currentMessages.let {
                if (messageRange != null) {
                    it.subList(messageRange.start, messageRange.endInclusive + 1)
                } else {
                    it
                }
            }
            val storedSummary = conversation.rollingContextSummary
            val rollingPlan = createRollingContextPlan(
                messages = currentMessages,
                storedSummary = storedSummary,
                thresholdTokens = rollingThresholdTokens,
            )
            var rollingContextText = storedSummary?.content
            var requestStartIndex = 0

            if (rollingPlan != null && rollingPlan.messagesToSummarize.isNotEmpty()) {
                // 需要压缩：调用 AI 生成摘要
                val summaryText = generateRollingContextSummary(
                    model = model,
                    provider = model.findProvider(settings.providers)
                        ?: error("Provider not found"),
                    providerManager = providerManager,
                    messages = rollingPlan.messagesToSummarize,
                    previousSummary = rollingPlan.previousSummary?.content,
                    targetTokens = rollingPlan.targetTokens,
                )
                if (summaryText.isNotBlank()) {
                    val newSummary = RollingContextSummary(
                        content = summaryText,
                        sourceMessageIds = rollingPlan.sourceMessageIds,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    // 持久化摘要到会话
                    val updatedConversation = conversation.copy(rollingContextSummary = newSummary)
                    conversationRepo.updateConversation(updatedConversation)
                    rollingContextText = summaryText
                    requestStartIndex = currentMessages.size - rollingPlan.messagesToSummarize.size - rollingPlan.previousSummary.let {
                        if (it != null) rollingPlan.sourceMessageIds.size - rollingPlan.messagesToSummarize.size else 0
                    }
                    // 计算安全起始索引
                    requestStartIndex = rollingContextWindowStartIndex(currentMessages, rollingThresholdTokens)
                    Log.i(TAG, "Rolling context: compressed ${rollingPlan.messagesToSummarize.size} messages into summary (${summaryText.length} chars), start index = $requestStartIndex")
                }
            } else if (storedSummary != null) {
                // 有摘要但不需要更新，计算安全起始索引
                requestStartIndex = rollingContextWindowStartIndex(currentMessages, rollingThresholdTokens)
            }

            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = currentMessages,
                assistant = assistant,
                conversationId = conversationId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                enableAutoReconnect = settingsStore.settingsFlow.value.enableStreamAutoReconnect,
                onAutoReconnect = { attempt, maxAttempts, error ->
                    _reconnectNotices.tryEmit(StreamReconnectNotice(conversationId, attempt, maxAttempts))
                    // 重试原因上屏：下次尝试开始时由 GenerationHandler 清除，取消时也会清
                    session.processingStatus.value = context.getString(
                        R.string.chat_generation_network_retrying,
                        error.networkErrorMessage(context),
                        attempt,
                        maxAttempts,
                    )
                },
                workspaceCwd = conversation.workspaceCwd,
                rollingContextSummary = rollingContextText,
                requestMessageStartIndex = requestStartIndex,
                onPollQueuedMessages = {
                    // 生成循环每轮调用 LLM 前取出队列（快路径），插入对话让 LLM 尽快看到用户补充
                    sessions[conversationId]?.drainQueue()?.map { it.message } ?: emptyList()
                },
                onRequeueQueuedMessages = { messages ->
                    // 插入失败（如被打断）时按原序回滚到队首，避免排队消息丢失
                    sessions[conversationId]?.requeueFront(messages)
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    add(agentMdTransformer)
                    add(visionImageToTextTransformer)
                    add(backgroundTaskReminder(conversationId))
                    add(
                        PermissionModePromptTransformer(
                            mode = conversation.permissionMode,
                            planPrompt = settings.planModePrompt,
                            buildPrompt = settings.buildModePrompt,
                            yoloPrompt = settings.yoloModePrompt,
                        )
                    )
                },
                outputTransformers = outputTransformers,
                tools = applyConversationGrants(
                    tools = PermissionModePolicy.apply(
                        tools = buildList {
                        // MCP 工具名校验：无效时中止整个生成（保持原行为）
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }
                    val mainTools = buildList {
                        if (assistant.enableWebSearch) {
                            addAll(createSearchTools(settings))
                        }
                        addAll(localTools.getTools(assistant.localTools, conversationId))
                        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd, conversationId))
                        if (assistant.enabledSkills.isNotEmpty()) {
                            addAll(
                                createSkillTools(
                                    enabledSkills = assistant.enabledSkills,
                                    allSkills = skillManager.listSkills(),
                                )
                            )
                        }
                        mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
                            add(
                                Tool(
                                    name = "mcp__${serverName}__${tool.name}",
                                    description = tool.description ?: "",
                                    parameters = { tool.inputSchema },
                                    needsApproval = { tool.needsApproval },
                                    execute = {
                                        mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                    },
                                )
                            )
                        }
                    }
                    addAll(mainTools)
                    // subagent 工具：catalog = 主工具池（不含 subagent 工具，v1 禁止嵌套）
                    if (assistant.subagentIds.isNotEmpty()) {
                        addAll(
                            createSubAgentTools(
                                subAgents = settings.subagents.filter { it.id in assistant.subagentIds },
                                assistant = assistant,
                                settings = settings,
                                conversationSystemPrompt = conversation.customSystemPrompt,
                                conversationHistory = conversation.currentMessages,
                                toolCatalog = mainTools,
                                allSkills = skillManager.listSkills(),
                                subAgentRunner = subAgentRunner,
                                conversationId = conversationId,
                            )
                        )
                    }
                    },
                        mode = conversation.permissionMode,
                    ),
                    grants = session.toolGrants,
                ),
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：向事件总线广播，供悬浮球等消费者更新状态
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 悬浮球等消费方依赖此事件；tryEmit 不挂起，事件丢失只影响状态刷新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底广播结束事件（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    /**
     * 会话级授权包装：把「本次会话内全部同意」的授予结果并入每个工具的 needsApproval。
     * 已被目录/工具授权覆盖的调用直接放行，不再进入 Pending 审批——
     * 并行批次中一旦有一个获得授权，其余同批调用在下一轮循环自动放行。
     */
    private fun applyConversationGrants(tools: List<Tool>, grants: ToolGrants): List<Tool> = tools.map { tool ->
        val base = tool.needsApproval
        tool.copy(
            needsApproval = { args ->
                base(args) && !grants.covers(tool.name, ToolGrants.relevantPaths(tool.name, args))
            }
        )
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String? = null,
        conversationId: Uuid? = null,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd, conversationId?.toString())
    }

    /** 后台任务完成提醒：绑定当前对话，任务完成后下次生成时注入 <bg_reminder> */
    private fun backgroundTaskReminder(conversationId: Uuid?): BackgroundTaskReminderTransformer =
        BackgroundTaskReminderTransformer(
            workspaceRepository = workspaceRepository,
            bgManager = workspaceBgManager,
            conversationId = conversationId?.toString(),
        )

    // ---- 后台任务完成自动拉起 ----

    /**
     * 后台任务完成自动拉起：模型调用 bgt_start 后结束回合等待时，
     * 任务一完成就自动触发一次生成，无需用户发消息。提醒内容由
     * [BackgroundTaskReminderTransformer] 在生成前注入并标记 notified。
     *
     * 只拉起**活跃会话**（聊天页打开中，refCount > 0）且当前没有生成、没有挂起审批的对话；
     * 通过 [ConversationSession.beginGenerationIfIdle] 预留式登记生成任务，与用户发送互斥（用户优先）。
     * 连续触发/连续失败都有上限（BG_AUTO_RESUME_MAX_STREAK / BG_AUTO_RESUME_MAX_FAILURES），
     * 用户主动发消息或审批时清零。
     */
    private fun startBgTaskAutoResumeWatcher() {
        appScope.launch {
            while (true) {
                delay(BG_AUTO_RESUME_POLL_MS)
                runCatching { checkFinishedBgTasksAndResume() }
                    .onFailure { Log.w(TAG, "checkFinishedBgTasksAndResume failed", it) }
            }
        }
    }

    private suspend fun checkFinishedBgTasksAndResume() {
        for (conversationId in sessions.keys) {
            val session = sessions[conversationId] ?: continue
            if (session.isGenerating) continue
            // 只拉起活跃会话；页面已关闭的留给下次生成前的提醒注入兜底，避免无人观看的后台生成
            if (!session.hasReferences()) continue

            val conversation = session.state.value
            if (conversation.messageNodes.isEmpty()) continue

            // 存在挂起审批/未恢复工具时不自动拉起，避免打断审批流程
            val hasPendingTool = conversation.messageNodes.any { node ->
                node.currentMessage.getTools().any { !it.isExecuted && !it.approvalState.canResumeToolExecution() }
            }
            if (hasPendingTool) continue

            val assistant = settingsStore.settingsFlow.first()
                .getAssistantById(conversation.assistantId) ?: continue
            val workspaceId = assistant.workspaceId?.toString() ?: continue
            val workspace = workspaceRepository.getById(workspaceId) ?: continue
            if (workspace.shellStatus != WorkspaceShellStatus.READY.name) continue

            val finishedTasks = workspaceBgManager.listUnNotifiedFinishedTasks(
                workspace.root,
                conversationId.toString(),
            )
            if (finishedTasks.isEmpty()) continue

            // 连续自动拉起达到上限：停止自我续跑，等用户介入
            if (session.autoResumeStreak >= BG_AUTO_RESUME_MAX_STREAK) continue

            Log.i(TAG, "bg auto-resume: conversation $conversationId has finished task, triggering generation")
            // 预留式登记：与并发的用户发送互斥避免双生成；被抢占则跳过本轮
            session.beginGenerationIfIdle {
                session.onAutoResumeTriggered()
                appScope.launch {
                    try {
                        handleMessageComplete(conversationId)
                        _generationDoneFlow.emit(conversationId)
                    } catch (e: Exception) {
                        addError(e, conversationId, title = context.getString(R.string.bg_auto_resume_error_title))
                    } finally {
                        // 按结果判断：任务仍未被消费说明生成在提醒注入前就失败了，计入连续失败，
                        // 达到上限后停止重试并报错提示用户手动查看
                        val taskIds = finishedTasks.map { it.taskId }.toSet()
                        val stillPending = workspaceBgManager
                            .listUnNotifiedFinishedTasks(workspace.root, conversationId.toString())
                            .any { it.taskId in taskIds }
                        if (stillPending) {
                            session.onAutoResumeAttemptFailed()
                            if (session.autoResumeFailures >= BG_AUTO_RESUME_MAX_FAILURES) {
                                addError(
                                    error = IllegalStateException(
                                        context.getString(R.string.bg_auto_resume_gave_up)
                                    ),
                                    conversationId = conversationId,
                                    title = context.getString(R.string.bg_auto_resume_error_title),
                                )
                            }
                        } else {
                            session.onAutoResumeConsumed()
                        }
                        flushQueuedMessages(conversationId)
                    }
                }
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim())
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = buildList {
            compressedSummaries.forEach { summary ->
                add(UIMessage.user(summary).toMessageNode())
            }
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 滚动摘要上下文 ----

    /**
     * 调用 AI 生成滚动摘要上下文。
     * 将早期对话压缩为一段简短摘要，后续请求只发送最近窗口消息 + 摘要。
     */
    private suspend fun generateRollingContextSummary(
        model: Model,
        provider: me.rerere.ai.provider.ProviderSetting,
        providerManager: ProviderManager,
        messages: List<UIMessage>,
        previousSummary: String?,
        targetTokens: Int,
    ): String = withContext(Dispatchers.IO) {
        val providerImpl = providerManager.getProviderByType(provider)
        val conversationText = messages.joinToString("\n") { "[${it.role.name}] ${it.toText()}" }
        val prompt = buildString {
            append(ROLLING_CONTEXT_SUMMARY_PROMPT)
            if (!previousSummary.isNullOrBlank()) {
                append("\n\nPrevious summary:\n")
                append(previousSummary)
            }
            append("\n\n")
            append(conversationText)
            append("\n\nSummary (target ~$targetTokens tokens):")
        }
        val result = providerImpl.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(
                model = model,
                maxTokens = targetTokens.coerceAtMost(2_000),
                temperature = 0.3f,
                reasoningLevel = ReasoningLevel.OFF,
            ),
        )
        result.message.toText().trim()
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        // 内存态无条件更新：权限模式等设置在首条消息发送前也要即时生效于 UI 与下一次生成
        updateConversation(conversationId, conversation)

        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时仅更新内存态不落库，避免空会话塞满对话列表
        }

        if (!exists) {
            conversationRepo.insertConversation(conversation)
        } else {
            conversationRepo.updateConversation(conversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = createForkConversation(currentConversation, copiedNodes)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
