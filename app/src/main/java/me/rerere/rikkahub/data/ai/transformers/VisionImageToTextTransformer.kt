package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

private const val TAG = "VisionImageToText"

/**
 * 视觉模型降级转换器（image-router 式网关拦截）。
 *
 * 当主模型不支持图片输入（inputModalities 不含 IMAGE）且设置了「视觉模型」时，
 * 在请求发送前把消息（含历史消息）中的所有图片调用视觉模型生成文字描述，
 * 替换为文本块，避免纯文本模型解析图片块报错导致会话损坏（历史会话修复）。
 *
 * 行为约定：
 * - 主模型支持图片 / 未配置视觉模型 / 视觉模型本身不支持图片 → 透传，保持原行为
 * - 图片 → 描述结果按 url 缓存（同一会话内同一张图只调用一次视觉模型，控制成本）
 * - 视觉模型调用失败 → 替换为「无法解析」占位文本，不阻塞生成
 */
class VisionImageToTextTransformer(
    private val providerManager: ProviderManager,
) : InputMessageTransformer {

    private val descriptionCache = LinkedHashMap<String, String>(64, 0.75f, true)

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val visionModelId = ctx.settings.visionModelId ?: return messages
        // 主模型支持图片输入时原样直传
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) return messages
        if (messages.none { message -> message.parts.any { it is UIMessagePart.Image } }) return messages

        val model = ctx.settings.providers.findModelById(visionModelId) ?: return messages
        if (!model.inputModalities.contains(Modality.IMAGE)) return messages
        val provider = model.findProvider(ctx.settings.providers) ?: return messages
        val providerImpl = providerManager.getProviderByType(provider)

        return messages.map { message ->
            if (message.parts.none { it is UIMessagePart.Image }) return@map message
            message.copy(
                parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Image) return@map part
                    val cached = descriptionCache[part.url]
                    val desc = if (cached != null) {
                        cached
                    } else {
                        ctx.processingStatus.value = "正在用视觉模型解析图片…"
                        val result = runCatching {
                            describeImage(providerImpl, provider, model, part)
                        }.getOrElse { e ->
                            Log.w(TAG, "describe image failed: ${e.message}")
                            ""
                        }
                        if (result.isNotBlank()) descriptionCache[part.url] = result
                        result
                    }
                    UIMessagePart.Text(
                        if (desc.isBlank()) "[图片（无法解析）]" else "[图片描述] $desc"
                    )
                }
            )
        }
    }

    private suspend fun describeImage(
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        model: Model,
        image: UIMessagePart.Image,
    ): String {
        val prompt = "请详细描述这张图片的内容（包括可见的物体、文字、布局、颜色等关键信息），只输出描述本身。"
        val response = providerImpl.generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(prompt), image)
                )
            ),
            params = TextGenerationParams(model = model, temperature = 0.2f),
        )
        return response.message.toText().trim()
    }
}
