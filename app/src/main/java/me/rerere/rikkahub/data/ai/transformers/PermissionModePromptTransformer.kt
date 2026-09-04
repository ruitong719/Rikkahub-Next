package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.PermissionMode

/**
 * 权限模式提示注入（配合 PermissionModePolicy 的工具改写）。
 *
 * 注入点对齐 opencode session/reminders.ts：把当前模式的有效提示词作为新的 Text part
 * 追加到最后一条用户消息之后，而不是改动 system prompt——保持前缀稳定以提升缓存命中。
 * 提示词为空串时不注入（用户在设置里清空即视为关闭该模式的提示）。
 */
class PermissionModePromptTransformer(
    private val mode: PermissionMode,
    private val planPrompt: String,
    private val buildPrompt: String,
    private val yoloPrompt: String,
    private val goalPrompt: String,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val prompt = when (mode) {
            PermissionMode.PLAN -> planPrompt
            PermissionMode.BUILD -> buildPrompt
            PermissionMode.YOLO -> yoloPrompt
            PermissionMode.GOAL -> goalPrompt
        }.trim()
        if (prompt.isEmpty()) return messages

        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        return if (lastUserIndex >= 0) {
            messages.toMutableList().apply {
                this[lastUserIndex] = this[lastUserIndex].copy(
                    parts = this[lastUserIndex].parts + UIMessagePart.Text("\n\n$prompt"),
                )
            }
        } else {
            // 极少数无用户消息的场景（如后台续跑首轮回退）：退回插入 system 消息
            listOf(UIMessage.system(prompt = prompt)) + messages
        }
    }
}
