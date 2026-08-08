package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.SubAgentRunner
import me.rerere.rikkahub.data.ai.slugify
import me.rerere.rikkahub.data.ai.uniqueToolName
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.SubAgent
import kotlin.uuid.Uuid

/**
 * 每个启用的 subagent 生成一个独立工具（Claude Code 式：主 Agent 同时看到多个"专家"）。
 * 工具内部运行 SubAgentRunner 嵌套循环；catalog = 主工具池剔除 subagent 工具后传入
 * （v1 禁止嵌套：subagent 看不到其他 subagent 工具）。
 */
fun createSubAgentTools(
    subAgents: List<SubAgent>,
    assistant: Assistant,
    settings: Settings,
    memories: List<AssistantMemory>?,
    conversationSystemPrompt: String?,
    conversationHistory: List<UIMessage>,
    toolCatalog: List<Tool>,
    allSkills: List<SkillMetadata>,
    subAgentRunner: SubAgentRunner,
): List<Tool> {
    if (subAgents.isEmpty()) return emptyList()
    val usedSlugs = mutableSetOf<String>()
    return subAgents.map { subAgent ->
        val slug = uniqueToolName(slugify(subAgent.name), usedSlugs, subAgent.id)
        usedSlugs += slug
        Tool(
            name = "subagent_$slug",
            description = buildString {
                append(subAgent.description.ifBlank { "Run the subagent '${subAgent.name}'." })
                append(" Run it with a clear task. ")
                append("Timeout ${subAgent.timeoutMs / 1000}s, max ${subAgent.maxSteps} steps; returns a JSON result.")
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("task", buildJsonObject {
                            put("type", "string")
                            put("description", "The task to delegate to this subagent")
                        })
                        put("context", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional necessary background for the task (the subagent already sees the conversation history)"
                            )
                        })
                    },
                    required = listOf("task"),
                )
            },
            needsApproval = { subAgent.requiresApproval },
            execute = { input ->
                val params = input.jsonObject
                val task = params["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (task.isEmpty()) error("task is required")
                val context = params["context"]?.jsonPrimitive?.contentOrNull?.trim()
                val result = subAgentRunner.run(
                    subAgent = subAgent,
                    assistant = assistant,
                    settings = settings,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationHistory = conversationHistory,
                    task = task,
                    context = context,
                    memories = memories,
                    toolCatalog = toolCatalog,
                    allSkills = allSkills,
                )
                listOf(UIMessagePart.Text(result))
            },
        )
    }
}
