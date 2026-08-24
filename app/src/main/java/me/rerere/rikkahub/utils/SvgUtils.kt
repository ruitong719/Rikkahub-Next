package me.rerere.rikkahub.utils

import java.util.Base64

/**
 * SVG 源码转 base64 data URI，供 coil 直接加载。
 * 用 java.util.Base64 而非 android.util.Base64，保持 JVM 单测可用。
 */
fun svgToDataUri(code: String): String =
    "data:image/svg+xml;base64," +
        Base64.getEncoder().encodeToString(code.toByteArray(Charsets.UTF_8))
