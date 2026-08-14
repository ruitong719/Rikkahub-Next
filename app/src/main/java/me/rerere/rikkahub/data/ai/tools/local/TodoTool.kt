package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

private val todoJson = Json { encodeDefaults = true }

private fun todoElement(todo: TodoItem): JsonElement =
    todoJson.parseToJsonElement(todoJson.encodeToString(TodoItem.serializer(), todo))

private fun stringParam(obj: kotlinx.serialization.json.JsonObject, key: String): String? =
    obj[key]?.jsonPrimitive?.contentOrNull?.trim()

private fun okTodoResult(action: String, todo: TodoItem): String = buildJsonObject {
    put("status", "ok")
    put("action", action)
    put("todo", todoElement(todo))
}.toString()

private fun errorResult(message: String): String = buildJsonObject {
    put("status", "error")
    put("message", message)
}.toString()

internal fun buildTodoCreateTool(store: TodoStore, conversationId: Uuid): Tool = Tool(
    name = "todo_create",
    description = """
        Create a todo item scoped to the current conversation (other conversations do not see it).
        Returns the created todo with its 12-character hex id.
        Use todo_update to edit it and todo_complete to mark it done.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Short task title (required)")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional details about the task")
                })
            },
            required = listOf("title"),
        )
    },
    execute = {
        val params = it.jsonObject
        val title = stringParam(params, "title").orEmpty()
        if (title.isEmpty()) error("title is required")
        val description = stringParam(params, "description").orEmpty()
        val todo = store.create(conversationId, title, description)
        listOf(UIMessagePart.Text(okTodoResult("create", todo)))
    },
)

internal fun buildTodoUpdateTool(store: TodoStore, conversationId: Uuid): Tool = Tool(
    name = "todo_update",
    description = """
        Edit an existing todo item in the current conversation.
        Provide the id and at least one of title or description.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "12-character hex id of the todo to edit")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "New title")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "New description (empty string clears it)")
                })
            },
            required = listOf("id"),
        )
    },
    execute = {
        val params = it.jsonObject
        val id = stringParam(params, "id").orEmpty()
        if (id.isEmpty()) error("id is required")
        val title = stringParam(params, "title")
        val description = stringParam(params, "description")
        if (title == null && description == null) error("provide at least one of title or description")
        val todo = store.update(conversationId, id, title, description)
        if (todo == null) {
            listOf(UIMessagePart.Text(errorResult("Todo not found for id '$id'")))
        } else {
            listOf(UIMessagePart.Text(okTodoResult("update", todo)))
        }
    },
)

internal fun buildTodoCompleteTool(store: TodoStore, conversationId: Uuid): Tool = Tool(
    name = "todo_complete",
    description = """
        Mark a todo item in the current conversation as completed (or un-completed).
        Only the AI should decide whether a task is done based on the conversation.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "12-character hex id of the todo")
                })
                put("completed", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Default true. Set false to un-complete.")
                })
            },
            required = listOf("id"),
        )
    },
    execute = {
        val params = it.jsonObject
        val id = stringParam(params, "id").orEmpty()
        if (id.isEmpty()) error("id is required")
        val completed =
            params["completed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val todo = store.setCompleted(conversationId, id, completed)
        if (todo == null) {
            listOf(UIMessagePart.Text(errorResult("Todo not found for id '$id'")))
        } else {
            listOf(UIMessagePart.Text(okTodoResult(if (completed) "complete" else "uncomplete", todo)))
        }
    },
)

internal fun buildTodoClearTool(store: TodoStore, conversationId: Uuid): Tool = Tool(
    name = "todo_clear",
    description = """
        Delete the entire todo list of the current conversation (all items, done and pending).
        Use sparingly - this is irreversible. Returns the number of items removed.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    execute = {
        val removed = store.todos(conversationId).value.size
        store.clear(conversationId)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "ok")
                    put("action", "clear")
                    put("removed", removed)
                }.toString()
            )
        )
    },
)
