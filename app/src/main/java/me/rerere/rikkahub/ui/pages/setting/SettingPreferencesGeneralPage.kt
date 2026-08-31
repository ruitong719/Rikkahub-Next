package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.Eraser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.service.FloatingBubbleService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ColorPickerRow
import me.rerere.rikkahub.ui.components.ui.IconSourceEditor
import me.rerere.rikkahub.ui.components.ui.IconSourceImage
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.time.Instant

private val UPDATE_PAUSE_DAY_OPTIONS = listOf(7, 14, 21)
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPreferencesGeneralPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var ttsPlaybackSpeed by remember(settings.defaultTTSPlaybackSpeed) {
        mutableFloatStateOf(settings.defaultTTSPlaybackSpeed)
    }

    var showUpdatePauseDialog by remember { mutableStateOf(false) }
    var selectedUpdatePauseDays by remember { mutableStateOf(UPDATE_PAUSE_DAY_OPTIONS.first()) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val updateChecksEnabled =
        displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()

    // 关闭自动检查时"暂停更新"项置灰（CardGroup 的 item 工厂不是 composable，颜色需在此计算）
    val pausedItemColors = if (displaySetting.disableUpdateCheck) {
        ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            supportingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            trailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            overlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            leadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    } else {
        null
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_general))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_update_check_disabled_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_update_check_disabled_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.disableUpdateCheck,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(disableUpdateCheck = it))
                                }
                            )
                        },
                    )
                    // 暂停更新：永久关闭时置灰不可用（disableUpdateCheck 优先于暂停时长）
                    item(
                        onClick = if (displaySetting.disableUpdateCheck) {
                            null
                        } else {
                            {
                                selectedUpdatePauseDays = UPDATE_PAUSE_DAY_OPTIONS.first()
                                showUpdatePauseDialog = true
                            }
                        },
                        colors = pausedItemColors,
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_updates_title)) },
                        supportingContent = {
                            Text(
                                when {
                                    displaySetting.disableUpdateCheck ->
                                        stringResource(R.string.setting_update_check_disabled_state)
                                    updateChecksEnabled ->
                                        stringResource(R.string.setting_update_reminder_enabled)
                                    else ->
                                        stringResource(
                                            R.string.setting_update_reminder_paused_until,
                                            Instant.ofEpochMilli(displaySetting.updateCheckDisabledUntilEpochMillis)
                                                .toLocalDateTime(),
                                        )
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                }
            }

            item {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc)) },
                        trailingContent = {
                            Switch(
                                checked = createNewConversationOnStart,
                                onCheckedChange = { createNewConversationOnStart = it }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.sendOnEnter,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(sendOnEnter = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showMessageJumper,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.showMessageJumper) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.messageJumperOnLeft,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it))
                                    }
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableAutoScroll,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableAutoScroll = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableBlurEffect,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableBlurEffect = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableMessageGenerationHapticEffect,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.skipCropImage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.pasteLongTextAsFile,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.pasteLongTextAsFile) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.pasteLongTextThreshold.toFloat(),
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                        },
                                        valueRange = 100f..10000f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${displaySetting.pasteLongTextThreshold}")
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableVolumeKeyScroll,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.enableVolumeKeyScroll) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.volumeKeyScrollRatio,
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                        },
                                        valueRange = 0.25f..1.0f,
                                        steps = 2,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${(displaySetting.volumeKeyScrollRatio * 100).toInt()}%")
                                }
                            }
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_tts_settings)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_tts_page_default_playback_speed))
                        },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_tts_page_default_playback_speed_description))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = ttsPlaybackSpeed,
                                        onValueChange = {
                                            ttsPlaybackSpeed = (it * 10).roundToInt() / 10f
                                        },
                                        onValueChangeFinished = {
                                            vm.updateSettings(
                                                settings.copy(defaultTTSPlaybackSpeed = ttsPlaybackSpeed)
                                            )
                                        },
                                        valueRange = 0.5f..2.0f,
                                        steps = 14,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(text = "x${"%.1f".format(ttsPlaybackSpeed)}")
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadQuoted,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadQuoted = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadOutsideBrackets,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadOutsideBrackets = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoPlayTTSAfterGeneration,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(autoPlayTTSAfterGeneration = it))
                                }
                            )
                        },
                    )
                }
            }

            item {
                val bubbleContext = LocalContext.current
                var showOverlayPermissionDialog by remember { mutableStateOf(false) }
                // 滑块拖动期间用本地草稿驱动 UI，松手才写设置：
                // 每帧 updateSettings 会触发 DataStore 磁盘写入 + 全应用重组，造成明显卡顿
                var bubbleColorDraft by remember(settings.floatingBubbleColor) {
                    mutableStateOf(Color(settings.floatingBubbleColor.toInt()))
                }
                var bubbleSizeDraft by remember(settings.floatingBubbleSize) {
                    mutableStateOf(settings.floatingBubbleSize.toFloat())
                }
                var bubbleOpacityDraft by remember(settings.floatingBubbleOpacity) {
                    mutableStateOf(settings.floatingBubbleOpacity)
                }
                var expandWidthDraft by remember(settings.floatingBubbleExpandWidth) {
                    mutableStateOf(settings.floatingBubbleExpandWidth.toFloat())
                }
                var expandHeightDraft by remember(settings.floatingBubbleExpandHeight) {
                    mutableStateOf(settings.floatingBubbleExpandHeight.toFloat())
                }
                var showBubbleIconEditor by remember { mutableStateOf(false) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, settings.floatingBubbleEnabled) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME &&
                            settings.floatingBubbleEnabled &&
                            Settings.canDrawOverlays(bubbleContext)
                        ) {
                            bubbleContext.startForegroundService(
                                Intent(bubbleContext, FloatingBubbleService::class.java)
                            )
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_floating_bubble)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_floating_bubble_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.floatingBubbleEnabled,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(settings.copy(floatingBubbleEnabled = enabled))
                                    if (!enabled) {
                                        bubbleContext.stopService(
                                            Intent(bubbleContext, FloatingBubbleService::class.java)
                                        )
                                    } else if (Settings.canDrawOverlays(bubbleContext)) {
                                        bubbleContext.startForegroundService(
                                            Intent(bubbleContext, FloatingBubbleService::class.java)
                                        )
                                    } else {
                                        showOverlayPermissionDialog = true
                                    }
                                }
                            )
                        },
                    )
                    if (settings.floatingBubbleEnabled) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_color)) },
                            supportingContent = {
                                ColorPickerRow(
                                    color = bubbleColorDraft,
                                    onColorChange = { color -> bubbleColorDraft = color },
                                    onColorChangeFinished = {
                                        vm.updateSettings(
                                            settings.copy(
                                                floatingBubbleColor =
                                                    bubbleColorDraft.toArgb().toLong() and 0xFFFFFFFFL
                                            )
                                        )
                                    },
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_size)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = bubbleSizeDraft,
                                        onValueChange = { value -> bubbleSizeDraft = value },
                                        onValueChangeFinished = {
                                            vm.updateSettings(
                                                settings.copy(floatingBubbleSize = bubbleSizeDraft.roundToInt())
                                            )
                                        },
                                        valueRange = 32f..80f,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${bubbleSizeDraft.roundToInt()}dp")
                                }
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_opacity)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = bubbleOpacityDraft.toFloat(),
                                        onValueChange = { value -> bubbleOpacityDraft = value.roundToInt() },
                                        onValueChangeFinished = {
                                            vm.updateSettings(
                                                settings.copy(floatingBubbleOpacity = bubbleOpacityDraft)
                                            )
                                        },
                                        valueRange = 20f..100f,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${bubbleOpacityDraft}%")
                                }
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_custom_icon)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_floating_bubble_custom_icon_desc))
                            },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    // 当前图标预览（SVG/URL/Emoji）；旧 PNG 路径无轻量预览，留空占位
                                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                        settings.floatingBubbleIcon?.let {
                                            IconSourceImage(source = it)
                                        }
                                    }
                                    IconButton(onClick = { showBubbleIconEditor = true }) {
                                        Icon(
                                            imageVector = HugeIcons.Edit03,
                                            contentDescription = stringResource(R.string.setting_page_floating_bubble_pick_icon)
                                        )
                                    }
                                    if (settings.floatingBubbleIcon != null || settings.floatingBubbleIconPath != null) {
                                        IconButton(
                                            onClick = {
                                                settings.floatingBubbleIconPath?.let { path ->
                                                    runCatching { File(path).delete() }
                                                }
                                                vm.updateSettings(
                                                    settings.copy(floatingBubbleIcon = null, floatingBubbleIconPath = null)
                                                )
                                            }
                                        ) {
                                            Icon(
                                                imageVector = HugeIcons.Eraser,
                                                contentDescription = stringResource(R.string.setting_page_floating_bubble_clear_icon)
                                            )
                                        }
                                    }
                                }
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_show_todo_tab)) },
                            trailingContent = {
                                Switch(
                                    checked = settings.floatingBubbleShowTodoTab,
                                    onCheckedChange = { enabled ->
                                        vm.updateSettings(settings.copy(floatingBubbleShowTodoTab = enabled))
                                    },
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_show_live_tab)) },
                            trailingContent = {
                                Switch(
                                    checked = settings.floatingBubbleShowLiveTab,
                                    onCheckedChange = { enabled ->
                                        vm.updateSettings(settings.copy(floatingBubbleShowLiveTab = enabled))
                                    },
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_expand_width)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = expandWidthDraft,
                                        onValueChange = { value -> expandWidthDraft = value },
                                        onValueChangeFinished = {
                                            vm.updateSettings(
                                                settings.copy(floatingBubbleExpandWidth = expandWidthDraft.roundToInt())
                                            )
                                        },
                                        valueRange = 240f..500f,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${expandWidthDraft.roundToInt()}dp")
                                }
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_floating_bubble_expand_height)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = expandHeightDraft,
                                        onValueChange = { value -> expandHeightDraft = value },
                                        onValueChangeFinished = {
                                            vm.updateSettings(
                                                settings.copy(floatingBubbleExpandHeight = expandHeightDraft.roundToInt())
                                            )
                                        },
                                        valueRange = 280f..700f,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("${expandHeightDraft.roundToInt()}dp")
                                }
                            },
                        )
                    }
                }
                if (showOverlayPermissionDialog) {
                    AlertDialog(
                        onDismissRequest = { showOverlayPermissionDialog = false },
                        title = { Text(stringResource(R.string.setting_page_floating_bubble_permission_title)) },
                        text = { Text(stringResource(R.string.setting_page_floating_bubble_permission_desc)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showOverlayPermissionDialog = false
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${bubbleContext.packageName}")
                                    )
                                    runCatching { bubbleContext.startActivity(intent) }
                                }
                            ) {
                                Text(stringResource(R.string.setting_page_floating_bubble_permission_grant))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showOverlayPermissionDialog = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }
                if (showBubbleIconEditor) {
                    ModalBottomSheet(onDismissRequest = { showBubbleIconEditor = false }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("悬浮球图标", style = MaterialTheme.typography.titleMedium)
                            IconSourceEditor(
                                initial = settings.floatingBubbleIcon,
                                onSave = { source ->
                                    vm.updateSettings(settings.copy(floatingBubbleIcon = source))
                                    showBubbleIconEditor = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showUpdatePauseDialog) {
        AlertDialog(
            onDismissRequest = { showUpdatePauseDialog = false },
            title = { Text(stringResource(R.string.setting_update_reminder_pause_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.setting_update_reminder_pause_description))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        UPDATE_PAUSE_DAY_OPTIONS.forEachIndexed { index, days ->
                            SegmentedButton(
                                selected = selectedUpdatePauseDays == days,
                                onClick = { selectedUpdatePauseDays = days },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = UPDATE_PAUSE_DAY_OPTIONS.size,
                                ),
                            ) {
                                Text(stringResource(R.string.setting_update_reminder_pause_days, days))
                            }
                        }
                    }
                    if (!updateChecksEnabled) {
                        TextButton(
                            onClick = {
                                updateDisplaySetting(
                                    displaySetting.copy(updateCheckDisabledUntilEpochMillis = 0L)
                                )
                                showUpdatePauseDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_update_reminder_resume_now))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        updateDisplaySetting(
                            displaySetting.copy(
                                updateCheckDisabledUntilEpochMillis =
                                    System.currentTimeMillis() + selectedUpdatePauseDays * MILLIS_PER_DAY,
                            )
                        )
                        showUpdatePauseDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdatePauseDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}


