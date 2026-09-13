package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.GoalVerdictKind
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolCategory
import java.io.IOException

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
 * 不可恢复错误的特征子串：这类错误重试也不会成功，应直接终止目标循环。
 * 覆盖鉴权/权限、额度/计费、上下文超限、模型不存在；限流（429）、超时、连接中断等
 * 临时性故障不在此列，交由 [hasRetryableNetworkCause] 走退避重试。
 *
 * 用整词/短语而非过短的子串，避免误伤（如 "credit" 曾会命中正常文本里的 "credits"）。
 */
private val UNRECOVERABLE_PATTERNS = listOf(
    // 鉴权 / 权限
    "unauthorized", "forbidden", "invalid api key", "invalid_api_key", "authentication failed",
    "authentication error", "invalid token", "api key not valid", "permission denied",
    // 额度 / 计费
    "insufficient_quota", "insufficient quota", "exceeded your current quota", "quota exceeded",
    "insufficient balance", "insufficient funds", "out of credit", "no credit", "billing",
    // 上下文超限
    "context_length_exceeded", "context length", "maximum context", "context window",
    "too many tokens", "token limit exceeded",
    // 模型不存在 / 无访问权限
    "model not found", "no such model", "model_unavailable", "model unavailable",
    "does not exist or you do not have access",
)

/** HTTP 鉴权状态码用词边界匹配，避免把 "1401" / "5403" 之类的数字误判为鉴权失败 */
private val HTTP_STATUS_CODE_REGEX = Regex("""\b(401|403)\b""")

/**
 * 判断异常是否属于「不可恢复」错误：这类错误应终止目标（清除循环）而不是继续重试。
 *
 * 判定顺序有讲究：沿 cause 链遇到 [IOException]（网络抖动/超时/连接中断）一律返回 false，
 * 交给 [hasRetryableNetworkCause] 退避重试；其余再按特征子串/状态码匹配。
 */
fun Throwable.hasUnrecoverableGoalCause(): Boolean {
    // 网络层错误按可恢复处理，即使报错文本里恰好含有关键词
    var node: Throwable? = this
    while (node != null) {
        if (node is IOException) return false
        node = node.cause
    }
    // 扫描整条 cause 链（不再只看直接 cause），拼成小写文本统一匹配
    val text = buildString {
        var cur: Throwable? = this@hasUnrecoverableGoalCause
        while (cur != null) {
            cur.message?.let { append(it.lowercase()).append(' ') }
            cur = cur.cause
        }
    }
    if (text.isBlank()) return false
    return UNRECOVERABLE_PATTERNS.any { it in text } || HTTP_STATUS_CODE_REGEX.containsMatchIn(text)
}
