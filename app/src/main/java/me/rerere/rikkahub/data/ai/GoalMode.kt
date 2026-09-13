package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.GoalVerdictKind
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolCategory

/** 内置目标评估器名称（系统自动拉起，不进用户 subagent 列表） */
internal const val GOAL_EVALUATOR_NAME = "Goal Evaluator"

/**
 * 目标评估器：一个固定配置的内置 subagent。
 *
 * 设计要点（对齐 Claude Code /goal 的评估器）：
 * - **以对话记录为主**：判断依据是主模型在会话里给出的证据（跑了什么、结果如何）；
 * - 只读工具（read/glob/grep/git）仅在必要时使用，不放行任何写/执行工具；
 * - 交卷用 submit_report，首行必须是 `OUTCOME: ACHIEVED | NOT_MET | IMPOSSIBLE`，其余为原因。
 */
fun buildGoalEvaluatorSubAgent(): SubAgent = SubAgent(
    name = GOAL_EVALUATOR_NAME,
    description = "System subagent that evaluates whether the conversation has achieved the goal condition.",
    systemPrompt = """
        You are the Goal Evaluator, a system subagent that decides whether the main agent has achieved the goal.

        The goal condition is provided in the task. Judge ONLY against that condition.

        How to decide:
        - Base your verdict primarily on the CONVERSATION HISTORY: what the main agent did and what results it
          reported (commands it ran, tests, file contents it printed, errors, etc.).
        - You MAY use the read-only tools (read/glob/grep/git) to confirm a specific claim, but PREFER not to:
          only look at a file when the transcript alone is not enough. Never modify anything.
        - Treat the goal as ACHIEVED only if the transcript shows it is genuinely done. If evidence is missing,
          partial, or contradicts the claim -> NOT_MET and say exactly what is still missing.
        - Use IMPOSSIBLE only when the goal can never be satisfied (contradictory, blocked by something
          unrecoverable, or the requirement is unachievable), and explain why.

        Finish by calling submit_report exactly once. The FIRST LINE must be exactly one of:
        `OUTCOME: ACHIEVED`, `OUTCOME: NOT_MET`, or `OUTCOME: IMPOSSIBLE`.
        The rest is a short Markdown explanation: what you verified, and (for NOT_MET) the concrete next steps.
    """.trimIndent(),
    toolAllowlist = setOf(SubAgentToolCategory.READ),
    maxSteps = 20,
    timeoutMs = 120_000,
    requiresApproval = false,
)

/** 构造评估任务的 task 文本（评估器仍会收到主对话历史） */
fun buildGoalEvalTask(
    condition: String,
    todoSnapshot: String,
): String = buildString {
    appendLine("Goal completion review for this conversation.")
    appendLine()
    appendLine("<goal_condition>")
    appendLine(condition.ifBlank { "(no condition set)" })
    appendLine("</goal_condition>")
    appendLine()
    appendLine("<todo_snapshot>")
    appendLine(todoSnapshot.ifBlank { "(empty todo list)" })
    appendLine("</todo_snapshot>")
    appendLine()
    appendLine(
        "Decide whether the goal condition above has been achieved by the main agent. " +
            "Prefer the conversation history; only use read-only tools if needed. " +
            "Then call submit_report with your verdict (first line: OUTCOME: ACHIEVED | NOT_MET | IMPOSSIBLE)."
    )
}

/**
 * 解析评估报告首行的结论。识别技巧上偏保守：不确定时返回 [GoalVerdictKind.NOT_MET]，
 * 让主模型继续，而不是误判为已完成而提前结束。
 */
fun parseGoalVerdict(report: String): GoalVerdictKind {
    val firstLine = report.lineSequence().firstOrNull().orEmpty().trim().uppercase()
    val upper = report.uppercase()
    return when {
        firstLine.contains("OUTCOME: ACHIEVED") || firstLine == "ACHIEVED" -> GoalVerdictKind.ACHIEVED
        firstLine.contains("OUTCOME: IMPOSSIBLE") || firstLine == "IMPOSSIBLE" -> GoalVerdictKind.IMPOSSIBLE
        firstLine.contains("OUTCOME: NOT_MET") || firstLine.contains("OUTCOME: NOT MET") ||
            firstLine == "NOT_MET" || firstLine == "NOT MET" -> GoalVerdictKind.NOT_MET

        "IMPOSSIBLE" in upper -> GoalVerdictKind.IMPOSSIBLE
        "NOT MET" in upper || "NOT_MET" in upper -> GoalVerdictKind.NOT_MET
        "ACHIEVED" in upper || "COMPLETED" in upper -> GoalVerdictKind.ACHIEVED
        else -> GoalVerdictKind.NOT_MET
    }
}

/**
 * 判断异常是否属于「不可恢复」错误：这类错误应终止目标（清除循环）而不是继续重试。
 * 覆盖鉴权失败、额度/余额耗尽、上下文溢出、模型不可用；网络抖动/限流不在其列。
 */
fun Throwable.hasUnrecoverableGoalCause(): Boolean {
    val text = (message.orEmpty() + " " + (cause?.message.orEmpty())).lowercase()
    return listOf(
        "401", "403", "unauthorized", "forbidden", "invalid api key", "authentication failed",
        "insufficient", "quota", "balance", "billing", "credit",
        "context length", "maximum context", "context_length_exceeded", "too many tokens",
        "context window", "model not found", "no such model", "model_unavailable", "model unavailable",
    ).any { it in text }
}
