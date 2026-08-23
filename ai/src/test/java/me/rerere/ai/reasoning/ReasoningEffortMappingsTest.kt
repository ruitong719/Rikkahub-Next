package me.rerere.ai.reasoning

import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ReasoningEffortMappings 映射表单测：
 * 覆盖默认托底、供应商作用域修正、模型定向覆盖的优先级。
 */
class ReasoningEffortMappingsTest {

    @Test
    fun defaultMappingPassesThroughEffortValues() {
        assertEquals("none", ReasoningEffortMappings.resolveEffort(null, "unknown-model", ReasoningLevel.OFF))
        assertEquals("auto", ReasoningEffortMappings.resolveEffort(null, "unknown-model", ReasoningLevel.AUTO))
        assertEquals("low", ReasoningEffortMappings.resolveEffort(null, "unknown-model", ReasoningLevel.LOW))
        assertEquals("medium", ReasoningEffortMappings.resolveEffort(null, "unknown-model", ReasoningLevel.MEDIUM))
        assertEquals("high", ReasoningEffortMappings.resolveEffort(null, "unknown-model", ReasoningLevel.HIGH))
        assertEquals("xhigh", ReasoningEffortMappings.resolveEffort(null, "unknown-model", ReasoningLevel.XHIGH))
    }

    @Test
    fun openaiChatFlattensOffToLow() {
        // OpenAI chat completions 不接受 "none"
        assertEquals("low", ReasoningEffortMappings.resolveEffort("openai_chat", "gpt-5", ReasoningLevel.OFF))
        assertEquals("high", ReasoningEffortMappings.resolveEffort("openai_chat", "gpt-5", ReasoningLevel.HIGH))
        assertEquals("xhigh", ReasoningEffortMappings.resolveEffort("openai_chat", "gpt-5", ReasoningLevel.XHIGH))
    }

    @Test
    fun openaiResponsesKeepsDefaultSemantics() {
        // responses API 沿用默认（OFF=none、XHIGH=xhigh）
        assertEquals("none", ReasoningEffortMappings.resolveEffort("openai_responses", "gpt-5.2", ReasoningLevel.OFF))
        assertEquals("xhigh", ReasoningEffortMappings.resolveEffort("openai_responses", "gpt-5.2", ReasoningLevel.XHIGH))
    }

    @Test
    fun nvidiaDeepseekV4UsesModelOverride() {
        // 模型定向覆盖优先：deepseek-v4 系列 XHIGH -> max，中间档收敛为 high
        assertEquals("max", ReasoningEffortMappings.resolveEffort("nvidia", "deepseek-v4-flash", ReasoningLevel.XHIGH))
        assertEquals("none", ReasoningEffortMappings.resolveEffort("nvidia", "DeepSeek-V4-Pro", ReasoningLevel.OFF))
        assertEquals("high", ReasoningEffortMappings.resolveEffort("nvidia", "deepseek-v4", ReasoningLevel.LOW))
        assertEquals("high", ReasoningEffortMappings.resolveEffort("nvidia", "deepseek-v4", ReasoningLevel.MEDIUM))
        assertEquals("high", ReasoningEffortMappings.resolveEffort("nvidia", "deepseek-v4", ReasoningLevel.HIGH))
    }

    @Test
    fun nvidiaOtherModelsFallBackToOpenAiSemantics() {
        // 非 deepseek-v4 的 NVIDIA 模型：OFF -> low（与 OpenAI 一致）
        assertEquals("low", ReasoningEffortMappings.resolveEffort("nvidia", "llama-3.3-70b", ReasoningLevel.OFF))
        assertEquals("medium", ReasoningEffortMappings.resolveEffort("nvidia", "llama-3.3-70b", ReasoningLevel.MEDIUM))
    }

