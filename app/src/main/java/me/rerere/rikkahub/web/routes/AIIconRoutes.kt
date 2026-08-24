package me.rerere.rikkahub.web.routes

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.IconSource
import me.rerere.rikkahub.utils.computeAIIconByName
import me.rerere.rikkahub.utils.matchCustomAIIcon
import me.rerere.rikkahub.web.BadRequestException

fun Route.aiIconRoutes(context: Context, settingsStore: SettingsStore) {
    route("/ai-icon") {
        get {
            val name = call.request.queryParameters["name"]?.trim()
                ?: throw BadRequestException("Missing name")
            if (name.isEmpty()) {
                throw BadRequestException("Missing name")
            }

            val iconPath = computeAIIconByName(name)
            if (iconPath != null) {
                val assetPath = "icons/$iconPath"
                runCatching {
                    context.assets.open(assetPath).use { input ->
                        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
                        call.response.header(HttpHeaders.ContentType, resolveContentType(iconPath).toString())
                        call.respondOutputStream {
                            input.copyTo(this)
                        }
                    }
                }.onSuccess {
                    return@get
                }
            }

            // 预设未命中：查自定义映射（SVG 直接回源码 / URL 302 重定向 / Emoji 内联 SVG）
            val custom = matchCustomAIIcon(name, settingsStore.settingsFlow.value.customAiIcons)?.source
            when (custom) {
                is IconSource.Svg -> {
                    call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
                    call.respondText(text = custom.code, contentType = ContentType.Image.SVG)
                    return@get
                }

                is IconSource.Url -> {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondRedirect(custom.url)
                    return@get
                }

                is IconSource.Emoji -> {
                    call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
                    call.respondText(
                        text = buildEmojiSvg(custom.emoji),
                        contentType = ContentType.Image.SVG,
                    )
                    return@get
                }

                null -> {}
            }

            call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
            call.respondText(
                text = buildFallbackSvg(name),
                contentType = ContentType.Image.SVG,
            )
        }
    }
}

private fun resolveContentType(path: String): ContentType {
    return when (path.substringAfterLast('.').lowercase()) {
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "webp" -> ContentType("image", "webp")
        else -> ContentType.Application.OctetStream
    }
}

private fun buildFallbackSvg(name: String): String {
    val text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    val escapedText = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
          <rect x="0" y="0" width="64" height="64" rx="32" fill="#E9EAEE"/>
          <text x="32" y="36" font-family="system-ui, sans-serif" font-size="24" font-weight="600" text-anchor="middle" fill="#4E5969">$escapedText</text>
        </svg>
    """.trimIndent()
}

private fun buildEmojiSvg(emoji: String): String {
    val escaped = emoji
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
          <text x="32" y="34" font-size="44" text-anchor="middle" dominant-baseline="central">$escaped</text>
        </svg>
    """.trimIndent()
}
