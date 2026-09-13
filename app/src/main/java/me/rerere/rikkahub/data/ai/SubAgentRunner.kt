package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
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
 * Subagent 执行核心：复用 GenerationLoop.generateText 作为嵌套 Agent 循环。
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
    private val generationLoop: GenerationLoop,
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
        /** 覆盖运行模型（如 GOAL 评估模型）；null = 走 subagentModelId/助手模型链路 */
        modelOverride: Model? = null,
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
            // 用 async 承载本次运行，并登记 Job：外部「停止全部」可取消它，
            // 取消只终止这一个实例并返回「已停止」结果，不影响主生成循环。
            // LAZY 启动：必须先登记 Job 再启动协程，否则「停止全部」可能落在
            // 协程已启动但尚未登记的窗口里，导致漏掉这次运行。
            val result = coroutineScope {
                val deferred = async(start = CoroutineStart.LAZY) {
                    withTimeoutOrNull(subAgent.timeoutMs) {
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
                            modelOverride = modelOverride,
                        )
                    } ?: buildSubAgentResultJson(
                        status = "timeout",
                        result = "Subagent timed out after ${subAgent.timeoutMs}ms",
                        steps = 0,
                        usage = null,
                        runId = runId.toString(),
                    )
                }
                monitor.registerJob(runId, deferred)
                deferred.start()
                try {
                    deferred.await()
                } catch (e: CancellationException) {
                    // 父协程被取消（如用户打断整轮生成）：向上传播，保持结构化并发
                    if (!currentCoroutineContext().isActive) throw e
                    // 否则是「停止全部」主动取消本实例：交回一个普通结果，主循环继续
                    buildSubAgentResultJson(
                        status = "cancelled",
                        result = "Subagent was stopped by the user.",
                        steps = 0,
                        usage = null,
                        runId = runId.toString(),
                    )
                } finally {
                    monitor.unregisterJob(runId)
                }
            }
            val (status, message) = when {
                "\"status\":\"success\"" in result -> SubAgentRunStatus.SUCCESS to ""
                "\"status\":\"timeout\"" in result -> SubAgentRunStatus.TIMEOUT to "timed out"
                "\"status\":\"cancelled\"" in result -> SubAgentRunStatus.CANCELLED to "stopped"
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
        modelOverride: Model?,
    ): String {
        // 模型解析链：显式覆盖（GOAL 评估）-> 全局子代理模型 -> 助手模型 -> 全局默认模型
        val model = modelOverride
            ?: settings.findModelById(settings.subagentModelId, assistant.chatModelId)
            ?: settings.findModelById(settings.chatModelId)
            ?: return buildSubAgentResultJson(
                status = "error",
                result = "Model not found for subagent '${subAgent.name}': configure a chat model first",
                steps = 0,
                usage = null,
                runId = runId.toString(),
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
            // 终局报告工具：子代理完成时用它把最终报告写给主 Agent
            add(buildSubAgentReportTool())
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
        generationLoop.generateText(
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
                    extractRunProcess(finalMessages, fromIndex = ownMessageStart)
                )
            }
        }

        // 本次运行产生的消息数（finalMessages 含继承的主对话历史，需减去起始下标才是自己的步数）
        val ownSteps = (finalMessages.size - ownMessageStart).coerceAtLeast(0)

        // 报告优先取 submit_report 提交的正文；未调用则回退最后一条助手文本
        val report = extractSubAgentReport(finalMessages, ownMessageStart)
        val lastAssistantText = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()

        return when {
            report != null -> buildSubAgentResultJson(
                status = if (report.isNotBlank()) "success" else "error",
                result = report.ifBlank { "submit_report was called without a report" },
                steps = ownSteps,
                usage = finalMessages.lastOrNull()?.usage,
                runId = runId.toString(),
            )
            lastAssistantText.isNullOrBlank() -> buildSubAgentResultJson(
                status = "error",
                result = "Reached max steps (${subAgent.maxSteps}) without a final answer",
                steps = subAgent.maxSteps,
                usage = null,
                runId = runId.toString(),
            )
            else -> buildSubAgentResultJson(
                status = "success",
                result = lastAssistantText,
                steps = ownSteps,
                usage = finalMessages.lastOrNull()?.usage,
                runId = runId.toString(),
            )
        }
    }
}
