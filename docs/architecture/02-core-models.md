# 02 · ai 模块核心模型（LLM 抽象层）

> 模块路径：`ai/src/main/java/me/rerere/ai/`。这是全项目与 LLM 交互的底座：消息模型、流事件、Provider 协议实现、模型能力注册表。app 模块经 Koin 注入 `ProviderManager` 等类型。
> 依赖：仅 `:common`（OkHttp/kotlinx-serialization 由它 api 透出）。

## 1. 包结构总览

```
me.rerere.ai/
├── core/       MessageRole · Reasoning(ReasoningLevel) · Tool/InputSchema · Usage(TokenUsage)
├── provider/
│   ├── Model.kt            Model/ModelType/Modality/ModelAbility/BuiltInTools
│   ├── ModelContextWindow.kt 上下文窗口发现/解析/格式化
│   ├── Provider.kt         Provider 接口 + 各类 Params/Result
│   ├── ProviderManager.kt  注册表（openai/google/claude）
│   ├── ProviderSetting.kt  三种 Provider 配置 sealed
│   └── providers/
│       ├── ProviderMessageUtils.kt   消息分组公共工具
│       ├── openai/    OpenAIProvider · OpenAIImpl · ChatCompletionsAPI(812行) · ResponseAPI(726行) + 两个 StreamDecoder
│       ├── claude/    ClaudeProvider(930行) · ClaudeStreamDecoder
│       ├── google/    GoogleProvider(777行) · GoogleStreamDecoder · vertex/ServiceAccountTokenProvider
│       └── stream/    SseEvent · StreamChunkDecoder（协议→StreamChunk 的有状态解码器接口）
├── reasoning/  ReasoningEffortMappings.kt（思考深度→effort 值集中映射表）
├── registry/   ModelDsl.kt · ModelRegistry.kt(722行，token 匹配 DSL 定义模型能力)
├── ui/         Message(UIMessage+limitContext+迁移函数) · UIMessagePart · StreamChunk ·
│               StreamChunkHandler(流合并器) · MessageMetadata(PartMetadata 类型安全schema) ·
│               UIMessageAnnotation · Image(ImageGenerationItem/ImageGenSize)
└── util/       Json(JsonInstant) · SSE · Request · ErrorParser · FileEncoder(EXIF变换) · KeyRoulette(API key轮换) · Serializer
```

测试（`ai/src/test/java/...`）：请求构造（Claude prompt cache / Google / Moonshot / ResponseApi）、流解码、`StreamTraceReplayTest`（回放 trace-cli 录制的真实 SSE）、ToolApprovalState、UIMessagePart 序列化、MessageContextLimit、FileEncoder EXIF 等。

## 2. UIMessage（ui/Message.kt）

平台无关消息抽象，**具体 Provider 实现负责把它转成各自 API DTO**。

```kotlin
@Serializable data class UIMessage(
    val id: Uuid = random(),
    val role: MessageRole,                    // SYSTEM/USER/ASSISTANT/TOOL (@SerialName 小写)
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation>,
    val createdAt: LocalDateTime,
    val finishedAt: LocalDateTime? = null,
    val generationDurationMs: Long = 0,       // 纯 LLM 吐字时长累计(agentic 多轮累加, 不含工具间隙); 0=未知
    val modelId: Uuid? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null,          // 消息级翻译结果字段
)
```

辅助函数：
- `summaryAsText/toText/getTools()/isValidToUpload()/hasPart<P>()/hasBase64Part()`
- `finishReasoning()`：给未结束的 Reasoning part 补 finishedAt
- `finishPendingTools(transform)`：对未执行工具应用变换（如标 denied）并收尾
- **`List<UIMessage>.limitContext(limit)`**：阶梯式(滞回)条数截断。截断点只在越过 limit 时前进一大步（保留 `[limit*0.5, limit)` 条），使请求前缀稳定以命中提示词缓存；`alignContextStart()` 会把起点回退到 USER 边界/已配对工具处，保证不拆散 tool call 与结果

### 旧格式迁移函数（同文件，勿用于新数据）
- `toSortedMessageParts()` @Deprecated：按 Reasoning(-1)/中段(0)/媒体(+1) 排序
- `migrateToolParts/migrateToolMessages/migrateToolNodes(...)`：把旧 ToolCall/ToolResult part 和 TOOL 角色消息合并进统一的 `UIMessagePart.Tool`（DB Migration_15_16 调用）

