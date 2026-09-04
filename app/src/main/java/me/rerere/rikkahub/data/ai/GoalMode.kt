package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolCategory

/** Goal 模式评审基础设施：write/edit 轨迹提取、内置评审子代理构造、评审输出解析。 */

/** write/edit 工具名（与 WorkspaceTools 保持一致，改动时需同步） */
internal val FILE_MUTATION_TOOL_NAMES = setOf("write", "edit")

/** 内置评审子代理名称（系统自动拉起，不进用户 subagent 列表） */
internal const val GOAL_REVIEWER_NAME = "Goal Reviewer"

/** 评审结论 */
enum class GoalReviewOutcome {
    COMPLETED,
    INCOMPLETE,
    UNKNOWN,
}

/**
 * 从会话消息提取已执行的 write/edit 工具轨迹，交给评审子代理核对。
 * 只做摘要（路径/关键参数/输出头），避免把完整文件内容塞进上下文。
 */
fun extractFileMutationTrail(messages: List<UIMessage>): String {
    val entries = messages.asSequence()
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Tool>()
        .filter { it.isExecuted && it.toolName in FILE_MUTATION_TOOL_NAMES }
        .map { tool -> summarizeMutation(tool) }
        .toList()
    if (entries.isEmpty()) return "(no write/edit calls have been made in this session)"
    return entries.joinToString("\n")
}

private fun summarizeMutation(tool: UIMessagePart.Tool): String {
    val obj = runCatching { tool.inputAsJson().jsonObject }.getOrNull() ?: return "${tool.toolName} (unparseable input)"
    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "?"
    val base = when (tool.toolName) {
        "write" -> {
            val overwrite = obj["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            "write $path (overwrite=$overwrite)"
        }

        "edit" -> {
            val oldLen = obj["old_text"]?.jsonPrimitive?.contentOrNull?.length ?: 0
            val newLen = obj["new_text"]?.jsonPrimitive?.contentOrNull?.length ?: 0
            "edit $path (old $oldLen chars -> new $newLen chars)"
        }

        else -> "${tool.toolName} $path"
    }
    val outputText = tool.output
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString(" ") { it.text }
        .trim()
    return if (outputText.isBlank()) base else "$base | ${outputText.take(220)}"
}

/** 内置评审子代理：只读核对（read + 只读 shell），禁止修改，评审完用 submit_report 交报告 */
fun buildGoalReviewerSubAgent(): SubAgent = SubAgent(
    name = GOAL_REVIEWER_NAME,
    description = "System subagent that verifies whether the main agent completed its goal by checking file changes and todo progress.",
    systemPrompt = """
        You are the Goal Reviewer, a system subagent that verifies whether the main agent completed its goal.

        Context you receive with the task:
        - todo snapshot: the current todo list of the conversation. When present, item 0 is the GOAL statement written by the main agent.
        - write/edit trail: every write/edit tool call the main agent made in this conversation.

        Verification contract:
        - You may READ files and run READ-ONLY shell commands (ls, cat, find, git status/diff/log, wc, grep) to verify the changes.
          You MUST NOT modify any file, create anything, or run non-read-only commands.
        - The goal statement is the first todo item when present; otherwise treat the latest user request in the history as the goal.
        - Check that every promised deliverable exists, is correct, and consistent with the write/edit trail; check that todo items
          are completed (or legitimately cancelled) and the goal item itself is completed.
        - If the goal is fully achieved with no material defects -> OUTCOME: COMPLETED.
        - Otherwise -> OUTCOME: INCOMPLETE and list the concrete missing pieces / defects so the main agent can continue.
        - Finish by calling submit_report with your full review report. The FIRST LINE of the report must be exactly
          `OUTCOME: COMPLETED` or `OUTCOME: INCOMPLETE`; the rest is the Markdown review (what was verified, findings, actions needed).
    """.trimIndent(),
    toolAllowlist = setOf(SubAgentToolCategory.READ, SubAgentToolCategory.SHELL),
    maxSteps = 48,
    timeoutMs = 180_000,
    requiresApproval = false,
)

/** 构造评审任务描述（作为 subagent 的 task 参数） */
fun buildGoalReviewTask(
    todoSnapshot: String,
    trail: String,
): String = buildString {
    appendLine("Goal completion review for the conversation.")
    appendLine()
    appendLine("<goal_review_todo>")
    appendLine(todoSnapshot.ifBlank { "(empty todo list)" })
    appendLine("</goal_review_todo>")
    appendLine()
    appendLine("<goal_review_trail>")
    appendLine(trail)
    appendLine("</goal_review_trail>")
    appendLine()
    appendLine(
        "Verify whether the GOAL (first todo item when present; otherwise the latest user request) has been fully achieved. " +
            "Read the relevant files and run read-only commands to confirm. Then decide OUTCOME: COMPLETED or INCOMPLETE " +
            "and submit your full report via submit_report (first line: OUTCOME: ...)."
    )
}

/** 解析评审报告结论（首行 OUTCOME: COMPLETED / OUTCOME: INCOMPLETE） */
fun parseGoalReviewOutcome(report: String): GoalReviewOutcome {
    val firstLine = report.lineSequence().firstOrNull().orEmpty().trim().uppercase()
    return when {
        firstLine.contains("OUTCOME: COMPLETED") || firstLine == "COMPLETED" -> GoalReviewOutcome.COMPLETED
        firstLine.contains("OUTCOME: INCOMPLETE") || firstLine == "INCOMPLETE" -> GoalReviewOutcome.INCOMPLETE
        "COMPLETED" in report.uppercase() && "INCOMPLETE" !in report.uppercase() -> GoalReviewOutcome.COMPLETED
        else -> GoalReviewOutcome.UNKNOWN
    }
}