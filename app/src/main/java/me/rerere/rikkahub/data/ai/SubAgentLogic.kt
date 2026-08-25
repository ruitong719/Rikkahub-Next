package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.SubAgentToolCategory
import kotlin.uuid.Uuid

/**
 * Subagent 纯逻辑函数：与 Android/生成循环无关，可独立 JVM 单测。
 */

/** 每个对话内同时运行的 subagent 实例上限（所有类型合并计数） */
const val SUBAGENT_CONCURRENCY_LIMIT_PER_CONVERSATION = 5

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
