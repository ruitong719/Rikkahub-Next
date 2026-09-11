package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * 推荐的提供商列表，在提供商设置页右上角的推荐 Sheet 中展示。
 * 仅保留 fork 自研的 OpenCode Zen；上游赞助商条目（AiHubMix / 随想AI网关 / MaruCode 等）已移除。
 */
val RECOMMENDED_PROVIDERS: List<ProviderSetting> = listOf(
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
