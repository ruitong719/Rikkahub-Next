package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.IconSource

private val SOURCE_TYPES = listOf("SVG", "URL", "Emoji")

/**
 * 图标来源编辑器：SVG 源码粘贴 / 图片 URL / Emoji 三选一，带实时预览与保存校验。
 * 自定义 AI 图标映射页与悬浮球图标设置共用；通常放在 ModalBottomSheet 内使用。
 */
@Composable
fun IconSourceEditor(
    initial: IconSource?,
    onSave: (IconSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typeIndex by remember(initial) {
        mutableIntStateOf(
            when (initial) {
                is IconSource.Svg -> 0
                is IconSource.Url -> 1
                is IconSource.Emoji -> 2
                null -> 0
            }
        )
    }
    var svgCode by remember(initial) { mutableStateOf((initial as? IconSource.Svg)?.code ?: "") }
    var url by remember(initial) { mutableStateOf((initial as? IconSource.Url)?.url ?: "") }
    var emoji by remember(initial) { mutableStateOf((initial as? IconSource.Emoji)?.emoji ?: "") }
    var showEmojiPicker by remember { mutableStateOf(false) }

    val current: IconSource = when (SOURCE_TYPES[typeIndex]) {
        "SVG" -> IconSource.Svg(svgCode.trim())
        "URL" -> IconSource.Url(url.trim())
        else -> IconSource.Emoji(emoji.trim())
    }

    val valid: Boolean = when (current) {
        is IconSource.Svg -> current.code.contains("<svg", ignoreCase = true)
        is IconSource.Url -> current.url.startsWith("http://") || current.url.startsWith("https://")
        is IconSource.Emoji -> current.emoji.isNotBlank()
    }
    val invalidHint: String = when (current) {
        is IconSource.Svg -> "需要包含 <svg> 标签"
        is IconSource.Url -> "需要以 http(s):// 开头"
        else -> ""
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SOURCE_TYPES.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = typeIndex == index,
                    onClick = { typeIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SOURCE_TYPES.size),
                ) {
                    Text(label)
                }
            }
        }

        when (SOURCE_TYPES[typeIndex]) {
            "SVG" -> OutlinedTextField(
                value = svgCode,
                onValueChange = { svgCode = it },
                label = { Text("SVG 源码") },
                placeholder = { Text("<svg xmlns=\"...\">...</svg>") },
                isError = !valid && svgCode.isNotBlank(),
                supportingText = if (!valid && svgCode.isNotBlank()) {
                    { Text(invalidHint) }
                } else null,
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )

            "URL" -> OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("图片 URL") },
                placeholder = { Text("https://example.com/logo.svg") },
                isError = !valid && url.isNotBlank(),
                supportingText = if (!valid && url.isNotBlank()) {
                    { Text(invalidHint) }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    label = { Text("Emoji") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { showEmojiPicker = true }) {
                    Text("选择")
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconSourceImage(source = current, modifier = Modifier.size(48.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "预览", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (valid) "点击保存生效" else invalidHint.ifBlank { "请输入内容" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { onSave(current) },
                enabled = valid,
            ) {
                Text("保存")
            }
        }
    }

    if (showEmojiPicker) {
        ModalBottomSheet(onDismissRequest = { showEmojiPicker = false }) {
            EmojiPicker(
                onEmojiSelected = { picked ->
                    emoji = picked.emoji
                    showEmojiPicker = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
        }
    }
}
