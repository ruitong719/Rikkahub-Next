package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.context.DEFAULT_ROLLING_CONTEXT_THRESHOLD_TOKENS
import me.rerere.rikkahub.data.ai.context.MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS
import me.rerere.rikkahub.data.ai.context.estimateContextTokens
import me.rerere.rikkahub.data.ai.context.effectiveRollingContextThreshold
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_basic))
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
        AssistantBasicContent(
            innerPadding = innerPadding,
            assistant = assistant,
            providers = providers,
            tags = tags,
            workspaces = workspaces,
            onUpdate = { vm.update(it) },
            vm = vm
        )
    }
}

@Composable
internal fun AssistantBasicContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    tags: List<DataTag>,
    workspaces: List<WorkspaceEntity>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdate(
                        assistant.copy(
                            avatar = avatar
                        )
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_name))
                },
                modifier = Modifier.padding(8.dp),

                ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                name = it
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_tags))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                TagsInput(
                    value = assistant.tags,
                    tags = tags,
                    onValueChange = { tagIds, tagList ->
                        vm.updateTags(tagIds, tagList)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_workspace))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_workspace_desc))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                val selectedWorkspace = workspaces.find { it.id == assistant.workspaceId?.toString() }
                Select(
                    options = listOf<WorkspaceEntity?>(null) + workspaces,
                    selectedOption = selectedWorkspace,
                    onOptionSelected = { workspace ->
                        onUpdate(
                            assistant.copy(
                                workspaceId = workspace?.id?.let { Uuid.parse(it) }
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { workspace ->
                        workspace?.name ?: stringResource(R.string.workspace_no_binding)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useAssistantAvatar,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useAssistantAvatar = it
                                )
                            )
                        }
                    )
                }
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_chat_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_chat_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    chatModelId = it.id
                                )
                            )
                        },
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_temperature_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    temperature = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                if (assistant.temperature != null) {
                    var temperatureInput by remember(assistant.id) {
                        mutableStateOf(assistant.temperature.toString())
                    }
                    val temperatureValue = temperatureInput.toFloatOrNull()
                    OutlinedTextField(
                        value = temperatureInput,
                        onValueChange = { value ->
                            temperatureInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { temperature ->
                                onUpdate(
                                    assistant.copy(
                                        temperature = temperature
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = temperatureValue == null || temperatureValue !in 0f..2f,
                        supportingText = {
                            Text("0 - 2")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_top_p_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    topP = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                assistant.topP?.let { topP ->
                    var topPInput by remember(assistant.id) {
                        mutableStateOf(topP.toString())
                    }
                    val topPValue = topPInput.toFloatOrNull()
                    OutlinedTextField(
                        value = topPInput,
                        onValueChange = { value ->
                            topPInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { nextTopP ->
                                onUpdate(
                                    assistant.copy(
                                        topP = nextTopP
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = topPValue == null || topPValue !in 0f..1f,
                        supportingText = {
                            Text("0 - 1")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_context_message_limit))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_limit_desc),
                    )
                }
            ) {
                var tokenThresholdInput by remember(assistant.id) {
                    mutableStateOf(formatThresholdInput(assistant.rollingContextCompressionThresholdTokens))
                }
                var tokenThresholdFocused by remember(assistant.id) {
                    mutableStateOf(false)
                }
                var showTokenThresholdDialog by remember(assistant.id) {
                    mutableStateOf(false)
                }
                val focusManager = LocalFocusManager.current

                fun commitTokenThresholdInput() {
                    val parsed = parseTokenThresholdInput(tokenThresholdInput)
                    if (parsed == null) {
                        tokenThresholdInput = formatThresholdInput(assistant.rollingContextCompressionThresholdTokens)
                        return
                    }
                    val normalized = normalizeTokenThreshold(parsed)
                    tokenThresholdInput = formatThresholdInput(normalized)
                    if (normalized != assistant.rollingContextCompressionThresholdTokens) {
                        onUpdate(
                            assistant.copy(
                                rollingContextCompressionThresholdTokens = normalized
                            )
                        )
                    }
                    if (normalized != parsed) {
                        showTokenThresholdDialog = true
                    }
                }

                TokenThresholdPresetChips(
                    currentTokens = assistant.rollingContextCompressionThresholdTokens,
                    onSelect = { preset ->
                        onUpdate(
                            assistant.copy(rollingContextCompressionThresholdTokens = preset)
                        )
                        tokenThresholdInput = formatThresholdInput(preset)
                    },
                )

                val parsedTokenThreshold = parseTokenThresholdInput(tokenThresholdInput)
                OutlinedTextField(
                    value = tokenThresholdInput,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() || it in "KkMm." || it == ' ' }) {
                            tokenThresholdInput = input
                            parseTokenThresholdInput(input)
                                ?.takeIf {
                                    it == 0 ||
                                        (it >= MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS && it <= MAX_CONTEXT_TOKEN_THRESHOLD)
                                }
                                ?.takeIf { it != assistant.rollingContextCompressionThresholdTokens }
                                ?.let { next ->
                                    onUpdate(
                                        assistant.copy(
                                            rollingContextCompressionThresholdTokens = next
                                        )
                                    )
                                }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (tokenThresholdFocused && !focusState.isFocused) {
                                commitTokenThresholdInput()
                            }
                            tokenThresholdFocused = focusState.isFocused
                        },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    singleLine = true,
                    isError = tokenThresholdInput.isNotBlank() &&
                        (parsedTokenThreshold == null ||
                            parsedTokenThreshold in 1 until MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS ||
                            parsedTokenThreshold > MAX_CONTEXT_TOKEN_THRESHOLD),
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.assistant_page_context_message_limit_hint,
                                MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS,
                                MAX_CONTEXT_TOKEN_THRESHOLD
                            )
                        )
                    }
                )

                // 按最近一次会话的消息长度估算阈值实际能携带的对话量
                val recentConversation by vm.recentConversation.collectAsStateWithLifecycle()
                val carriedVolume = remember(
                    recentConversation,
                    assistant.rollingContextCompressionThresholdTokens
                ) {
                    estimateCarriedMessages(
                        messages = recentConversation?.currentMessages.orEmpty(),
                        thresholdTokens = effectiveRollingContextThreshold(
                            assistant.rollingContextCompressionThresholdTokens
                        ),
                    )
                }
                if (carriedVolume != null) {
                    Text(
                        text = stringResource(
                            R.string.assistant_page_context_token_estimate,
                            carriedVolume.first,
                            carriedVolume.second,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }

                if (showTokenThresholdDialog) {
                    AlertDialog(
                        onDismissRequest = { showTokenThresholdDialog = false },
                        title = {
                            Text(stringResource(R.string.assistant_page_context_message_limit))
                        },
                        text = {
                            Text(
                                stringResource(
                                    R.string.assistant_page_context_message_limit_out_of_range,
                                    MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS,
                                    MAX_CONTEXT_TOKEN_THRESHOLD
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showTokenThresholdDialog = false }) {
                                Text(stringResource(R.string.common_confirm))
                            }
                        }
                    )
                }

                Text(
                    text = if (assistant.rollingContextCompressionThresholdTokens > 0) stringResource(
                        R.string.assistant_page_context_message_limit_count,
                        formatTokenThreshold(assistant.rollingContextCompressionThresholdTokens)
                    ) else stringResource(R.string.assistant_page_context_message_limit_unlimited),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                )

                if (assistant.rollingContextCompressionThresholdTokens > 0) {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_limit_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_stream_output))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_stream_output_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.streamOutput,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    streamOutput = it
                                )
                            )
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_thinking_budget))
                },
            ) {
                ReasoningButton(
                    reasoningLevel = assistant.reasoningLevel,
                    onUpdateReasoningLevel = { level ->
                        onUpdate(assistant.copy(reasoningLevel = level))
                    }
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_max_tokens))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_max_tokens_desc))
                }
            ) {
                OutlinedTextField(
                    value = assistant.maxTokens?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens = if (text.isBlank()) {
                            null
                        } else {
                            text.toIntOrNull()?.takeIf { it > 0 }
                        }
                        onUpdate(
                            assistant.copy(
                                maxTokens = tokens
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                    },
                    supportingText = {
                        if (assistant.maxTokens != null) {
                            Text(stringResource(R.string.assistant_page_max_tokens_limit, assistant.maxTokens))
                        } else {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_token_limit))
                        }
                    }
                )
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_gradient_background))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_gradient_background_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useGradientBackground,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useGradientBackground = it
                                )
                            )
                        }
                    )
                }
            )

            if (!assistant.useGradientBackground) {
                HorizontalDivider()

                BackgroundPicker(
                    modifier = Modifier.padding(8.dp),
                    background = assistant.background,
                    backgroundOpacity = assistant.backgroundOpacity,
                    onUpdate = { background ->
                        onUpdate(
                            assistant.copy(
                                background = background
                            )
                        )
                    }
                )
            }

            if (!assistant.useGradientBackground && assistant.background != null) {
                val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_opacity))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_background_opacity_desc))
                    }
                ) {
                    Slider(
                        value = backgroundOpacity,
                        onValueChange = {
                            onUpdate(
                                assistant.copy(
                                    backgroundOpacity = it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                                )
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_background_opacity_value,
                            (backgroundOpacity * 100).roundToInt()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/** 阈值输入上限, 与预设档位最大值一致 */
private const val MAX_CONTEXT_TOKEN_THRESHOLD = 512_000

/** 阈值预设档位 (token), 0 表示默认阈值 */
private val TOKEN_THRESHOLD_PRESETS = intArrayOf(0, 64_000, 128_000, 256_000, 512_000)

/**
 * 把输入值规范化: 0(默认) 保留, 其余收拢到 [MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS] ~ [MAX_CONTEXT_TOKEN_THRESHOLD]
 *
 * 下限依据: 低于此值时截断点几乎每轮都在移动, 提示词缓存命中率跌破 90%,
 * 且保留的上下文通常达不到可缓存的最小长度, 限制本身失去意义
 */
internal fun normalizeTokenThreshold(value: Int): Int = when {
    value == 0 -> 0
    value < MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS -> MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS
    value > MAX_CONTEXT_TOKEN_THRESHOLD -> MAX_CONTEXT_TOKEN_THRESHOLD
    else -> value
}

/**
 * 解析 Token 阈值输入, 支持纯数字 (32000) 与 K/M 后缀 (32K, 1.5M) 两种写法
 */
private fun parseTokenThresholdInput(input: String): Int? {
    val match = TOKEN_THRESHOLD_INPUT_REGEX.matchEntire(input.trim()) ?: return null
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    val multiplier = when (match.groupValues[2].uppercase()) {
        "K" -> 1_000f
        "M" -> 1_000_000f
        else -> 1f
    }
    return (number * multiplier).roundToInt()
}

private val TOKEN_THRESHOLD_INPUT_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*([KkMm]?)")

/**
 * 格式化 Token 阈值为人类可读形式 (e.g. "32K", "128K", "1M")
 */
private fun formatTokenThreshold(tokens: Int): String = when {
    tokens <= 0 -> "Default (32K)"
    tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

/** 输入框展示用格式化; 0 表示默认阈值, 必须保持可解析回 0 */
private fun formatThresholdInput(tokens: Int): String =
    if (tokens <= 0) "0" else formatTokenThreshold(tokens)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenThresholdPresetChips(
    currentTokens: Int,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TOKEN_THRESHOLD_PRESETS.forEach { preset ->
            FilterChip(
                selected = currentTokens == preset,
                onClick = { onSelect(preset) },
                label = {
                    Text(
                        text = if (preset == 0) {
                            stringResource(R.string.assistant_page_context_message_preset_default)
                        } else {
                            formatTokenThreshold(preset)
                        }
                    )
                },
            )
        }
    }
}

/**
 * 按最近会话的平均消息长度估算阈值可携带的消息量
 *
 * @return 消息条数 to 对话轮数 (一轮 ≈ 用户+助手两条), 无会话数据时返回 null
 */
private fun estimateCarriedMessages(
    messages: List<UIMessage>,
    thresholdTokens: Int,
): Pair<Int, Int>? {
    if (messages.isEmpty()) return null
    val totalTokens = estimateContextTokens(messages)
    if (totalTokens <= 0) return null
    val avgPerMessage = totalTokens.toFloat() / messages.size
    val messageCount = (thresholdTokens / avgPerMessage).roundToInt().coerceAtLeast(1)
    val roundCount = (messageCount / 2f).roundToInt().coerceAtLeast(1)
    return messageCount to roundCount
}
