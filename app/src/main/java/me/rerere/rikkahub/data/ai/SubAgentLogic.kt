package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * Subagent 纯逻辑函数：与 Android/生成循环无关，可独立 JVM 单测。
 */

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

/** 类别标签 -> 工具名前缀映射（plan 功能六 §4）；精确工具名直接命中 */
private val ALLOWLIST_CATEGORY_PREFIXES: Map<String, List<String>> = mapOf(
    "workspace_read" to listOf("workspace_read_file"),
    "workspace_write" to listOf("workspace_write_file", "workspace_edit_file"),
    "workspace_shell" to listOf("workspace_shell"),
    "workspace_other" to listOf(
        "workspace_export_to_phone",
        "workspace_mount_",
        "workspace_bg_",
        "workspace_create_backup",
    ),
    "search" to listOf("search_web", "scrape_web"),
    "mcp" to listOf("mcp__"),
    "local" to listOf(
        "calendar_",
        "clipboard_tool",
        "eval_javascript",
        "get_screen_time",
        "text_to_speech",
        "get_time_info",
        "todo_",
    ),
)

/**
 * 判断工具是否允许 subagent 使用。ask_user 一律排除：
 * 子循环内等待用户交互会挂起/死锁。
 */
fun matchesToolAllowlist(toolName: String, allowlist: Set<String>): Boolean {
    if (toolName == "ask_user") return false
    if (allowlist.isEmpty()) return false
    return allowlist.any { entry ->
        toolName == entry ||
            (ALLOWLIST_CATEGORY_PREFIXES[entry]?.any { toolName.startsWith(it) } == true)
    }
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

/** subagent 运行结果 JSON（返回给主 Agent 的 tool output） */
fun buildSubAgentResultJson(
    status: String,
    result: String,
    steps: Int,
    usage: TokenUsage?,
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
}.toString()
