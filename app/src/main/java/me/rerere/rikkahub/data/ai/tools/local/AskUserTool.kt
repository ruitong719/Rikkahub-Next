package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
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

internal fun buildAskUserTool(): Tool = Tool(
    name = ASK_USER_TOOL_NAME,
    // 使用注意按本 App 实际交互如实描述：single/multi 无手打框（与 opencode 的 custom 恒开相反），
    // 因此需要给「退出项」建议而非「别加 Other」建议；其余吸收 opencode 的用途清单与推荐项约定
    description = """
        Use this tool when you need to ask the user questions during execution. This allows you to:
        1. Gather user preferences or requirements
        2. Clarify ambiguous instructions or underspecified tasks
        3. Get decisions on implementation choices as you work
        4. Offer choices about what direction to take
        Usage notes:
        - Answers are returned keyed by each question's id; give every question a short stable id
        - selection_type=text (default): free-text input; options are shown as tappable suggestions if provided
        - selection_type=single/multi: the user can ONLY pick from the options (no free input), so include an explicit opt-out option when refusing or skipping is reasonable
        - Keep option labels short (1-5 words); put the recommended option first and append "(Recommended)" to its label
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
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional list of suggested options for the user to choose from"
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)

/**
 * 把 HITL 应答负载（{"answers":{id:text}}）连同入参中的题目原文格式化为模型友好的自然语言，
 * 对齐 opencode question 工具的输出风格（"Q"="A" 句式 + 续跑指令）；
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
                ?.let { answers[it]?.jsonPrimitive?.contentOrNull } ?: "Unanswered"
            "\"$text\"=\"$value\""
        }
    }.getOrNull()
    return if (formatted.isNullOrBlank()) answer
    else "User has answered your questions: $formatted. Continue with the user's answers in mind."
}
