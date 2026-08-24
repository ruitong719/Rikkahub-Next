package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.CustomAIIcon
import me.rerere.rikkahub.data.model.IconSource
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.IconSourceEditor
import me.rerere.rikkahub.ui.components.ui.IconSourceImage
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 自定义图标映射管理：供应商/模型名 -> 图标（SVG 源码 / 图片 URL / Emoji）。
 * 内置预设未命中名称时按这里的条目匹配。
 */
@Composable
fun SettingCustomIconsPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var showEditor by remember { mutableStateOf(false) }
    // 正在编辑的条目；null 表示新增
    var editing by remember { mutableStateOf<CustomAIIcon?>(null) }
    var pendingDelete by remember { mutableStateOf<CustomAIIcon?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("自定义图标") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = null
                showEditor = true
            }) {
                Icon(HugeIcons.Add01, contentDescription = "添加")
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val icons = settings.customAiIcons
        if (icons.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("暂无映射", style = MaterialTheme.typography.titleMedium)
                Text(
                    "点击右下角按钮，把供应商/模型名映射到自定义图标",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(icons, key = { it.id }) { icon ->
                    Card(onClick = {
                        editing = icon
                        showEditor = true
                    }) {
                        ListItem(
                            headlineContent = { Text(icon.pattern) },
                            supportingContent = { Text(describeCustomAIIcon(icon)) },
                            leadingContent = {
                                IconSourceImage(source = icon.source)
                            },
                            trailingContent = {
                                IconButton(onClick = { pendingDelete = icon }) {
                                    Icon(HugeIcons.Delete01, contentDescription = "删除")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        CustomAIIconEditSheet(
            settings = settings,
            editing = editing,
            onUpdateSettings = { vm.updateSettings(it) },
            onDismiss = { showEditor = false },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除映射") },
            text = { Text("确定删除「${target.pattern}」的图标映射吗？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings(
                        settings.copy(customAiIcons = settings.customAiIcons.filterNot { it.id == target.id })
                    )
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 编辑弹层：匹配关键词 + 匹配方式 + 图标来源 */
@Composable
private fun CustomAIIconEditSheet(
    settings: Settings,
    editing: CustomAIIcon?,
    onUpdateSettings: (Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    var pattern by remember(editing?.id) { mutableStateOf(editing?.pattern ?: "") }
    var exactMatch by remember(editing?.id) { mutableStateOf(editing?.exactMatch ?: false) }
    var patternError by remember(editing?.id) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (editing == null) "添加映射" else "编辑映射",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = pattern,
                onValueChange = {
                    pattern = it
                    patternError = false
                },
                label = { Text("匹配关键词") },
                supportingText = { Text("供应商或模型名的一部分即可，不区分大小写") },
                isError = patternError,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = exactMatch, onCheckedChange = { exactMatch = it })
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text("精确匹配（全等时才生效）")
            }
            IconSourceEditor(
                initial = editing?.source,
                onSave = { source ->
                    val trimmed = pattern.trim()
                    if (trimmed.isEmpty()) {
                        patternError = true
                        return@IconSourceEditor
                    }
                    val entry = editing?.copy(
                        pattern = trimmed,
                        exactMatch = exactMatch,
                        source = source,
                    ) ?: CustomAIIcon(
                        pattern = trimmed,
                        exactMatch = exactMatch,
                        source = source,
                    )
                    val updated = if (editing == null) {
                        settings.customAiIcons + entry
                    } else {
                        settings.customAiIcons.map { if (it.id == entry.id) entry else it }
                    }
                    onUpdateSettings(settings.copy(customAiIcons = updated))
                    onDismiss()
                },
            )
        }
    }
}

private fun describeCustomAIIcon(icon: CustomAIIcon): String {
    val source = when (val src = icon.source) {
        is IconSource.Svg -> "SVG 源码（${src.code.length} 字符）"
        is IconSource.Url -> src.url
        is IconSource.Emoji -> src.emoji
    }
    return if (icon.exactMatch) "$source · 精确匹配" else source
}
