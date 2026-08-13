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
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.SubAgent
import kotlin.uuid.Uuid

/**
 * Subagent 执行核心：复用 GenerationHandler.generateText 作为嵌套 Agent 循环。
 *
 * 上下文语义（用户拍板）：继承主 Agent 系统提示/记忆 + 带入主 Agent 对话记录
 * （过滤 think 过程），再叠加 task；不应用主 Agent 的 input/output transformers。
 *
 * 工具集：主工具池按 allowlist 过滤（needsApproval 覆盖为 false，派发时已一次性审批）
 * + subagent 自己的 enabledSkills 构建的 skill 工具。
 */
class SubAgentRunner(
    private val generationHandler: GenerationHandler,
) {
    suspend fun run(
        subAgent: SubAgent,
        assistant: Assistant,
        settings: Settings,
        conversationSystemPrompt: String?,
        conversationHistory: List<UIMessage>,
        task: String,
        context: String?,
        memories: List<AssistantMemory>?,
        toolCatalog: List<Tool>,
        allSkills: List<SkillMetadata>,
    ): String = withTimeoutOrNull(subAgent.timeoutMs) {
        val model = settings.findModelById(
            subAgent.modelId ?: assistant.chatModelId ?: settings.chatModelId
        ) ?: return@withTimeoutOrNull buildSubAgentResultJson(
            status = "error",
            result = "Model not found for subagent '${subAgent.name}'",
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
            chatModelId = subAgent.modelId,
        )

        val tools = buildList {
            addAll(
                toolCatalog
                    .filter { matchesToolAllowlist(it.name, subAgent.toolAllowlist) }
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

        var finalMessages: List<UIMessage> = emptyList()
        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = messages,
            assistant = subAssistant,
            memories = memories,
            tools = tools,
            maxSteps = subAgent.maxSteps,
            processingStatus = MutableStateFlow(null),
            conversationSystemPrompt = null, // 已合成进 subAssistant.systemPrompt
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) {
                finalMessages = chunk.messages
            }
        }

        val lastAssistantText = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()

        if (lastAssistantText.isNullOrBlank()) {
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
    } ?: buildSubAgentResultJson(
        status = "timeout",
        result = "Subagent timed out after ${subAgent.timeoutMs}ms",
        steps = 0,
        usage = null,
    )
}
