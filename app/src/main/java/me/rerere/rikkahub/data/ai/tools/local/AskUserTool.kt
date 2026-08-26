package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

internal const val ASK_USER_TOOL_NAME = "ask_user"

/** 单选/多选选项（对齐 opencode QuestionV2.Option：label + description） */
data class AskUserOption(
    val label: String,
    val description: String = "",
)

/** 一道问题的展示模型：交互表单、专属渲染器与答案格式化共用 */
data class AskUserQuestion(
    val id: String,
    val question: String,
    /** 简短标签（≤30 字符），显示在问题上方 */
    val header: String = "",
    val options: List<AskUserOption> = emptyList(),
    /** 允许多选（答案以 label 数组返回） */
    val multiple: Boolean = false,
    /** 允许手打自定义答案（默认开，UI 自动提供输入框） */
    val custom: Boolean = true,
)

/**
 * 解析 ask_user 入参的 questions 数组。
 *
 * 新格式（第二批起，对齐 opencode）：{id, question, header, options:[{label, description}], multiple, custom}；
 * 兼容旧格式：options 为纯字符串数组、selection_type=text/single/multi 自动映射
 * （text→custom 开、single→仅选项、multi→多选仅选项），历史消息仍可正常渲染。
 */
internal fun parseAskUserQuestions(arguments: JsonElement): List<AskUserQuestion> =
    runCatching {
        arguments.jsonObject["questions"]?.jsonArray.orEmpty().map { q ->
            val obj = q.jsonObject
            val legacyType = obj["selection_type"]?.jsonPrimitive?.contentOrNull
            val multiple = obj["multiple"]?.jsonPrimitive?.booleanOrNull ?: (legacyType == "multi")
            val custom = obj["custom"]?.jsonPrimitive?.booleanOrNull
                ?: when (legacyType) {
                    "single", "multi" -> false // 旧版这两类无手打框
                    else -> true
                }
            AskUserQuestion(
                id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                question = obj["question"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                header = obj["header"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                options = obj["options"]?.jsonArray.orEmpty().mapNotNull { el ->
                    when (el) {
                        is JsonObject -> {
                            val label = el["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (label.isBlank()) null
                            else AskUserOption(
                                label = label,
                                description = el["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            )
                        }
                        // 旧版选项是纯字符串
                        is JsonPrimitive -> el.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?.let { AskUserOption(it) }
                        else -> null
                    }
                },
                multiple = multiple,
                custom = custom,
            )
        }
    }.getOrDefault(emptyList())

/**
 * 把 HITL 应答负载（{"answers":{id:字符串|[label,...]}}）连同入参中的题目原文格式化为模型友好的自然语言，
 * 对齐 opencode question 工具的输出风格（"Q"="A" 句式 + 续跑指令）；多选答案按 ", " 连接；
 * 任何解析失败都原样返回应答文本兜底。
 */
internal fun formatAskUserAnswer(arguments: String, answer: String): String {
    val formatted = runCatching {
        val answers = Json.parseToJsonElement(answer).jsonObject["answers"]?.jsonObject
            ?: error("answers object missing")
        val questions = Json.parseToJsonElement(arguments.ifBlank { "{}" })
            .jsonObject["questions"]?.jsonArray.orEmpty()
        questions.joinToString("; ") { q ->
            val obj = q.jsonObject
            val text = obj["question"]?.jsonPrimitive?.contentOrNull ?: ""
            val value = obj["id"]?.jsonPrimitive?.contentOrNull
                ?.let { id -> answers[id]?.toAnswerText() } ?: "Unanswered"
            "\"$text\"=\"$value\""
        }
    }.getOrNull()
    return if (formatted.isNullOrBlank()) answer
    else "User has answered your questions: $formatted. Continue with the user's answers in mind."
}

/** 答案值转显示文本：字符串原样，数组（多选）按 ", " 连接 */
private fun JsonElement.toAnswerText(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: ""
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
    else -> ""
}

internal fun buildAskUserTool(): Tool = Tool(
    name = ASK_USER_TOOL_NAME,
    // 第二批起对齐 opencode question.txt：
    // custom 默认开（UI 自动提供「手打答案」输入框），因此采纳其「别加 Other 兜底项」建议；
    // header/选项说明/推荐项置首约定一并吸收。id 为本地兼容保留（应答按 id 键控返回）。
    description = """
        Use this tool when you need to ask the user questions during execution. This allows you to:
        1. Gather user preferences or requirements
        2. Clarify ambiguous instructions or underspecified tasks
        3. Get decisions on implementation choices as you work
        4. Offer choices about what direction to take
        Usage notes:
        - Give every question a short stable id; answers are returned keyed by id
        - When custom is enabled (default true), a "Type your own answer" input is added automatically; don't include "Other" or catch-all options
        - Answers are returned as arrays of labels when multiple is true; set multiple: true to allow selecting more than one
        - If you recommend a specific option, make that the first option in the list and add "(Recommended)" at the end of the label
        - Keep option labels short (1-5 words); header is a very short label (max 30 chars) shown above the question
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "List of questions to ask the user")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question; answers are returned keyed by id")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "Complete question")
                            })
                            put("header", buildJsonObject {
                                put("type", "string")
                                put("description", "Very short label (max 30 chars)")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put("description", "Available choices")
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("label", buildJsonObject {
                                            put("type", "string")
                                            put("description", "Display text (1-5 words, concise)")
                                        })
                                        put("description", buildJsonObject {
                                            put("type", "string")
                                            put("description", "Explanation of choice")
                                        })
                                    })
                                    put("required", buildJsonArray {
                                        add(JsonPrimitive("label"))
                                        add(JsonPrimitive("description"))
                                    })
                                })
                            })
                            put("multiple", buildJsonObject {
                                put("type", "boolean")
                                put("description", "Allow selecting multiple choices")
                            })
                            put("custom", buildJsonObject {
                                put("type", "boolean")
                                put("description", "Allow typing a custom answer (default: true)")
                            })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("id"))
                            add(JsonPrimitive("question"))
                        })
                    })
                })
            },
            required = listOf("questions"),
        )
    },
    needsApproval = { true },
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)