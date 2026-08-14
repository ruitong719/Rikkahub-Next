package me.rerere.ai.reasoning

import me.rerere.ai.core.ReasoningLevel

/**
 * 思考深度 -> 供应商 effort 值的集中映射表。
 *
 * 原先按 provider/host 与 modelId 分散硬编码在 4 个 provider 文件里
 * （ChatCompletionsAPI / ResponseAPI / ClaudeProvider / GoogleProvider），
 * 这里统一收敛成表：按「作用域（供应商/接入点）+ 模型 id」两级匹配，
 * 未命中的一律回退到 [DEFAULT] 托底。
 *
 * 约定：
 * - [DEFAULT] 与 `ReasoningLevel.effort` 一致（none/auto/low/medium/high/xhigh）；
 * - [SCOPE_DEFAULTS] 按供应商作用域修正默认值（如 OpenAI completions 不接受 "none"）；
 * - [MODEL_OVERRIDES] 按模型 id（大小写不敏感的包含匹配）定向覆盖，优先级最高。
 */
object ReasoningEffortMappings {

    /** 全局默认托底 */
    val DEFAULT: Map<ReasoningLevel, String> = mapOf(
        ReasoningLevel.OFF to "none",
        ReasoningLevel.AUTO to "auto",
        ReasoningLevel.LOW to "low",
        ReasoningLevel.MEDIUM to "medium",
        ReasoningLevel.HIGH to "high",
        ReasoningLevel.XHIGH to "xhigh",
    )

    /** 按供应商作用域的默认修正（对 [DEFAULT] 的覆盖） */
    private val SCOPE_DEFAULTS: Map<String, Map<ReasoningLevel, String>> = mapOf(
        // OpenAI chat completions 只接受 low/medium/high，OFF 压成 low
        "openai_chat" to mapOf(
            ReasoningLevel.OFF to "low",
        ),
        // NVIDIA（非 deepseek-v4 模型）与 OpenAI 语义一致
        "nvidia" to mapOf(
            ReasoningLevel.OFF to "low",
        ),
        // Gemini 3 系列 thinkingLevel 只接受 low/medium/high，HIGH/XHIGH 收敛为 high
        "gemini3" to mapOf(
            ReasoningLevel.LOW to "low",
            ReasoningLevel.MEDIUM to "medium",
            ReasoningLevel.HIGH to "high",
            ReasoningLevel.XHIGH to "high",
        ),
        // Claude / OpenAI responses / 其余作用域：沿用默认（OFF=none、XHIGH=xhigh）
    )

    /** 按模型 id（大小写不敏感的包含匹配）的定向覆盖，优先级最高 */
    private val MODEL_OVERRIDES: List<Pair<String, Map<ReasoningLevel, String>>> = listOf(
        // deepseek-v4 系列（NVIDIA 与官方均适用）：effort 语义为 none/high/max
        "deepseek-v4" to mapOf(
            ReasoningLevel.OFF to "none",
            ReasoningLevel.LOW to "high",
            ReasoningLevel.MEDIUM to "high",
            ReasoningLevel.HIGH to "high",
            ReasoningLevel.XHIGH to "max",
        ),
        // 需要为具体模型单独指定映射时在此追加，例如：
        // "kimi-k3" to mapOf(ReasoningLevel.XHIGH to "max"),
    )

    /**
     * 解析某个思考深度应发送的 effort 值。
     *
     * 优先级：用户自定义映射 [userMapping]（Model.reasoningEffortMap，仅配置了的等级生效）
     * > 模型 id 定向覆盖 > 供应商作用域默认 > [DEFAULT] 托底。
     *
     * @param scope 供应商作用域（openai_chat / openai_responses / nvidia / claude / gemini3 / ...），
     *              为 null 或未登记时直接用 [DEFAULT]
     * @param modelId 当前模型 id，命中 [MODEL_OVERRIDES] 时优先于作用域默认
     * @param level 思考深度
     * @param userMapping 用户在模型页配置的等级 -> 发送值映射；配置了的等级以用户值为准
     */
    fun resolveEffort(
        scope: String?,
        modelId: String,
        level: ReasoningLevel,
        userMapping: Map<ReasoningLevel, String> = emptyMap(),
    ): String {
        userMapping[level]?.let { return it }

        MODEL_OVERRIDES.firstOrNull { (pattern, _) ->
            modelId.contains(pattern, ignoreCase = true)
        }?.second?.get(level)?.let { return it }

        return (scope?.let { SCOPE_DEFAULTS[it] } ?: DEFAULT)[level] ?: DEFAULT.getValue(level)
    }
}