## 3. UIMessagePart（ui/UIMessagePart.kt）

```kotlin
@Serializable sealed class UIMessagePart { abstract val metadata: JsonObject? }
```

| 子类 | @SerialName | 关键字段 | 说明 |
|---|---|---|---|
| Text | `text` | text | 正文 |
| Image | `image` | url（本地 uri 或 data:） | |
| Video/Audio | `video`/`audio` | url | |
| Document | `document` | url/fileName/mime | 文档附件（发送前由 DocumentAsPromptTransformer 解析或原样上传） |
| Reasoning | `reasoning` | reasoning/createdAt/finishedAt/reasoningType(`reasoning_text`\|`summary_text`) | 思维链 |
| Search | `search` | — | **Deprecated**（兼容旧序列化保留） |
| ToolCall | `tool_call` | — | **Deprecated** → 合并进 Tool |
| ToolResult | `tool_result` | — | **Deprecated** → 合并进 Tool |
| ServerTool | `server_tool` | toolCallId/toolName/input/output/status(IN_PROGRESS/COMPLETED/FAILED) | 服务商服务端工具(gemini search 等)，不参与客户端审批 |
| **Tool** | `tool` | toolCallId/toolName/**input: String(JSON)**/**output: List<UIMessagePart>**/approvalState/metadata | 客户端工具统一形态 |

Tool 的派生属性：`isExecuted`(output 非空)、`isPending`、`canResumeExecution`；`inputAsJson()`；`merge(other)`（流式拼接 name/input/output）。

**ToolApprovalState**（同文件）：`Auto` / `Pending` / `Approved` / `Denied(reason)` / `Answered(answer)`。
`canResumeToolExecution()`: Approved/Denied/Answered → true（生成恢复时可直接处理）；Auto/Pending → false。

## 4. PartMetadata（ui/MessageMetadata.kt）——metadata 的类型安全 schema

metadata 在序列化层始终是 JsonObject，读写经编译期类型（`part.metadataAs<T>()` / `x.toMetadata()`）。json 配置 ignoreUnknownKeys + explicitNulls=false，因此不同 provider 的 metadata 互不干扰：

| 类型 | 字段 | 用途 |
|---|---|---|
| `ClaudeReasoningMetadata` | signature | thinking block 回传必须携带 |
| `OpenAIReasoningMetadata` | reasoning_id, encrypted_content | Responses API reasoning item 回传 |
| `ServerToolMetadata` | protocol(openai_responses\|anthropic_messages), call/callIndex/result/resultIndex | 原样回传服务端工具原始块 |
| `OpenRouterReasoningMetadata` | reasoning_details | 工具续轮回传 |
| `GoogleThoughtMetadata` | thoughtSignature | Gemini functionCall/inlineData 签名 |
| `DiffMetadata` | diff | workspace_edit_file 输出的 unified diff（仅供 UI 渲染，不发 API） |

## 5. TokenUsage（core/Usage.kt）

`promptTokens/completionTokens/cachedTokens/totalTokens`；`merge(other)` 语义：新值 >0 则取新值，totalTokens=二者之和（多轮 agentic 循环里逐次覆盖式更新）。

## 6. ReasoningLevel（core/Reasoning.kt）

```
OFF(0,"none") AUTO(-1,"auto") LOW(1_000,"low") MEDIUM(2_000) HIGH(8_000)
XHIGH(16_000,"xhigh") MAX(32_000,"max")
```
`fromBudgetTokens()` 反查最近档位。UI 的 ReasoningPicker 展示 7 档。

## 7. Tool 定义（core/Tool.kt）

```kotlin
@Serializable data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (Model, List<UIMessage>) -> String = {_,_->""}, // 注入 system 的工具说明
    val needsApproval: (JsonElement) -> Boolean = { false },          // 按参数决定是否要审批
    val execute: suspend (JsonElement) -> List<UIMessagePart>,        // 异常会被 GenerationHandler 捕获转 error JSON
)
@Serializable sealed class InputSchema { @SerialName("object") data class Obj(properties, required) }
```

## 8. Provider 抽象（provider/Provider.kt）

