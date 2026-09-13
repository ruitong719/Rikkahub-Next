package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.ASK_USER_TOOL_NAME
import me.rerere.rikkahub.data.model.PermissionMode

/**
 * 会话级权限模式（opencode 的 plan/build/yolo）对工具集的统一改写。
 *
 * - BUILD: 原样透传，审批行为由各工具自身配置决定
 * - YOLO: 全部工具跳过审批（含 subagent 派发、MCP、bash 等高危项）
 * - PLAN: 变更类工具执行时直接返回只读错误（不弹审批），subagent 整体下线，
 *   引导模型进入「只调研、出计划」的工作方式；配合 PermissionModePromptTransformer 注入的提示词
 */
object PermissionModePolicy {
    /** 变更类工具名：PLAN 下禁用、GOAL 未设定目标前拦截。工具改名后需同步维护 */
    private val MUTATING_TOOLS = setOf(
        "write",
        "edit",
        "bash",
        "bgt_start",
        "bgt",
        "create_backup",
    )

    fun apply(
        tools: List<Tool>,
        mode: PermissionMode,
        /** GOAL 模式下目标是否已由 set_goal 设定；未设定时变更类工具被拦截 */
        isGoalConditionSet: () -> Boolean = { true },
    ): List<Tool> = when (mode) {
        PermissionMode.BUILD -> tools

        // GOAL：跳过所有审批（比 YOLO 更自由：保留 ask_user 交互），
        // 但在 set_goal 之前拦截变更类工具，逼模型先把目标定下来。
        PermissionMode.GOAL -> tools.map { tool ->
            when {
                tool.name == ASK_USER_TOOL_NAME -> tool

                // 变更类工具：执行时再判定目标是否已设定（动态判定），
                // 这样同一轮里先调用 set_goal、再调用 write 也能正常放行。
                tool.name in MUTATING_TOOLS -> {
                    val originalExecute = tool.execute
                    tool.copy(
                        needsApproval = { false },
                        execute = { args ->
                            if (isGoalConditionSet()) originalExecute(args) else goalNotSetDenied(tool.name)
                        },
                    )
                }

                else -> tool.copy(needsApproval = { false })
            }
        }

        PermissionMode.YOLO -> tools.map { tool ->
            when {
                // ask_user 是 HITL 交互工具，YOLO 下没有审批流程可走；
                // 直接放行会掉进 execute 抛错。改为返回结构化提示：
                // 模型收到「不可用」说明，用户界面展示切换模式提示
                tool.name == ASK_USER_TOOL_NAME -> tool.copy(
                    needsApproval = { false },
                    execute = { yoloAskUserDenied() },
                )

                else -> tool.copy(needsApproval = { false })
            }
        }

        PermissionMode.PLAN -> tools
            .filterNot { it.name.startsWith("subagent_") }
            .map { tool ->
                if (tool.name in MUTATING_TOOLS) {
                    tool.copy(
                        needsApproval = { false },
                        execute = { deniedExecution(tool.name) },
                    )
                } else {
                    tool
                }
            }
    }

    private fun deniedExecution(toolName: String): List<UIMessagePart> = listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put(
                    "error",
                    "Tool '$toolName' is unavailable in plan mode (read-only research). " +
                        "Explore and read instead, then present a plan; the user will switch to build mode to execute it."
                )
            }.toString()
        )
    )

    /** YOLO 模式下 ask_user 的替代执行结果：不进交互流程，模型收到不可用说明 */
    private fun yoloAskUserDenied(): List<UIMessagePart> = listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put(
                    "error",
                    "ask_user is unavailable in YOLO mode (tools run without user interaction). " +
                        "Switch the permission mode if you need to ask the user questions."
                )
            }.toString()
        )
    )

    /** GOAL 模式下 set_goal 之前的变更类工具拦截结果 */
    private fun goalNotSetDenied(toolName: String): List<UIMessagePart> = listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put(
                    "error",
                    "调用错误：当前处于 GOAL 模式且目标尚未设定，必须先调用 set_goal 定义目标，" +
                        "之后才能使用变更类工具 '$toolName'。"
                )
            }.toString()
        )
    )
}
