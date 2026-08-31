package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

private val todoJson = Json { encodeDefaults = true; explicitNulls = false }

/** 单个 todo 条目的参数校验与解析，非法值直接报错让模型自纠 */
private fun parseTodoElement(element: kotlinx.serialization.json.JsonElement): TodoItem {
    val obj = element.jsonObject
    val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
    require(!content.isNullOrBlank()) { "each todo requires a non-empty 'content'" }

    val statusRaw = obj["status"]?.jsonPrimitive?.contentOrNull
    val status = statusRaw?.let {
        runCatching { TodoStatus.valueOf(it.trim().uppercase()) }.getOrElse {
            error("invalid status '$statusRaw', expected one of: pending, in_progress, completed, cancelled")
        }
    } ?: TodoStatus.PENDING

    val priorityRaw = obj["priority"]?.jsonPrimitive?.contentOrNull
    val priority = priorityRaw?.let {
        runCatching { TodoPriority.valueOf(it.trim().uppercase()) }.getOrElse {
            error("invalid priority '$priorityRaw', expected one of: high, medium, low")
        }
    } ?: TodoPriority.MEDIUM

    return TodoItem(content = content, status = status, priority = priority)
}

/** todo 工具名（底栏 todo 图标按需显示等 UI 判断共用） */
const val TODO_TOOL_NAME = "todowrite"

/**
 * todowrite：对齐 opencode 的单工具全量替换模型。
 * 模型每次提交完整列表覆盖当前对话的 todo；返回写入后的全量列表，
 * 因此模型无需读取工具也能随时掌握现状（上下文压缩后依然自洽）。
 * PLAN 模式刻意不拦截本工具：模型可以先把执行计划写成 todo 清单。
 */
internal fun buildTodoWriteTool(store: TodoStore, conversationId: Uuid): Tool = Tool(
    name = TODO_TOOL_NAME,
    description = """
        Create and maintain a structured task list for the current conversation. Tracks progress, organizes multi-step work, and surfaces status to the user.
        Pass the FULL updated list every call - it replaces the previous list entirely (items are identified by position, not id).
        Usage - use proactively when:
        - The task requires 3+ distinct steps or actions (not just 3 tool calls for a single conceptual step)
        - The work is non-trivial and benefits from planning
        - The user provides multiple tasks (numbered or comma-separated) or explicitly asks for a todo list
        - New instructions arrive - capture them as todos
        - You start a task - mark it in_progress (only one at a time) before working
        - You finish a task - mark it completed and add any follow-ups discovered during the work
        Usage - skip when:
        - The work is a single, straightforward task (or <3 trivial steps)
        - The request is purely informational or conversational
        - Tracking adds no organizational value
        States:
        - pending: not started
        - in_progress: actively working (exactly ONE at a time)
        - completed: finished successfully
        - cancelled: no longer needed
        Rules:
        - Update status in real time; don't batch completions
        - Mark completed only after the required work is actually done, including any required verification. Never based on intent.
        - Keep exactly one in_progress while work remains
        - If blocked or partial, keep it in_progress and add a follow-up todo describing the blocker
        - Preserve user-provided commands verbatim (flags, args, order)
        - Items should be specific and actionable; break large work into smaller steps
        When in doubt, use it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("todos", buildJsonObject {
                    put("type", "array")
                    put("description", "The updated todo list (full replacement)")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "Brief description of the task")
                            })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("description", "pending, in_progress, completed or cancelled. Defaults to pending.")
                            })
                            put("priority", buildJsonObject {
                                put("type", "string")
                                put("description", "high, medium or low. Defaults to medium.")
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("content")) })
                    })
                })
            },
            required = listOf("todos"),
        )
    },
    execute = {
        val params = it.jsonObject
        val todos = params["todos"]?.jsonArray
            ?: error("todos is required")
        require(todos.size <= TODO_WRITE_MAX_ITEMS) {
            "too many todos (${todos.size}), max $TODO_WRITE_MAX_ITEMS"
        }
        val items = todos.map(::parseTodoElement)
        store.replaceAll(conversationId, items)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "ok")
                    put("activeCount", items.count { it.status == TodoStatus.PENDING || it.status == TodoStatus.IN_PROGRESS })
                    put("todos", todoJson.parseToJsonElement(todoJson.encodeToString(items)))
                }.toString()
            )
        )
    },
)

private const val TODO_WRITE_MAX_ITEMS = 200
