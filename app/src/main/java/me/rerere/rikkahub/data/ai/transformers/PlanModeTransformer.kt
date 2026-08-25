package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * PLAN 模式提示注入（配合 PermissionModePolicy 的工具拦截）。
 *
 * 告知模型当前处于只读研究模式：变更类工具已被禁用、subagent 不可用，
 * 应当调研后产出计划，由用户切回 build 模式执行。
 */
class PlanModeTransformer(
    private val enabled: Boolean,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!enabled) return messages

        // 追加到第一条 system 消息; 若不存在则插入一条（与 AgentMdTransformer 同模式）
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$PROMPT")
            }
        } else {
            listOf(UIMessage.system(prompt = PROMPT)) + messages
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

    companion object {
        private val PROMPT = """
            <plan_mode>
            You are currently in PLAN mode (read-only research):
            - Mutating tools are disabled: you cannot modify files, run shell commands (`bash` is fully disabled), dispatch subagents, or export/backup data.
            - Research with read-only tools (`read`, search, conversation tools) only.
            - When you have enough context, present a concise implementation plan (steps, files to change, risks). Do NOT attempt workarounds to bypass plan mode.
            - The user will switch back to build mode when they want the plan executed.
            </plan_mode>
        """.trimIndent()
    }
}
