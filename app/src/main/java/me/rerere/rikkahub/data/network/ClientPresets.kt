package me.rerere.rikkahub.data.network

import me.rerere.ai.provider.ProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 客户端身份预设：模拟常见 harness/客户端访问 API。
 *
 * 组合取自 hermes-agent 与 opencode 官方客户端的生产实现 —— 部分服务端按
 * 客户端指纹放行或拒绝：
 * - api.kimi.com (Kimi For Coding) 要求 ``claude-code`` UA，否则 403
 * - chatgpt.com Codex 后端的 Cloudflare 白名单按 ``originator`` + UA 形态识别，
 *   非白名单来源直接 403 challenge（与鉴权无关）
 * - opencode.ai Zen 免费档部分模型 UA 门控；Go 订阅端点为 /zen/go/v1
 */
data class ClientPreset(
    val name: String,
    val userAgent: String,
    // UA 之外的附加 header（如 Codex 的 originator）
    val headers: Map<String, String> = emptyMap(),
    // 常见宿主 host：命中时自动应用该预设（可被供应商级自定义覆盖）
    val matchHosts: List<String> = emptyList(),
) {
    /** 组合出完整的 header 表（含 User-Agent），供一键套用 */
    fun toHeaders(): Map<String, String> = buildMap {
        put(ClientPresets.USER_AGENT_HEADER, userAgent)
        putAll(headers)
    }
}

/**
 * 客户端身份预设注册表：预设清单、常量与按 host 的匹配/读取逻辑。
 */
object ClientPresets {
    const val USER_AGENT_HEADER = "User-Agent"

    /**
     * 动态占位符：header 值中的该占位符由共享 OkHttpClient 拦截器替换为当前
     * 请求的会话 ID（生成请求通过 RequestTags.attachSessionId 挂载）；
     * 非生成请求无会话 ID，含占位符的 header 跳过注入。
     */
    const val SESSION_ID_PLACEHOLDER = "{sessionId}"

    val ALL = listOf(
        ClientPreset(
            name = "Claude Code",
            userAgent = "claude-code/0.1.0",
            matchHosts = listOf("api.kimi.com"),
        ),
        ClientPreset(
            name = "Codex CLI",
            userAgent = "codex_cli_rs/0.46.0 (Linux; arm64)",
            headers = mapOf("originator" to "codex_cli_rs"),
            matchHosts = listOf("chatgpt.com"),
        ),
        // opencode 官方格式：opencode/{channel}/{version}/{client}
        // 见 packages/opencode/src/installation/index.ts userAgent()
        ClientPreset(
            name = "OpenCode",
            userAgent = "opencode/latest/1.18.21/cli",
            headers = mapOf(
                "x-opencode-client" to "cli",
                // 会话头为动态值：仅生成请求注入（拦截器把 {sessionId} 替换为当前会话 ID）
                "x-opencode-session" to ClientPresets.SESSION_ID_PLACEHOLDER,
            ),
            matchHosts = listOf("opencode.ai"),
        ),
        ClientPreset(
            name = "Gemini CLI",
            userAgent = "GeminiCLI/v19.4.0 (linux; arm64)",
        ),
        ClientPreset(
            name = "Cherry Studio",
            userAgent = "CherryStudio/1.5.6",
        ),
        ClientPreset(
            name = "Chatbox",
            userAgent = "Chatbox/1.16.0",
        ),
        ClientPreset(
            name = "curl",
            userAgent = "curl/8.5.0",
        ),
    )

    /**
     * 按请求 host 匹配应自动应用的预设（hermes-agent 的按 host 分发方案）。
     * 仅当该供应商没有自定义身份时生效。
     */
    fun findAutoPreset(host: String): ClientPreset? {
        return ALL.firstOrNull { host in it.matchHosts }
    }

    /**
     * 按请求 host 匹配供应商（取第一个 baseUrl host 相同者）。
     */
    fun findProviderByHost(
        providers: List<ProviderSetting>,
        host: String,
    ): ProviderSetting? {
        return providers.firstOrNull { provider ->
            providerHost(provider) == host
        }
    }

    fun providerHost(provider: ProviderSetting): String? {
        return runCatching { provider.baseUrl().toHttpUrlOrNull()?.host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun ProviderSetting.baseUrl(): String = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }

    /** 供应商配置的 API key（各子类型独立声明，此处统一读取） */
    fun ProviderSetting.apiKeyOrNull(): String = when (this) {
        is ProviderSetting.OpenAI -> apiKey
        is ProviderSetting.Google -> apiKey
        is ProviderSetting.Claude -> apiKey
    }
}
