package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.util.InstantSerializer
import java.time.Instant

/**
 * GOAL 模式的状态（会话级，持久化在 ConversationEntity.goal）。
 *
 * 与 PermissionMode.GOAL 正交：模式决定「是否在目标循环里」，本对象保存目标本身。
 *
 * 生命周期：
 * - `/goal <描述>` → 建 GoalState(status=ACTIVE, request=描述, condition 为空)；
 * - 主模型首轮必须调用 set_goal 把目标规范化写入 [condition]；
 * - 评估器每轮产出 [GoalVerdict] 追加到 [history]；
 * - 达成 / 不可能 / 用户停止 → 终态，会话自动回到 BUILD，但本对象保留可回看。
 */
@Serializable
data class GoalState(
    /** 用户 `/goal` 后的原始描述（未规范化，仅展示用） */
    val request: String = "",
    /** 主模型通过 set_goal 规范化后的目标；为空表示尚未设定 */
    val condition: String = "",
    val status: GoalStatus = GoalStatus.ACTIVE,
    /** 已评估（含自动续跑）的轮数 */
    val turnCount: Int = 0,
    /** 开始时的 token 基线，用于展示本目标期间的花费 */
    val tokenBaseline: Long? = null,
    /** 连续「主模型无工具调用」的轮数，用于无进展熔断 */
    val noProgressStreak: Int = 0,
    /** 按时间顺序的评估判决历史 */
    val history: List<GoalVerdict> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
) {
    val hasCondition: Boolean get() = condition.isNotBlank()
}

/** 目标终态 / 进行中 */
@Serializable
enum class GoalStatus {
    ACTIVE,
    ACHIEVED,
    IMPOSSIBLE,
    STOPPED,
}

/** 评估器单轮判决种类 */
@Serializable
enum class GoalVerdictKind {
    NOT_MET,
    ACHIEVED,
    IMPOSSIBLE,
}

/** 一次评估判决（评估器经 submit_goal_review 交卷后落库） */
@Serializable
data class GoalVerdict(
    val kind: GoalVerdictKind,
    val reason: String = "",
    /** 产生该判决时的轮数 */
    val atTurn: Int = 0,
    @Serializable(with = InstantSerializer::class)
    val at: Instant = Instant.now(),
)
