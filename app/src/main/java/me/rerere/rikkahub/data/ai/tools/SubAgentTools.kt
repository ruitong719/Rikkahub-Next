package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.SUBAGENT_CONCURRENCY_LIMIT_PER_CONVERSATION
import me.rerere.rikkahub.data.ai.SubAgentRunner
import me.rerere.rikkahub.data.ai.slugify
import me.rerere.rikkahub.data.ai.uniqueToolName
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolCategory
import me.rerere.rikkahub.data.model.isGeneralSubagent
import kotlin.uuid.Uuid

/**
 * 每个启用的 subagent 生成一个独立工具（Claude Code 式：主 Agent 同时看到多个"专家"）。
 *
 * - 预设 subagent：task + context，工具面来自定义里的类别白名单；
 * - 内置 General：可并行发派多个实例，每次调用由主模型通过 tools 参数指定该实例的
 *   可用类别（read/write/shell/skill），label 用于在监看面板区分实例。
 *
 * catalog = 主工具池剔除 subagent 工具后传入（v1 不做嵌套发派）；
 * 所有 subagent 工具标记 parallelSafe，同批多个发派可并发执行。
 */
fun createSubAgentTools(
    subAgents: List<SubAgent>,
    assistant: Assistant,
    settings: Settings,
    conversationSystemPrompt: String?,
    conversationHistory: List<UIMessage>,
    toolCatalog: List<Tool>,
    allSkills: List<SkillMetadata>,
    subAgentRunner: SubAgentRunner,
    conversationId: Uuid? = null,
): List<Tool> {
    if (subAgents.isEmpty()) return emptyList()
    val usedSlugs = mutableSetOf<String>("general")
    return subAgents.map { subAgent ->
        val slug = if (isGeneralSubagent(subAgent.id)) {
            "general"
        } else {
            uniqueToolName(slugify(subAgent.name), usedSlugs, subAgent.id).also { usedSlugs += it }
        }
        val baseContext = SubAgentInvokeContext(
            subAgent = subAgent,
            assistant = assistant,
            settings = settings,
            conversationSystemPrompt = conversationSystemPrompt,
            conversationHistory = conversationHistory,
            toolCatalog = toolCatalog,
            allSkills = allSkills,
            subAgentRunner = subAgentRunner,
            conversationId = conversationId,
        )
        if (isGeneralSubagent(subAgent.id)) {
            buildGeneralSubAgentTool(baseContext)
        } else {
            buildPresetSubAgentTool(slug, baseContext)
        }
    }
}

/** 构建工具所需的上下文快照（工具执行时使用创建时的会话状态） */
private data class SubAgentInvokeContext(
    val subAgent: SubAgent,
    val assistant: Assistant,
    val settings: Settings,
    val conversationSystemPrompt: String?,
    val conversationHistory: List<UIMessage>,
    val toolCatalog: List<Tool>,
    val allSkills: List<SkillMetadata>,
    val subAgentRunner: SubAgentRunner,
    val conversationId: Uuid?,
)

