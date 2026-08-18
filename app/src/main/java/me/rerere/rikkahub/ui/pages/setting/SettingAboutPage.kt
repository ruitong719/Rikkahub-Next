package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.SmartPhone01
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.easteregg.EmojiBurstHost
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.DEFAULT_UPDATE_URL
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAboutPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val navController = LocalNavController.current
    var showUpdateUrlDialog by remember { mutableStateOf(false) }
    var updateUrlDraft by remember { mutableStateOf("") }
    val emojiOptions = remember {
        listOf(
            "🎉", "✨", "🌟", "💫", "🎊", "🥳", "🎈", "🎆", "🎇", "🧨",
            "🌈", "🧧", "🎁", "🍬", "🍭", "🍉", "🍓", "🍒", "🍍", "🥭",
            "🐱", "🐶", "🦊", "🐼", "🦁", "🐯", "🐵", "🦄",
            "❤️", "🧡", "💛", "💚", "💙", "💜",
            "🇨🇳", "🌏", "🌍", "🌎",
            "🤗", "🤩", "😆", "😺", "😸", "🤡",
            "💡", "🔥", "💥", "🚀", "⭐", "🌙"
        )
    }
    var logoCenterPx by remember { mutableStateOf(Offset.Zero) }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.about_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        EmojiBurstHost(
            modifier = Modifier.fillMaxSize(),
            emojiOptions = emojiOptions,
            burstCount = 12
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AsyncImage(
                            model = R.mipmap.ic_launcher,
                            contentDescription = "Logo",
                            modifier = Modifier
                                .clip(CircleShape)
                                .size(150.dp)
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInParent()
                                    val size = coordinates.size
                                    logoCenterPx = Offset(
                                        position.x + size.width / 2f,
                                        position.y + size.height / 2f
                                    )
                                }
                                .clickable {
                                    onBurst(logoCenterPx)
                                }
                        )

                        Text(
                            text = "RikkaHub",
                            style = MaterialTheme.typography.displaySmall,
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { navController.navigate(Screen.Debug) },
                            ),
                            leadingContent = { Icon(HugeIcons.Code, null) },
                            supportingContent = {
                                Text("${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}")
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_version)) },
                        )
                        item(
                            onClick = {
                                updateUrlDraft = settings.updateUrl
                                showUpdateUrlDialog = true
                            },
                            leadingContent = { Icon(HugeIcons.Link01, null) },
                            supportingContent = {
                                Text(settings.updateUrl.ifBlank { DEFAULT_UPDATE_URL })
                            },
                            headlineContent = { Text(stringResource(R.string.about_page_update_url)) },
                        )
                    }
                }

                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        item(
                            onClick = { context.openUrl("https://rikka-ai.com/") },
                            leadingContent = { Icon(HugeIcons.Earth, null) },
                            supportingContent = { Text("https://rikka-ai.com") },
                            headlineContent = { Text(stringResource(R.string.about_page_website)) },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/rikkahub/rikkahub") },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = { Text("rikkahub/rikkahub") },
                            headlineContent = { Text("上游项目") },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/ruitong719/Rikkahub-Next/") },
                            leadingContent = { Icon(HugeIcons.Github, null) },
                            supportingContent = { Text("ruitong719/Rikkahub-Next") },
                            headlineContent = { Text("本项目") },
                        )
                        item(
                            onClick = { context.openUrl("https://github.com/rikkahub/rikkahub/blob/master/LICENSE") },
                            leadingContent = { Icon(HugeIcons.File02, null) },
                            supportingContent = { Text("AGPL-3.0") },
                            headlineContent = { Text(stringResource(R.string.about_page_license)) },
                        )
                    }
                }
            }
        }
    }

    if (showUpdateUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateUrlDialog = false },
            title = { Text(stringResource(R.string.about_page_update_url)) },
            text = {
                OutlinedTextField(
                    value = updateUrlDraft,
                    onValueChange = { updateUrlDraft = it },
                    label = { Text(stringResource(R.string.about_page_update_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateSettings(
                            settings.copy(updateUrl = updateUrlDraft.trim())
                        )
                        showUpdateUrlDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateUrlDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