```kotlin
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>
    suspend fun getBalance(providerSetting: T): String        // 默认 "TODO"
    suspend fun generateText(setting, messages, params): TextGenerationResult
    suspend fun streamText(setting, messages, params): Flow<StreamChunk>
    suspend fun generateEmbedding(...)   // 默认 error()
    suspend fun generateImage(...): Flow<ImageGenerationItem>  // 默认 error()
    suspend fun editImage(...)
}
```
- 无状态设计：调用时把 ProviderSetting 当参数传入
- `TextGenerationParams(model, temperature?, topP?, maxTokens?, tools, reasoningLevel, customHeaders, customBody)`
- `ImageGenerationParams(prompt, numOfImages, size, partialImages, ...)`；Embedding 同理
- `CustomHeader(name,value)` / `CustomBody(key,JsonElement)`：模型/助手级自定义注入

### ProviderSetting（provider/ProviderSetting.kt）sealed 三型

| 型 | 特有配置 |
|---|---|
| `OpenAI`(@SerialName "openai") | apiKey, baseUrl(默认 https://api.openai.com/v1), chatCompletionsPath(/chat/completions), **useResponseApi**(切 /responses), includeHistoryReasoning |
| `Google`("google") | apiKey, baseUrl(v1beta), vertexAI/useServiceAccount/privateKey/serviceAccountEmail/location/projectId |
| `Claude`("claude") | apiKey, baseUrl(v1), promptCaching, promptCacheTtl(FIVE_MINUTES/ONE_HOUR→cache_control ttl) |

公共字段：id/enabled/name/models/balanceOption(BalanceOption: enabled/apiPath `/credits`/resultPath `data.total_usage`)/builtIn/description(@Composable, @Transient)。模型增删改移返回 copy。

### Model（provider/Model.kt)

```kotlin
@Serializable data class Model(
    modelId, displayName, id: Uuid,
    type: ModelType{CHAT,IMAGE,EMBEDDING},
    customHeaders/customBodies,
    inputModalities/outputModalities: List<Modality{TEXT,IMAGE}>,
    abilities: List<ModelAbility{TOOL,REASONING}>,
    tools: Set<BuiltInTools{Search,UrlContext,ImageGeneration}>,  // 服务商内置工具
    providerOverwrite: ProviderSetting?,     // 单模型覆盖供应商连接设置(如 OpenRouter 定向)
    reasoningEffortMap: Map<ReasoningLevel,String>,  // 用户自定义思考映射(优先于内置表)
    contextWindowTokens: Int?,               // null=未知
)
```

**ModelContextWindow.kt**：从各家 listModels 响应 JSON 里探测上下文窗口（15 个候选字段名 × 6 个容器对象），带 OpenAI/Google/Anthropic 已知型号兜底表；`parseContextWindowTokens("32K"/"1.5M")` 解析紧凑写法（上限 10M）；`mergeDiscoveredContextWindows()` 只补空缺不覆盖用户值。

## 9. 流事件体系

### SseEvent + StreamChunkDecoder（provider/stream/）
```kotlin
interface StreamChunkDecoder {
    fun accept(event: SseEvent): DecodeResult   // 解析失败抛异常; completed=true 表示协议已结束可主动断开
    fun onClosed(): List<StreamChunk>           // SSE 正常关闭收尾(幂等)
}
```
每条响应流必须创建独立实例（有状态）。

### StreamChunk（ui/StreamChunk.kt）——Provider 无关的通用流事件

| 组 | 事件 |
|---|---|
| 文本 | TextStart/TextDelta(id,text)/TextEnd |
| 推理 | ReasoningStart/Delta/End（含 reasoningType、metadata） |
| 客户端工具 | ToolCallStart(toolName)/ToolCallDelta(toolNameDelta,inputDelta)/ToolCallEnd —— 按 id 并行 |
| 服务端工具 | ServerToolStart/InputDelta/InputEnd/End(input,output,status) |
| 图片 | ImageStart(mimeType)/ImageDelta(base64 追加)/**ImageSnapshot(整帧替换)**/ImageEnd —— 支持部分图渐进渲染 |
| 其他 | Annotations(去重追加)、Usage(TokenUsage.merge)、Finish(finishReason,responseId,model) |

### StreamChunkHandler（ui/StreamChunkHandler.kt）——流合并器
- 把 StreamChunk 流合并成一条 UIMessage：Start 时记录 part 下标，Delta 按 id 定位更新；容忍缺 Start 直接来 Delta；图片 Snapshot 替换而非追加；Usage merge；Finish 设 finishedAt + 结束未关闭 reasoning + 清索引 + **累加本轮纯吐字时长到 generationDurationMs**
- 非流式走 `handleTextGenerationResult(result,...)`：追加或 appendMessage 合并（文本/推理拼接，工具按 toolCallId merge）
- ⚠️ 一条并发响应流一个实例，不可复用

## 10. 三家 Provider 实现

| 实现 | 行数 | 要点 |
|---|---|---|
| **OpenAIProvider** | 375 | 双 API 分支：useResponseApi ? ResponseAPI(726行,/responses) : ChatCompletionsAPI(812行)；listModels/balance(/credits JSON path)/embedding/image(edit)；下载 URL 图转 base64 |
| **ClaudeProvider** | 930 | Messages API；prompt caching（system/tools 最后消息打 cache_control，TTL 可选 1h）；thinking blocks signature 回传；server_tool(web_search) 映射 |
| **GoogleProvider** | 777 | v1beta generateContent + :streamGenerateContent?alt=sse；vertex AI 服务账号(JWT 换 token, ServiceAccountTokenProvider)；内置工具 google_search/url_context/image_generation 映射 BuiltInTools；thoughtSignature |

各配一个 StreamDecoder 把 wire protocol 翻译成通用 StreamChunk（ChatCompletionsStreamDecoder/ResponseApiStreamDecoder/ClaudeStreamDecoder/GoogleStreamDecoder）。

公共消息转换在 `providers/ProviderMessageUtils.kt`（消息分组校验等）。

## 11. ModelRegistry + ModelDsl（registry/）

用 **token 切分匹配 DSL** 声明已知模型能力（722 行定义）：

```kotlin
private val GPT_5 = defineModel {
    tokens("gpt", "5"); notTokens("gpt", "5", ".")   // 子串 token 序列匹配 + 排除
    visionInput(); toolReasoningAbility(); contextLength(400_000)?  // 可选
}
```
- tokenize：modelId 小写后切成字母/数字/符号 token；`tokens()` 顺序子序列匹配，`notTokens()` 排除，`exact()` 全等(+1000 分)，`tokenRegex()` 正则 token
- `matchScore(modelId): Int?` 返回匹配得分（null 不匹配），供 UI 自动推断新添加模型的 modality/ability/contextWindow
- 覆盖 GPT/o系列/Gemini/Claude/DeepSeek/Qwen/GLM/Kimi/MiMo/Llama 等

## 12. ReasoningEffortMappings（reasoning/）

思考深度 → 供应商 effort 值的集中映射（替代原先散落 4 个 provider 文件的硬编码）：

1. `DEFAULT`：与 ReasoningLevel.effort 一致（none/auto/low/medium/high/xhigh/max）
2. `SCOPE_DEFAULTS`（作用域覆盖）：`openai_chat` OFF→low（completions 不接受 none）；`nvidia` 同；`gemini3` 只接受 low/medium/high（HIGH/XHIGH 收敛 high）
3. `MODEL_OVERRIDES`（模型包含匹配，最高优先）：`deepseek-v4`（none/high/max 语义）；`x-preview-f-free`（opencode Zen 免费端点只接受 low/high/max）
4. 模型自身 `reasoningEffortMap` 在调用方再覆写一层（用户配置 > 模型定向 > 作用域默认 > 全局默认）

## 13. util/

| 文件 | 内容 |
|---|---|
| `Json.kt` | `json`: ignoreUnknownKeys/explicitNulls=false/isLenient/coerceInputValues/encodeDefaults 的共享实例 |
| `KeyRoulette.kt` | 多 apiKey 轮换（default 内存版 / lru(context) 持久版），搜索模块也复用 |
| `FileEncoder.kt` | 附件编码 base64，EXIF 方向修正/压缩（有单测） |
| `ErrorParser.kt` | HTTP 错误体解析为友好信息 |
| `Request.kt`/`SSE.kt` | OkHttp 请求构建与 SSE 读帧封装（供 provider 用） |
| `Serializer.kt` | — |

## 14. 如何接入新的"OpenAI 兼容"站点

通常不需要新 Provider：新建 `ProviderSetting.OpenAI` 改 baseUrl 即可（RikkaHub/OpenRouter/DeepSeek 等都这么接）。特殊参数差异的处理位置：
- 请求体差异 → ChatCompletionsAPI/ResponseAPI 内按 host/modelId 分支（参考 Moonshot/NVIDIA/OpenCode Zen 的既有分支）
- effort 语义差异 → ReasoningEffortMappings.MODEL_OVERRIDES 加一行
- 上下文窗口未知 → ModelContextWindow 兜底表或让用户手填
- UA/header 伪装 → app 层 ClientPresets（见 03 文档 §8）
