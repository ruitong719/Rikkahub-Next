package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

/**
 * 推荐的提供商列表，在提供商设置页右上角的推荐 Sheet 中展示。
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append("提供 OpenAI、Claude、Google Gemini 等主流模型的高并发和稳定服务")
                    appendLine()
                    append("官网：")
                    withLink(LinkAnnotation.Url("https://aihubmix.com?aff=pG7r")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://aihubmix.com")
                        }
                    }
                    appendLine()
                    append("充值: ")
                    withLink(LinkAnnotation.Url("https://console.aihubmix.com/topup")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://console.aihubmix.com/topup")
                        }
                    }
                }
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd"),
        name = "随想AI网关",
        baseUrl = "https://sui-xiang.com/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append("可靠高效的 API 中继服务，提供 Claude、Codex、Gemini 等中继服务。注重隐私·无数据倒卖·无模型掺水，充值额度 1:1，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。")
                    appendLine()
                    append("官网：")
                    withLink(LinkAnnotation.Url("https://sui-xiang.com")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://sui-xiang.com")
                        }
                    }
                }
            )
        },
    ),
    // OpenCode Zen 免费档：keyless（空 apiKey 时拦截器会移除 Authorization，
    // Zen 对未知 Bearer 直接 401），身份 header 由 ClientPresets 按 host 自动注入
    ProviderSetting.OpenAI(
        id = Uuid.parse("7f0e3c2a-9d41-4b58-a6c3-2e5f8b1d4a90"),
        name = "OpenCode Zen",
        baseUrl = "https://opencode.ai/zen/v1",
        apiKey = "",
        enabled = true,
        description = {
            Text(
                text = buildAnnotatedString {
                    append("OpenCode 官方模型网关免费档，无需 API Key，提供 grok-code、big-pickle、kimi-k2.5-free、glm-5-free 等免费模型。")
                    appendLine()
                    append("订阅用户将 baseUrl 改为 ")
                    withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                        append("https://opencode.ai/zen/go/v1")
                    }
                    append(" 并填入 API Key 即可使用全部模型。")
                    appendLine()
                    append("文档：")
                    withLink(LinkAnnotation.Url("https://opencode.ai/docs/zen")) {
                        withStyle(SpanStyle(MaterialTheme.colorScheme.primary)) {
                            append("https://opencode.ai/docs/zen")
                        }
                    }
                }
            )
        },
    ),
)
