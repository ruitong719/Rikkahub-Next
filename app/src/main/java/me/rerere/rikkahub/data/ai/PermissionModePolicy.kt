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
    /** PLAN 模式下禁用（拒绝执行）的工具名。工具改名后需同步维护 */
    private val PLAN_DENIED_TOOLS = setOf(
        "write",
        "edit",
        "bash",
        "bgt_start",
        "bgt",
        "create_backup",
    )

    fun apply(tools: List<Tool>, mode: PermissionMode): List<Tool> = when (mode) {
        PermissionMode.BUILD, PermissionMode.GOAL -> tools

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
                if (tool.name in PLAN_DENIED_TOOLS) {
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
}
