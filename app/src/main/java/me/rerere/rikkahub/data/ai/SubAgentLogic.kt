package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.SubAgentToolCategory
import kotlin.uuid.Uuid

/**
 * Subagent 纯逻辑函数：与 Android/生成循环无关，可独立 JVM 单测。
 */

/** 每个对话内同时运行的 subagent 实例上限（所有类型合并计数） */
const val SUBAGENT_CONCURRENCY_LIMIT_PER_CONVERSATION = 5

/** subagent 向主 Agent 提交最终报告的工具名（仅在子循环内暴露，不进入主工具池） */
const val SUBAGENT_REPORT_TOOL_NAME = "submit_report"

/**
 * subagent 专用终局工具：子代理完成后用它把最终报告写给主 Agent。
 * 调用后本次运行立即结束（terminal），运行结果取该工具的 report 参数。
 */
fun buildSubAgentReportTool(): Tool = Tool(
    name = SUBAGENT_REPORT_TOOL_NAME,
    description = buildString {
        append(
            "Write the final report for the main agent and end this subagent run. " +
                "Call this exactly once, when you have finished the task (or determined you cannot complete it). " +
                "The 'report' parameter is the complete report returned to the main agent; " +
                "write it in Markdown and include what you did, what you found, the outcome, " +
                "and any caveats or follow-ups. Do not call any other tools after this one."
        )
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("report", buildJsonObject {
                    put("type", "string")
                    put("description", "The final report (Markdown) for the main agent")
                })
            },
            required = listOf("report"),
        )
    },
    terminal = true,
    execute = { input ->
        val report = input.jsonObject
            .get("report")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        check(report.isNotEmpty()) { "report is required" }
        listOf(UIMessagePart.Text("Report submitted (${report.length} chars). The run will now end."))
    },
)

/**
 * 从本次运行的消息中提取 submit_report 提交的报告正文；未调用返回 null，
 * 调用但内容为空返回空串（由调用方据此判定失败）。
 */
fun extractSubAgentReport(messages: List<UIMessage>, fromIndex: Int): String? {
    val reportTool = messages.drop(fromIndex)
        .asReversed()
        .flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Tool>()
        .firstOrNull { it.toolName == SUBAGENT_REPORT_TOOL_NAME && it.isExecuted }
        ?: return null
    return reportTool.inputAsJson()
        .jsonObject
        .get("report")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        .orEmpty()
}

/**
 * 从本次运行新增的消息中提取过程时间线（thinking → 中间叙述 → 工具调用），
 * 供执行轨迹页实时展示。工具调用仅携带成败，不携带输出内容。
 */
fun extractRunProcess(messages: List<UIMessage>, fromIndex: Int): List<SubAgentRunStep> = buildList {
    messages.drop(fromIndex).forEach { message ->
        val hasToolCall = message.parts.any { it is UIMessagePart.Tool }
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Reasoning ->
                    if (part.reasoning.isNotBlank()) {
                        add(SubAgentRunStep.Thinking(part.reasoning))
                    }

                is UIMessagePart.Text ->
                    // 仅展示工具调用之间的中间叙述，避免与最终结果重复
                    if (hasToolCall && part.text.isNotBlank()) {
                        add(SubAgentRunStep.IntermediateText(part.text))
                    }

                is UIMessagePart.Tool -> add(
                    SubAgentRunStep.ToolCall(
                        toolName = part.toolName,
                        input = part.input.trim(),
                        executed = part.isExecuted,
                        succeeded = isSuccessfulToolOutput(part),
                    )
                )

                else -> Unit
            }
        }
    }
}

/** 工具调用是否成功：已执行且输出不是执行异常的 {"error": ...} 包装 */
private fun isSuccessfulToolOutput(tool: UIMessagePart.Tool): Boolean {
    if (!tool.isExecuted) return false
    val text = tool.output
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    return !text.startsWith("""{"error""")
}

/** 工具名 slug：仅保留 ASCII 字母数字，其余转下划线、压缩连续下划线；空名回退 "agent" */
fun slugify(name: String): String =
    name.lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .ifEmpty { "agent" }

/** slug 冲突时追加短 id 后缀，保证工具名唯一（如 subagent_code_reviewer_a1b2） */
fun uniqueToolName(slug: String, used: Set<String>, id: Uuid): String {
    if (slug !in used) return slug
    val suffix = id.toString().takeLast(4)
    var candidate = "${slug}_$suffix"
    var i = 1
    while (candidate in used) {
        candidate = "${slug}_${suffix}$i"
        i++
    }
    return candidate
}

/**
 * 类别 -> 精确工具名白名单。刻意用精确名单而非前缀匹配：
 * 不在表内的工具（搜索/MCP/export/mount/bg 等）一律不暴露给 subagent。
 * ask_user 永远排除：子循环内等待用户交互会挂起/死锁。
 */
val CATEGORY_TOOLS: Map<SubAgentToolCategory, Set<String>> = mapOf(
    SubAgentToolCategory.READ to setOf(
        "read",
        "get_time_info",
    ),
    SubAgentToolCategory.WRITE to setOf(
        "write",
        "edit",
        "todowrite",
    ),
    SubAgentToolCategory.SHELL to setOf(
        "bash",
    ),
)

fun matchesToolAllowlist(toolName: String, allowlist: Set<SubAgentToolCategory>): Boolean {
    if (toolName == "ask_user") return false
    return allowlist.any { category -> toolName in CATEGORY_TOOLS[category].orEmpty() }
}

private val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)

/**
 * 过滤主 Agent 对话记录中的 think 过程：
 * 1) 丢弃 UIMessagePart.Reasoning part；
 * 2) 剥离 Text part 中残留的 <think>...</think> 片段（ThinkTagTransformer 同款正则）。
 */
fun stripReasoning(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
    message.copy(
        parts = message.parts.flatMap { part ->
            when (part) {
                is UIMessagePart.Reasoning -> emptyList()
                is UIMessagePart.Text -> {
                    if (THINKING_REGEX.containsMatchIn(part.text)) {
                        listOf(part.copy(text = part.text.replace(THINKING_REGEX, "")))
                    } else {
                        listOf(part)
                    }
                }
                else -> listOf(part)
            }
        }
    )
}

/** subagent 运行结果 JSON（返回给主 Agent 的 tool output）；runId 供监看面板跳转对应轨迹 */
fun buildSubAgentResultJson(
    status: String,
    result: String,
    steps: Int,
    usage: TokenUsage?,
    runId: String? = null,
): String = buildJsonObject {
    put("status", status)
    put("result", result)
    put("steps", steps)
    if (usage != null) {
        put("usage", buildJsonObject {
            put("promptTokens", usage.promptTokens)
            put("completionTokens", usage.completionTokens)
            put("totalTokens", usage.totalTokens)
        })
    }
    if (runId != null) {
        put("runId", runId)
    }
}.toString()
