package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository

/**
 * AGENTS.md 指令注入转换器。
 *
 * 指令来源（优先级从高到低）：
 * 1. 当前助手绑定的工作区根目录 `/workspace/agent.md`（proot 环境存在时读取，覆盖设置）
 * 2. 设置里的全局 agent.md 文本（settings.globalAgentMd）
 *
 * 追加到第一条 system 消息末尾；若没有 system 消息则插入一条（与 WorkspaceReminderTransformer 同模式）。
 * 注：subagent 复用主 Agent 的 systemPrompt 合成，但独立走 SubAgentRunner 的生成循环，
 * 不经过本转换器，因此该指令仅对主对话生效。
 */
class AgentMdTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val agentMd = resolveAgentMd(ctx) ?: return messages

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$agentMd")
            }
        } else {
            listOf(UIMessage.system(prompt = agentMd)) + messages
        }
    }

    /** 工作区 agent.md 优先；无工作区或文件不存在时回退到设置里的全局文本 */
    private suspend fun resolveAgentMd(ctx: TransformerContext): String? {
        val workspaceId = ctx.assistant.workspaceId?.toString()
        if (workspaceId != null) {
            runCatching {
                val text = workspaceRepository.readText(workspaceId, "/workspace/agent.md").trim()
                if (text.isNotBlank()) return text
            }
        }
        return ctx.settings.globalAgentMd.trim().ifBlank { null }
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
