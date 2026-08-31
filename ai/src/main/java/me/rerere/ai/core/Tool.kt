package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    /**
     * 同批多个工具调用是否可并发执行（无顺序依赖、互不干扰）。
     * 仅 subagent 发派类工具置 true：GenerationHandler 对含并行工具的批次先顺序执行
     * 普通工具，再并发跑这批并按原序写回结果。
     */
    val parallelSafe: Boolean = false,
    /**
     * 终局工具：执行完成后立即结束生成循环（不再发起下一轮 LLM 请求）。
     * 用于 subagent 的 submit_report 等一次性收尾工具。
     */
    val terminal: Boolean = false,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
