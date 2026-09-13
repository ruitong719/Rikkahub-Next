package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.GoalState
import me.rerere.rikkahub.data.model.GoalStatus

/** GOAL 模式工具名 */
const val SET_GOAL_TOOL_NAME = "set_goal"
const val GET_GOAL_TOOL_NAME = "get_goal"

/**
 * GOAL 模式的两个工具：
 * - set_goal：主模型首轮把用户的目标规范化写入会话（未调用前变更类工具被拦截）；
 * - get_goal：读取当前目标条件、状态与最近一次评估判决（评估器交卷后由 system-reminder 引导调用）。
 */
internal fun createGoalTools(
    goalProvider: () -> GoalState?,
    onSetGoal: suspend (String) -> Unit,
): List<Tool> = listOf(
    Tool(
        name = SET_GOAL_TOOL_NAME,
        description = """
            Define or refine the goal of this conversation. You MUST call this exactly once before using any
            mutating tool (write/edit/bash/background tasks/backup); until then those tools are rejected.
            Write the goal as a concrete, self-contained condition describing what "done" looks like.
            Keep it short (a few sentences). If the user's request is vague, state your best interpretation.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("condition", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "The refined goal / completion condition (what the finished result must be).",
                        )
                    })
                },
                required = listOf("condition"),
            )
        },
        execute = { args ->
            val condition = args.jsonObject["condition"]
                ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(condition.isNotBlank()) { "condition is required and must not be blank" }
            onSetGoal(condition)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "ok")
                        put("condition", condition)
                        put("message", "Goal set. You may now use mutating tools. Work toward this goal.")
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = GET_GOAL_TOOL_NAME,
        description = """
            Read the current goal, its status, and the latest evaluation verdict. Call this after the
            evaluation system reminds you that a review is ready, to see the reason and adjust your work.
        """.trimIndent(),
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            listOf(UIMessagePart.Text(renderGoal(goalProvider())))
        },
    ),
)

private fun renderGoal(goal: GoalState?): String {
    if (goal == null) {
        return buildJsonObject { put("status", "none"); put("message", "No goal is set for this conversation.") }.toString()
    }
    return buildJsonObject {
        put("request", goal.request)
        put("condition", goal.condition)
        put("goal_status", goal.status.name.lowercase())
        put("turns_evaluated", goal.turnCount)
        put("condition_set", goal.hasCondition)
        val latest = goal.history.lastOrNull()
        if (latest == null) {
            put("last_verdict", "none")
        } else {
            put("last_verdict", latest.kind.name.lowercase())
            put("last_reason", latest.reason)
            put("last_verdict_turn", latest.atTurn)
        }
        put("evaluated_turns", goal.history.size)
        put("history", buildJsonArray {
            goal.history.forEach { v ->
                add(buildJsonObject {
                    put("turn", v.atTurn)
                    put("verdict", v.kind.name.lowercase())
                    put("reason", v.reason)
                })
            }
        })
    }.toString()
}

/** 目标终态判定（供主循环决定是否结束目标循环） */
internal fun GoalStatus.isTerminal(): Boolean = this != GoalStatus.ACTIVE
