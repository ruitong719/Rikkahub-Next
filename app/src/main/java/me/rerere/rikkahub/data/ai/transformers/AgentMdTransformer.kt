package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * AGENTS.md 指令注入转换器。
 *
 * 指令来源（优先级从高到低）：
 * 1. `/agent` 目录（挂载自 filesDir/agent，对所有工作区可见）下所有 `*.md` 文件，
 *    按「agent.md 优先，其余按文件名排序」拼接后整体注入
 * 2. 设置里的全局 agent.md 文本（settings.globalAgentMd，目录为空时兜底）
 *
 * 追加到第一条 system 消息末尾；若没有 system 消息则插入一条（与 WorkspaceReminderTransformer 同模式）。
 * 注：subagent 复用主 Agent 的 systemPrompt 合成，但独立走 SubAgentRunner 的生成循环，
 * 不经过本转换器，因此该指令仅对主对话生效。
 */
class AgentMdTransformer : InputMessageTransformer {
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

    /** /agent 目录下的 *.md 优先（agent.md 排最前，其余按文件名排序）；无文件时回退到设置里的全局文本 */
    private fun resolveAgentMd(ctx: TransformerContext): String? {
        val agentDir = File(ctx.context.filesDir, "agent")
        val mdFiles = agentDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
            ?.sortedWith(compareBy({ it.name.lowercase() != "agent.md" }, { it.name }))
            .orEmpty()

        if (mdFiles.isNotEmpty()) {
            return mdFiles.joinToString("\n\n") { file ->
                file.readText().trim()
            }.trim().ifBlank { null }
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
