package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("notify")
    data object Notify : LocalToolOption()

    // 工具已下线：仅为旧设置数据的反序列化兼容保留（清数据后可删）
    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    // 工具已下线：仅为旧设置数据的反序列化兼容保留（清数据后可删）
    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("todo")
    data object Todo : LocalToolOption()
}
