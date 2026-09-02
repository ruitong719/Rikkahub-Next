package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

private const val DEBOUNCE_MIN_MS = 2
private const val DEBOUNCE_MAX_MS = 100

/**
 * 实验性功能开关集中页（从「常规」页拆出，与主题/通知/常规同级）。
 * 新的实验性开关默认加到这里；转正后迁移到对应的功能设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPreferencesExperimentalPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_experimental))
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
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_experimental_stream_reconnect_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_experimental_stream_reconnect_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.enableStreamAutoReconnect,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(settings.copy(enableStreamAutoReconnect = checked))
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_shell_live_output_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_shell_live_output_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.displaySetting.enableShellLiveOutput,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            displaySetting = settings.displaySetting.copy(enableShellLiveOutput = checked)
                                        )
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_experimental_streaming_debounce_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_experimental_streaming_debounce_desc))
                                OutlinedTextField(
                                    value = settings.displaySetting.streamingDebounceMs.toString(),
                                    onValueChange = { input ->
                                        val value = input.filter { it.isDigit() }.toIntOrNull()
                                            ?.coerceIn(DEBOUNCE_MIN_MS, DEBOUNCE_MAX_MS)
                                        if (value != null) {
                                            vm.updateSettings(
                                                settings.copy(
                                                    displaySetting = settings.displaySetting.copy(streamingDebounceMs = value)
                                                )
                                            )
                                        }
                                    },
                                    label = { Text("ms") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(100.dp),
                                    singleLine = true,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
