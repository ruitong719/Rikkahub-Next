package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.AskUserQuestion
import me.rerere.rikkahub.data.ai.tools.local.parseAskUserQuestions
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.utils.JsonInstant

/**
 * ask_user 专属渲染器：展示终态（已答 Q/A、YOLO 不可用提示）。
 * Pending 交互表单不走注册式框架（需要 onToolAnswer 回调提交），在 ChatMessageToolStep 单独渲染。
 */
object AskUserToolUI : ToolUIRenderer {
    override val toolName: String = "ask_user"

    private const val TITLE_MAX_CHARS = 40

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.BubbleChatQuestion

    private fun questionsOf(context: ToolUIContext): List<AskUserQuestion> =
        parseAskUserQuestions(context.arguments)

    /** 已答状态：approvalState=Answered 且答案负载可解析时返回 题→答案文本 映射 */
    private fun answeredOf(context: ToolUIContext): List<Pair<AskUserQuestion, String>>? {
        val state = context.tool.approvalState as? ToolApprovalState.Answered ?: return null
        return runCatching {
            val answers = JsonInstant.parseToJsonElement(state.answer)
                .jsonObject["answers"]?.jsonObject ?: return null
            questionsOf(context).map { q ->
                val value = q.id.let { id ->
                    answers[id]?.let { el ->
                        when (el) {
                            is kotlinx.serialization.json.JsonPrimitive ->
                                el.contentOrNull ?: ""
                            is kotlinx.serialization.json.JsonArray ->
                                el.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                                    .joinToString(", ")
                            else -> ""
                        }
                    } ?: ""
                }
                q to value
            }
        }.getOrNull()
    }

    /** 执行异常/YOLO 替代结果：输出含 error 字段时给出提示文案 */
    private fun deniedErrorOf(context: ToolUIContext): String? {
        if (context.tool.approvalState is ToolApprovalState.Answered) return null
        return runCatching {
            JsonInstant.parseToJsonElement(
                context.tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            ).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    @Composable
    override fun title(context: ToolUIContext): String {
        val questions = questionsOf(context)
        val single = questions.firstOrNull()
        val base = if (questions.size <= 1) {
            single?.header?.takeIf { it.isNotBlank() }
                ?: single?.question?.replace("\n", " ")?.trim()
        } else {
            null
        }
        return when {
            base != null -> if (base.length > TITLE_MAX_CHARS) base.take(TITLE_MAX_CHARS) + "…" else base
            questions.isNotEmpty() -> stringResource(R.string.chat_message_tool_ask_questions, questions.size)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        answeredOf(context) != null || deniedErrorOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        answeredOf(context)?.let { answered ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                answered.forEach { (q, answer) ->
                    if (q.question.isNotBlank() && answer.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            q.header.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = q.question,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = answer,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            return
        }
        deniedErrorOf(context)?.let {
            Text(
                text = stringResource(R.string.chat_message_tool_ask_user_yolo),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val answered = answeredOf(context)
        val denied = deniedErrorOf(context)
        when {
            context.loading -> PendingPreview(stringResource(R.string.tool_ui_ask_user_pending))
            answered != null -> AskUserPreview(answered)
            denied != null -> DeniedPreview(denied)
            else -> DefaultToolPreview(context = context)
        }
    }

    /** 详情：每题题目（含 header）与答案，选项一并列出 */
    @Composable
    private fun AskUserPreview(answered: List<Pair<AskUserQuestion, String>>) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            answered.forEachIndexed { index, (q, answer) ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    q.header.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        text = q.question,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (q.options.isNotEmpty()) {
                        q.options.forEach { option ->
                            Text(
                                text = if (option.description.isBlank()) {
                                    option.label
                                } else {
                                    "${option.label} — ${option.description}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = if (answer.isBlank()) "—" else answer,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = DiffAddedColor,
                    )
                }
            }
        }
    }

    /** 详情：YOLO 不可用提示 + 原始错误 */
    @Composable
    private fun DeniedPreview(error: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.chat_message_tool_ask_user_yolo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}