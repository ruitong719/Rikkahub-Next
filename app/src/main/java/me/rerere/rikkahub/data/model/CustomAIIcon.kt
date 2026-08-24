package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 图标来源：SVG 源码粘贴 / 图片 URL / Emoji。
 * 自定义 AI 图标映射与悬浮球自定义图标共用。
 */
@Serializable
sealed class IconSource {
    @Serializable
    @SerialName("svg")
    data class Svg(val code: String) : IconSource()

    @Serializable
    @SerialName("url")
    data class Url(val url: String) : IconSource()

    @Serializable
    @SerialName("emoji")
    data class Emoji(val emoji: String) : IconSource()
}

/**
 * 供应商/模型名到图标的自定义映射：内置预设（computeAIIconByName）未命中时生效。
 */
@Serializable
data class CustomAIIcon(
    val id: Uuid = Uuid.random(),
    // 匹配关键词：对名称不区分大小写；exactMatch 时全等匹配，否则包含匹配
    val pattern: String,
    val exactMatch: Boolean = false,
    val source: IconSource,
)
