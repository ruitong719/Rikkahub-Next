package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.network.ClientPreset
import me.rerere.rikkahub.data.network.ClientPresets
import me.rerere.rikkahub.ui.components.ui.CardGroup

/**
 * 供应商级客户端身份：为该供应商的请求覆盖默认 header（User-Agent 等），
 * 用于模拟常见 harness 客户端（Claude Code / Codex CLI / OpenCode 等）。
 */
@Composable
fun ProviderIdentityCard(
    provider: ProviderSetting,
    networkSetting: NetworkSetting,
    onUpdateIdentity: (identity: Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val identity = networkSetting.providerIdentities[provider.id.toString()].orEmpty()
    // remember 只以 provider.id 为 key：identity 提交后 networkSetting 会变成新实例，
    // 若以其为 key 会在输入过程中重置本地状态
    var userAgent by remember(provider.id) {
        mutableStateOf(identity[ClientPresets.USER_AGENT_HEADER].orEmpty())
    }
    // 附加 header 按稳定行编辑；User-Agent 单独走上面的字段
    var extraHeaders by remember(provider.id) {
        mutableStateOf(
            identity.filterKeys { it != ClientPresets.USER_AGENT_HEADER }.map { it.key to it.value }
        )
    }

    fun commit() {
        val merged = buildMap {
            if (userAgent.isNotBlank()) put(ClientPresets.USER_AGENT_HEADER, userAgent.trim())
            extraHeaders.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) put(name.trim(), value.trim())
            }
        }
        onUpdateIdentity(merged)
    }

    fun applyPreset(preset: ClientPreset) {
        userAgent = preset.userAgent
        extraHeaders = preset.headers.map { it.key to it.value }
        onUpdateIdentity(preset.toHeaders())
    }

    val providerHost = ClientPresets.providerHost(provider)
    val matchedPreset = providerHost?.let { host ->
        ClientPresets.ALL.firstOrNull { host in it.matchHosts }
    }

    CardGroup(
        modifier = modifier.padding(horizontal = 16.dp),
        title = { Text(stringResource(R.string.setting_page_client_identity)) },
    ) {
        item(
            headlineContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 预设：hermes-agent 在生产环境验证过的客户端指纹组合
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ClientPresets.ALL.forEach { preset ->
                            SuggestionChip(
                                onClick = { applyPreset(preset) },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                    matchedPreset?.let {
                        Text(
                            text = stringResource(
                                R.string.setting_page_client_identity_host_hint,
                                providerHost.orEmpty(),
                                it.name,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    OutlinedTextField(
                        value = userAgent,
                        onValueChange = {
                            userAgent = it
                            commit()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.setting_page_preferences_network_user_agent)) },
                        singleLine = true,
                    )
                    extraHeaders.forEachIndexed { index, (name, value) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { new ->
                                    extraHeaders = extraHeaders.toMutableList().apply {
                                        set(index, new to value)
                                    }
                                    commit()
                                },
                                modifier = Modifier.weight(0.4f),
                                label = { Text(stringResource(R.string.setting_page_client_identity_header_name)) },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = value,
                                onValueChange = { new ->
                                    extraHeaders = extraHeaders.toMutableList().apply {
                                        set(index, name to new)
                                    }
                                    commit()
                                },
                                modifier = Modifier.weight(0.6f),
                                label = { Text(stringResource(R.string.setting_page_client_identity_header_value)) },
                                singleLine = true,
                            )
                            IconButton(
                                onClick = {
                                    extraHeaders = extraHeaders.filterIndexed { i, _ -> i != index }
                                    commit()
                                },
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Delete02,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            extraHeaders = extraHeaders + ("" to "")
                            commit()
                        },
                    ) {
                        Icon(HugeIcons.Add01, null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.setting_page_client_identity_add_header))
                    }
                }
            },
            supportingContent = {
                Text(stringResource(R.string.setting_page_client_identity_desc))
            },
        )
    }
}
