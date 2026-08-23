package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.network.ClientPresets
import me.rerere.rikkahub.ui.components.ui.Select

/**
 * 供应商级客户端身份：为该供应商的请求覆盖默认 header（User-Agent 等），
 * 用于模拟常见 harness 客户端（Claude Code / Codex CLI / OpenCode 等）。
 *
 * 与「获取账户余额」同款折叠布局：默认只显示一行标题，不启用、不覆写任何
 * header（请求使用 RikkaHub 默认标识）；勾选启用并从下拉框选择客户端预设后，
 * 拦截器才应用该预设的指纹覆写。
 */
@Composable
fun ProviderIdentityCard(
    provider: ProviderSetting,
    networkSetting: NetworkSetting,
    onUpdateIdentity: (identity: Map<String, String>) -> Unit,
    onUpdateEnabled: (enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val providerId = provider.id.toString()
    val identity = networkSetting.providerIdentities[providerId].orEmpty()
    val enabled = providerId in networkSetting.providerIdentityEnabledIds
    var expand by remember { mutableStateOf(false) }

    // 当前选中的预设：按已存的 User-Agent 反查预设表
    val selectedPreset = identity[ClientPresets.USER_AGENT_HEADER]
        ?.let { ua -> ClientPresets.ALL.firstOrNull { it.userAgent == ua } }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.setting_page_client_identity))
                // 折叠状态下也展示当前生效状态
                Text(
                    text = if (enabled && selectedPreset != null) {
                        stringResource(R.string.setting_page_client_identity_enabled_hint, selectedPreset.name)
                    } else {
                        stringResource(R.string.setting_page_client_identity_disabled_hint)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { expand = !expand }) {
                Icon(
                    imageVector = if (expand) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                )
            }
            // 总开关：不勾选时不覆写任何 header，使用 RikkaHub 默认标识
            Checkbox(
                checked = enabled,
                onCheckedChange = onUpdateEnabled,
            )
        }
        AnimatedVisibility(visible = expand) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.setting_page_client_identity_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 客户端预设下拉：hermes-agent 在生产环境验证过的客户端指纹组合
                Select(
                    options = listOf("") + ClientPresets.ALL.map { it.name },
                    selectedOption = selectedPreset?.name ?: "",
                    onOptionSelected = { name ->
                        val preset = ClientPresets.ALL.firstOrNull { it.name == name }
                        // 选中「不覆写」时清空 identity；否则存该预设的完整 header 表
                        onUpdateIdentity(preset?.toHeaders() ?: emptyMap())
                    },
                    optionToString = { if (it.isEmpty()) stringResource(R.string.setting_page_client_identity_none) else it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
