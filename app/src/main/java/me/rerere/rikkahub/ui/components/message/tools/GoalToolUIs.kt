package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Flag01
import me.rerere.hugeicons.stroke.Target01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * GOAL 模式的 set_goal / get_goal 两个工具调用的专属渲染器。
 *
 * - 折叠态（Summary）：set_goal 显示规范化后的目标条件；get_goal 显示当前状态与条件；
 * - 详情态（Preview）：完整条件 + 原始请求 + 逐轮评估判决历史，加载中给占位、执行异常回退通用 JSON。
 */

private const val GOAL_TITLE_MAX_CHARS = 40

/** set_goal：主模型把会话目标规范化写入时调用 */
object SetGoalToolUI : ToolUIRenderer {
    override val toolName: String = "set_goal"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Target01

    private fun conditionOf(context: ToolUIContext): String? =
        context.arguments.getStringContent("condition")

    @Composable
    override fun title(context: ToolUIContext): String {
        val condition = conditionOf(context)?.replace("\n", " ")?.trim()
            ?: return stringResource(R.string.tool_ui_set_goal_default)
        val truncated = if (condition.length > GOAL_TITLE_MAX_CHARS) {
            condition.take(GOAL_TITLE_MAX_CHARS) + "…"
        } else {
            condition
        }
        return stringResource(R.string.tool_ui_set_goal, truncated)
    }

    override fun hasSummary(context: ToolUIContext): Boolean = conditionOf(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val condition = conditionOf(context) ?: return
        Text(
            text = condition,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .shimmer(isLoading = context.loading),
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        // 仅执行异常（无输出且不在加载中）时回退通用 JSON 视图
        if (context.content == null && !context.loading) {
            DefaultToolPreview(context = context)
            return
        }
        if (context.content == null) {
            PendingPreview(stringResource(R.string.tool_ui_set_goal_pending))
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.6f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GoalStatusRow(
                label = stringResource(R.string.tool_ui_set_goal_ok),
                color = DiffAddedColor,
            )
            LabeledBlock(
                label = stringResource(R.string.tool_ui_goal_condition),
                text = conditionOf(context).orEmpty(),
            )
            context.content.getStringContent("message")
                ?.takeIf { it.isNotBlank() }
                ?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

/** get_goal：读取当前目标、状态与最近一次评估判决 */
object GetGoalToolUI : ToolUIRenderer {
    override val toolName: String = "get_goal"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Flag01

    private fun statusOf(context: ToolUIContext): String? =
        context.content.getStringContent("goal_status")

    @Composable
    override fun title(context: ToolUIContext): String {
        val status = statusOf(context) ?: return stringResource(R.string.tool_ui_get_goal_default)
        return stringResource(R.string.tool_ui_get_goal, goalStatusLabel(status))
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val condition = context.content.getStringContent("condition")
        val lastVerdict = context.content.getStringContent("last_verdict")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .shimmer(isLoading = context.loading),
        ) {
            GoalDot(color = goalStatusColor(statusOf(context)))
            Text(
                text = condition?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.tool_ui_goal_condition_unset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (lastVerdict != null) {
                Text(
                    text = goalVerdictLabel(lastVerdict),
                    style = MaterialTheme.typography.labelSmall,
                    color = goalVerdictColor(lastVerdict),
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        if (context.content == null && !context.loading) {
            DefaultToolPreview(context = context)
            return
        }
        if (context.content == null) {
            PendingPreview(stringResource(R.string.tool_ui_goal_pending))
            return
        }
        val status = statusOf(context)
        if (status == null && context.content.getStringContent("status") == "none") {
            Text(
                text = context.content.getStringContent("message")
                    ?: stringResource(R.string.tool_ui_goal_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
            return
        }
        GoalPreview(context)
    }

    /** 详情：状态徽章 + 条件/原始请求 + 轮数 + 逐轮判决历史 */
    @Composable
    private fun GoalPreview(context: ToolUIContext) {
        val content = context.content ?: return
        val status = statusOf(context)
        val condition = content.getStringContent("condition").orEmpty()
        val request = content.getStringContent("request").orEmpty()
        val turns = content.jsonObjectOrNull?.get("turns_evaluated")
            ?.jsonPrimitiveOrNull?.intOrNull
        val history = remember(context) { goalHistoryOf(content) }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GoalStatusRow(
                label = goalStatusLabel(status),
                color = goalStatusColor(status),
            )
            if (turns != null) {
                Text(
                    text = stringResource(R.string.tool_ui_goal_turns, turns),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val conditionUnset = stringResource(R.string.tool_ui_goal_condition_unset)
            LabeledBlock(
                label = stringResource(R.string.tool_ui_goal_condition),
                text = condition.ifBlank { conditionUnset },
            )
            if (request.isNotBlank() && request != condition) {
                LabeledBlock(
                    label = stringResource(R.string.tool_ui_goal_request),
                    text = request,
                )
            }
            if (history.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_goal_history),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                history.asReversed().forEach { entry -> GoalVerdictCard(entry) }
            }
        }
    }
}

private data class GoalHistoryEntry(
    val turn: Int,
    val verdict: String,
    val reason: String,
)

private fun goalHistoryOf(content: kotlinx.serialization.json.JsonElement): List<GoalHistoryEntry> {
    val array = content.jsonObjectOrNull?.get("history")?.jsonArrayOrNull ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element.jsonObjectOrNull ?: return@mapNotNull null
        GoalHistoryEntry(
            turn = obj["turn"]?.jsonPrimitiveOrNull?.intOrNull ?: 0,
            verdict = obj["verdict"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            reason = obj["reason"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        )
    }
}

@Composable
private fun GoalVerdictCard(entry: GoalHistoryEntry) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_goal_round, entry.turn),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = goalVerdictLabel(entry.verdict),
                    style = MaterialTheme.typography.labelMedium,
                    color = goalVerdictColor(entry.verdict),
                )
            }
            if (entry.reason.isNotBlank()) {
                Text(
                    text = entry.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun GoalStatusRow(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GoalDot(color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun GoalDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun LabeledBlock(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun goalStatusLabel(status: String?): String = when (status) {
    "active" -> stringResource(R.string.goal_status_active)
    "achieved" -> stringResource(R.string.goal_status_achieved)
    "impossible" -> stringResource(R.string.goal_status_impossible)
    "stopped" -> stringResource(R.string.goal_status_stopped)
    else -> "…"
}

@Composable
private fun goalStatusColor(status: String?): Color = when (status) {
    "achieved" -> DiffAddedColor
    "impossible" -> MaterialTheme.colorScheme.error
    "stopped" -> MaterialTheme.colorScheme.outline
    "active" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun goalVerdictLabel(verdict: String?): String = when (verdict) {
    "achieved" -> stringResource(R.string.goal_verdict_achieved)
    "impossible" -> stringResource(R.string.goal_verdict_impossible)
    "not_met" -> stringResource(R.string.goal_verdict_not_met)
    "none" -> "—"
    else -> verdict ?: "—"
}

@Composable
private fun goalVerdictColor(verdict: String?): Color = when (verdict) {
    "achieved" -> DiffAddedColor
    "impossible" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