private fun buildPresetSubAgentTool(
    slug: String,
    ctx: SubAgentInvokeContext,
): Tool {
    val subAgent = ctx.subAgent
    return Tool(
        name = "subagent_$slug",
        description = buildString {
            append(subAgent.description.ifBlank { "Run the subagent '${subAgent.name}'." })
            append(" Run it with a clear task. ")
            append(
                "Timeout ${subAgent.timeoutMs / 1000}s, max ${subAgent.maxSteps} steps;" +
                    " at most ${SUBAGENT_CONCURRENCY_LIMIT_PER_CONVERSATION} subagents run concurrently per conversation." +
                    " Returns a JSON result."
            )
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
        parallelSafe = true,
        execute = { input ->
            val params = input.jsonObject
            val task = params["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (task.isEmpty()) error("task is required")
            val contextText = params["context"]?.jsonPrimitive?.contentOrNull?.trim()
            val result = ctx.subAgentRunner.run(
                subAgent = subAgent,
                assistant = ctx.assistant,
                settings = ctx.settings,
                conversationSystemPrompt = ctx.conversationSystemPrompt,
                conversationHistory = ctx.conversationHistory,
                task = task,
                context = contextText,
                toolCatalog = ctx.toolCatalog,
                allSkills = ctx.allSkills,
                conversationId = ctx.conversationId,
            )
            listOf(UIMessagePart.Text(result))
        },
    )
}

private fun buildGeneralSubAgentTool(ctx: SubAgentInvokeContext): Tool {
    val subAgent = ctx.subAgent
    val categoriesHint = "Allowed values: read (read, get_time_info)," +
        " write (write, edit, todo tools)," +
        " shell (bash), skill (the subagent's configured skills)."
    return Tool(
        name = "subagent_general",
        description = buildString {
            append(subAgent.description.ifBlank {
                "Free-form general-purpose subagent for ad-hoc tasks not covered by preset subagents."
            })
            append(" You choose which tool categories each instance may use via 'tools'.")
            append(" Multiple instances may be launched in parallel (at most $SUBAGENT_CONCURRENCY_LIMIT_PER_CONVERSATION run concurrently per conversation);")
            append(" pass a short 'label' to tell concurrent instances apart.")
            append(" Timeout ${subAgent.timeoutMs / 1000}s, max ${subAgent.maxSteps} steps; returns a JSON result.")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task", buildJsonObject {
                        put("type", "string")
                        put("description", "The task to delegate to this instance")
                    })
                    put("context", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional necessary background for the task (the instance already sees the conversation history)"
                        )
                    })
                    put("tools", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("read")
                                add("write")
                                add("shell")
                                add("skill")
                            }
                        })
                        put("description", "Tool categories this instance may use. $categoriesHint")
                    })
                    put("label", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Short (3-5 words) label for this instance, shown in the monitor to distinguish concurrent instances"
                        )
                    })
                },
                required = listOf("task", "tools"),
            )
        },
        needsApproval = { subAgent.requiresApproval },
        parallelSafe = true,
        execute = { input ->
            val params = input.jsonObject
            val task = params["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (task.isEmpty()) error("task is required")
            val requested = params["tools"]
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?: error("tools is required")
            val categories = buildSet {
                requested.forEach { element ->
                    when (element.jsonPrimitive.contentOrNull?.lowercase()) {
                        "read" -> add(SubAgentToolCategory.READ)
                        "write" -> add(SubAgentToolCategory.WRITE)
                        "shell" -> add(SubAgentToolCategory.SHELL)
                        "skill" -> Unit // 仅开启 skill 池，不加入工作区类别
                    }
                }
            }
            val allowSkills = requested.any { it.jsonPrimitive.contentOrNull?.lowercase() == "skill" }
            if (categories.isEmpty() && !allowSkills) {
                error("tools must contain at least one of: read, write, shell, skill")
            }
            val label = params["label"]?.jsonPrimitive?.contentOrNull?.trim()?.take(50)
            val effectiveSubAgent = if (allowSkills) {
                subAgent
            } else {
                // 未勾选 skill 类别时不暴露 use_skill 工具
                subAgent.copy(enabledSkills = emptySet())
            }
            val contextText = params["context"]?.jsonPrimitive?.contentOrNull?.trim()
            val result = ctx.subAgentRunner.run(
                subAgent = effectiveSubAgent,
                assistant = ctx.assistant,
                settings = ctx.settings,
                conversationSystemPrompt = ctx.conversationSystemPrompt,
                conversationHistory = ctx.conversationHistory,
                task = task,
                context = contextText,
                toolCatalog = ctx.toolCatalog,
                allSkills = ctx.allSkills,
                conversationId = ctx.conversationId,
                label = label,
                allowlistOverride = categories,
            )
            listOf(UIMessagePart.Text(result))
        },
    )
}

