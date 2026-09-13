package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.GoalState
import me.rerere.rikkahub.data.model.GoalStatus

/**
 * GOAL 模式的「评估完成提醒」注入。
 *
 * 评估器判 NOT_MET（或需要催 set_goal）后，会把一条 `<system-reminder>` 文本写入
 * [GoalState.pendingReminder]；本 transformer 在组装请求时把它追加到最后一条用户消息之后，
 * 随后通过 [onConsumed] 清空——**只注入一次**，且不落进对话消息，界面不会出现内部标记。
 *
 * 通过 [goalProvider] 读实时状态而非快照：同一次生成里每个工具步骤都会重建请求，
 * 若用快照，清理后仍会在后续步骤重复注入。
 */
class GoalContextTransformer(
    private val goalProvider: () -> GoalState?,
    private val onConsumed: suspend () -> Unit = {},
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val goal = goalProvider() ?: return messages
        if (goal.status != GoalStatus.ACTIVE) return messages
        val reminder = goal.pendingReminder?.takeIf { it.isNotBlank() } ?: return messages

        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        val updated = if (lastUserIndex >= 0) {
            messages.toMutableList().apply {
                this[lastUserIndex] = this[lastUserIndex].copy(
                    parts = this[lastUserIndex].parts + UIMessagePart.Text("\n\n$reminder"),
                )
            }
        } else {
            listOf(UIMessage.system(reminder)) + messages
        }
        onConsumed()
        return updated
    }
}
