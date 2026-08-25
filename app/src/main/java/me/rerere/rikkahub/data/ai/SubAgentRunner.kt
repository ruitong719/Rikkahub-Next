package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolCategory
import kotlin.uuid.Uuid

/**
 * Subagent 执行核心：复用 GenerationHandler.generateText 作为嵌套 Agent 循环。
 *
 * 上下文语义（用户拍板）：继承主 Agent 系统提示 + 带入主 Agent 对话记录
 * （过滤 think 过程），再叠加 task；不应用主 Agent 的 input/output transformers。
 *
 * 工具集：主工具池按 allowlist 精确白名单过滤（needsApproval 覆盖为 false，子循环内不审批）
 * + subagent 自己的 enabledSkills 构建的 skill 工具。
 *
 * 执行轨迹：运行期间把工具调用步骤实时上报给 [monitor]，供执行轨迹页展示；
 * 并发由 [SubAgentRunMonitor.tryAcquire] 按对话限额。
 */
class SubAgentRunner(
    private val generationHandler: GenerationHandler,
    private val monitor: SubAgentRunMonitor,
) {
    suspend fun run(
        subAgent: SubAgent,
        assistant: Assistant,
        settings: Settings,
        conversationSystemPrompt: String?,
        conversationHistory: List<UIMessage>,
        task: String,
        context: String?,
        toolCatalog: List<Tool>,
        allSkills: List<SkillMetadata>,
        conversationId: Uuid? = null,
        /** General 实例的展示标签；预设 subagent 为 null（用定义名） */
        label: String? = null,
        /** General 调用时由主模型指定的类别；null = 用定义里的 toolAllowlist */
        allowlistOverride: Set<SubAgentToolCategory>? = null,
    ): String {
        if (!monitor.tryAcquire(conversationId, SUBAGENT_CONCURRENCY_LIMIT_PER_CONVERSATION)) {
            return buildSubAgentResultJson(
                status = "error",
                result = "Concurrency limit reached: at most ${SUBAGENT_CONCURRENCY_LIMIT_PER_CONVERSATION} subagents can run at the same time in this conversation. Wait for one to finish before dispatching again.",
                steps = 0,
                usage = null,
            )
        }
        // runId 唯一：并行实例在轨迹里各自独立记录
        val runId = Uuid.random()
        val displayName = label?.trim()?.takeIf { it.isNotEmpty() } ?: subAgent.name
        try {
            monitor.start(runId, subAgent.id, displayName, task, conversationId)
            val result = withTimeoutOrNull(subAgent.timeoutMs) {
                runInternal(
                    subAgent = subAgent,
                    assistant = assistant,
                    settings = settings,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationHistory = conversationHistory,
                    task = task,
                    context = context,
                    toolCatalog = toolCatalog,
                    allSkills = allSkills,
                    allowlist = allowlistOverride ?: subAgent.toolAllowlist,
                    runId = runId,
                )
            } ?: buildSubAgentResultJson(
                status = "timeout",
                result = "Subagent timed out after ${subAgent.timeoutMs}ms",
                steps = 0,
                usage = null,
            )
            val (status, message) = when {
                "\"status\":\"success\"" in result -> SubAgentRunStatus.SUCCESS to ""
                "\"status\":\"timeout\"" in result -> SubAgentRunStatus.TIMEOUT to "timed out"
                "\"status\":\"error\"" in result -> SubAgentRunStatus.ERROR to "failed"
                else -> SubAgentRunStatus.ERROR to "unknown"
            }
            monitor.finish(runId, status, result = result, message = message)
            return result
        } finally {
            monitor.release(conversationId)
        }
    }

    private suspend fun runInternal(
        subAgent: SubAgent,
        assistant: Assistant,
        settings: Settings,
        conversationSystemPrompt: String?,
        conversationHistory: List<UIMessage>,
        task: String,
        context: String?,
        toolCatalog: List<Tool>,
        allSkills: List<SkillMetadata>,
        allowlist: Set<SubAgentToolCategory>,
        runId: Uuid,
    ): String {
        // 模型解析链：全局子代理模型 -> 助手模型 -> 全局默认模型
        val model = settings.findModelById(settings.subagentModelId, assistant.chatModelId)
            ?: settings.findModelById(settings.chatModelId)
            ?: return buildSubAgentResultJson(
                status = "error",
                result = "Model not found for subagent '${subAgent.name}': configure a chat model first",
                steps = 0,
                usage = null,
            )

        // 合成"虚拟 Assistant"：主 effectiveSystemPrompt + subagent 专属提示
        val effectiveMainPrompt =
            if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                conversationSystemPrompt
            } else {
                assistant.systemPrompt
            }
        val subAssistant = assistant.copy(
            id = Uuid.random(),
            name = subAgent.name,
            systemPrompt = listOfNotNull(effectiveMainPrompt, subAgent.systemPrompt)
                .joinToString("\n\n")
                .trim(),
        )

        val tools = buildList {
            addAll(
                toolCatalog
                    .filter { matchesToolAllowlist(it.name, allowlist) }
                    .map { it.copy(needsApproval = { false }) }
            )
            if (subAgent.enabledSkills.isNotEmpty()) {
                addAll(createSkillTools(subAgent.enabledSkills, allSkills))
            }
        }

        val taskMessage = if (context.isNullOrBlank()) {
            task
        } else {
            "$task\n\n<context>\n$context\n</context>"
        }
        // 主 Agent 对话记录（去 think） + 任务；不传主 Agent 的 transformers
        val messages = stripReasoning(conversationHistory) + UIMessage.user(taskMessage)
        // 本次运行新增消息的起始下标：轨迹只记录本次运行自己的工具调用，排除继承的主对话历史
        val ownMessageStart = messages.size

        var finalMessages: List<UIMessage> = emptyList()
        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = messages,
            assistant = subAssistant,
            tools = tools,
            maxSteps = subAgent.maxSteps,
            processingStatus = MutableStateFlow(null),
            conversationSystemPrompt = null, // 已合成进 subAssistant.systemPrompt
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) {
                finalMessages = chunk.messages
                monitor.updateSteps(
                    runId,
                    extractSteps(finalMessages, fromIndex = ownMessageStart)
                )
            }
        }

        val lastAssistantText = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()

        return if (lastAssistantText.isNullOrBlank()) {
            buildSubAgentResultJson(
                status = "error",
                result = "Reached max steps (${subAgent.maxSteps}) without a final answer",
                steps = subAgent.maxSteps,
                usage = null,
            )
        } else {
            buildSubAgentResultJson(
                status = "success",
                result = lastAssistantText,
                steps = finalMessages.size,
                usage = finalMessages.lastOrNull()?.usage,
            )
        }
    }

    /** 从本次运行新增的消息中提取已执行的工具调用（工具名 + 简略输入/输出） */
    private fun extractSteps(messages: List<UIMessage>, fromIndex: Int): List<SubAgentRunStep> = buildList {
        messages.drop(fromIndex).forEach { message ->
            message.parts.forEach { part ->
                if (part is UIMessagePart.Tool && part.isExecuted) {
                    val input = part.input.trim().replace('\n', ' ').take(80)
                    val outputText = part.output
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString(" ") { it.text }
                        .trim()
                        .replace('\n', ' ')
                        .take(120)
                    add(
                        SubAgentRunStep(
                            toolName = part.toolName,
                            inputPreview = input,
                            outputPreview = outputText,
                        )
                    )
                }
            }
        }
    }
}
