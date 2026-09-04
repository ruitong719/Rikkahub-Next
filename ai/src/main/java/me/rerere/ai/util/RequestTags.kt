package me.rerere.ai.util

import me.rerere.ai.provider.TextGenerationParams
import okhttp3.Request

/**
 * 挂在 OkHttp Request 上的会话 ID tag，供 app 模块共享拦截器解析客户端身份
 * 伪装中的动态占位符（ClientPresets.SESSION_ID_PLACEHOLDER）。
 * tag 随请求走，多个会话并发生成时互不干扰。
 */
class SessionIdRequestTag(val sessionId: String)

/** 生成请求构建时挂上会话 ID；无会话（sessionId 为 null）时不挂，占位符 header 将跳过注入。 */
fun Request.Builder.attachSessionId(params: TextGenerationParams): Request.Builder {
    params.sessionId?.let {
        tag(SessionIdRequestTag::class.java, SessionIdRequestTag(it))
    }
    return this
}