    @Test
    fun opencodeOxAlphaClampsToLowHighMax() {
        // x-preview-f-free（Ox Alpha）chat/completions 只接受 low/high/max，
        // none/auto/medium/xhigh 上游直接 400，必须收敛（实测 2026-08-23）
        assertEquals("low", ReasoningEffortMappings.resolveEffort("opencode", "x-preview-f-free", ReasoningLevel.OFF))
        assertEquals("low", ReasoningEffortMappings.resolveEffort("opencode", "x-preview-f-free", ReasoningLevel.LOW))
        assertEquals("low", ReasoningEffortMappings.resolveEffort("opencode", "x-preview-f-free", ReasoningLevel.MEDIUM))
        assertEquals("high", ReasoningEffortMappings.resolveEffort("opencode", "x-preview-f-free", ReasoningLevel.HIGH))
        assertEquals("max", ReasoningEffortMappings.resolveEffort("opencode", "x-preview-f-free", ReasoningLevel.XHIGH))
        assertEquals("max", ReasoningEffortMappings.resolveEffort("opencode", "x-preview-f-free", ReasoningLevel.MAX))
    }

    @Test
    fun opencodeOtherModelsKeepDefaultSemantics() {
        // 只有 x-preview-f-free 受覆盖，其余 opencode 模型沿用默认映射
        assertEquals("medium", ReasoningEffortMappings.resolveEffort("opencode", "laguna-s-2.1-free", ReasoningLevel.MEDIUM))
        assertEquals("xhigh", ReasoningEffortMappings.resolveEffort("opencode", "hy3-free", ReasoningLevel.XHIGH))
    }

    @Test
    fun gemini3CapsHighAndXhigh() {
        assertEquals("low", ReasoningEffortMappings.resolveEffort("gemini3", "gemini-3-flash", ReasoningLevel.LOW))
        assertEquals("medium", ReasoningEffortMappings.resolveEffort("gemini3", "gemini-3-flash", ReasoningLevel.MEDIUM))
        assertEquals("high", ReasoningEffortMappings.resolveEffort("gemini3", "gemini-3-flash", ReasoningLevel.HIGH))
        assertEquals("high", ReasoningEffortMappings.resolveEffort("gemini3", "gemini-3-flash", ReasoningLevel.XHIGH))
    }

    @Test
    fun claudeUsesDefaultMapping() {
        // Claude 的 output_config.effort 接受 xhigh，保持默认透传
        assertEquals("xhigh", ReasoningEffortMappings.resolveEffort("claude", "claude-opus-4-7", ReasoningLevel.XHIGH))
        assertEquals("medium", ReasoningEffortMappings.resolveEffort("claude", "claude-sonnet-4-5", ReasoningLevel.MEDIUM))
    }

    @Test
    fun unknownScopeFallsBackToDefault() {
        assertEquals("high", ReasoningEffortMappings.resolveEffort("some-new-provider", "any-model", ReasoningLevel.HIGH))
        assertEquals("xhigh", ReasoningEffortMappings.resolveEffort("some-new-provider", "any-model", ReasoningLevel.XHIGH))
    }

    @Test
    fun userMappingTakesPriorityOverEverything() {
        // 用户配置优先于模型定向覆盖
        val userMap = mapOf(ReasoningLevel.XHIGH to "maxxed")
        assertEquals(
            "maxxed",
            ReasoningEffortMappings.resolveEffort("nvidia", "deepseek-v4", ReasoningLevel.XHIGH, userMap)
        )
        // 用户配置优先于作用域默认
        val userOff = mapOf(ReasoningLevel.OFF to "off-custom")
        assertEquals("off-custom", ReasoningEffortMappings.resolveEffort("openai_chat", "gpt-5", ReasoningLevel.OFF, userOff))
        // 用户配置优先于全局默认
        val userLow = mapOf(ReasoningLevel.LOW to "low-custom")
        assertEquals("low-custom", ReasoningEffortMappings.resolveEffort(null, "unknown-model", ReasoningLevel.LOW, userLow))
    }

    @Test
    fun userMappingOnlyOverridesConfiguredLevels() {
        // 未配置的等级仍走内置逻辑
        val userMap = mapOf(ReasoningLevel.XHIGH to "max")
        assertEquals("low", ReasoningEffortMappings.resolveEffort("openai_chat", "gpt-5", ReasoningLevel.OFF, userMap))
        assertEquals("high", ReasoningEffortMappings.resolveEffort("openai_chat", "gpt-5", ReasoningLevel.HIGH, userMap))
    }
}
