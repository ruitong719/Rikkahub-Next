package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.css
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.rememberAvatarShape
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.IconSource
import me.rerere.rikkahub.utils.computeAIIconByName
import me.rerere.rikkahub.utils.matchCustomAIIcon
import me.rerere.rikkahub.utils.svgToDataUri
import me.rerere.rikkahub.utils.toCssHex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject

@Composable
private fun AIIcon(
    path: String,
    name: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val contentColor = LocalContentColor.current
    val context = LocalContext.current
    val model = remember(path, contentColor, context) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/icons/$path")
            .css(
                """
                svg {
                  fill: ${contentColor.toCssHex()};
                }
            """.trimIndent()
            )
            .build()
    }
    Surface(
        modifier = modifier.size(24.dp),
        shape = rememberAvatarShape(loading),
        color = color,
    ) {
        AsyncImage(
            model = model,
            contentDescription = name,
            modifier = Modifier.padding(4.dp)
        )
    }
}

@Composable
fun AutoAIIcon(
    name: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    // 内置预设优先，未命中再查自定义映射，最后回退首字母
    val path = remember(name) { computeAIIconByName(name) }
    if (path != null) {
        AIIcon(
            path = path,
            name = name,
            modifier = modifier,
            loading = loading,
            color = color,
        )
        return
    }

    val customSource = remember(name, settings.customAiIcons) {
        matchCustomAIIcon(name, settings.customAiIcons)?.source
    }
    if (customSource == null) {
        TextAvatar(text = name, modifier = modifier, loading = loading, color = color)
        return
    }
    IconSourceImage(source = customSource, modifier = modifier, color = color)
}

/**
 * 渲染自定义图标来源（SVG 源码 / 图片 URL / Emoji）。
 * 供 AutoAIIcon、自定义图标设置页与悬浮球设置预览共用。
 */
@Composable
fun IconSourceImage(
    source: IconSource,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    size: Dp = 24.dp,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.size(size),
        shape = rememberAvatarShape(false),
        color = color,
    ) {
        if (source is IconSource.Emoji) {
            val density = LocalDensity.current
            Text(
                text = source.emoji,
                fontSize = with(density) { (size * 0.625f).toSp() },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(3.dp)
            )
        } else {
            AsyncImage(
                model = remember(source) {
                    ImageRequest.Builder(context)
                        .data(
                            when (source) {
                                is IconSource.Svg -> svgToDataUri(source.code)
                                is IconSource.Url -> source.url
                            }
                        )
                        .build()
                },
                contentDescription = null,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

@Preview
@Composable
private fun PreviewAutoAIIcon() {
    Column {
        AutoAIIcon("测试")
    }
}

@Composable
fun SiliconFlowPowerByIcon(modifier: Modifier = Modifier) {
    val darkMode = LocalDarkMode.current
    if (!darkMode) {
        AsyncImage(model = R.drawable.siliconflow_light, contentDescription = null, modifier = modifier)
    } else {
        AsyncImage(model = R.drawable.siliconflow_dark, contentDescription = null, modifier = modifier)
    }
}
