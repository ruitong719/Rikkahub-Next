# 07 · 库模块（common / search / speech / document / highlight / material3）

## 1. :common —— 基础工具库

包 `me.rerere.common`。api 暴露 okhttp(+sse/logging)、kotlinx-serialization/coroutines/datetime、commons-text、floatingx(+compose)、quickjs。

| 文件 | 职责 |
|---|---|
| android/Logging.kt | 内存环形日志：`LogEntry` sealed(TextLog(tag,message,time) / RequestLog(url,method,headers,body,response,duration))；`object Logging`：log/logRequest/isRequestLoggingEnabled/setRequestLoggingEnabled/getRecentLogs/getTextLogs/getRequestLogs/clear——LogPage 与 OkHttp RequestLoggingInterceptor 的后端 |
| android/ContextUtil.kt | Context 扩展 |
| cache/CacheStore.kt, LruCache.kt, SingleFileCacheStore.kt, PerKeyFileCacheStore.kt, CacheEntry.kt, KeyCodec.kt, FileIO.kt | 通用缓存设施（内存 LRU + 单文件/每键文件持久缓存），OCR 缓存等复用 |
| http/Json.kt | JsonInstant 共享序列化配置 |
| http/SSE.kt | OkHttp SSE 读帧 |
| http/Request.kt | 请求构建辅助 |
| http/AcceptLang.kt | Accept-Language 头生成（OkHttp 拦截器用） |
| http/JsonExpression.kt | JSON path 表达式取值（余额解析 data.total_usage 等） |
| js/QuickJSFetch.kt | QuickJS 内的 fetch() polyfill（CustomJs 搜索服务跑用户 JS 用） |

⚠️ 无时间工具文件——kotlinx-datetime 仅作依赖透出。

## 2. :search —— 联网搜索 SDK（19 家服务商）

包 `me.rerere.search`，minSdk 23，api jsoup+quickjs，有自己的 values* i18n。

### 抽象（SearchService.kt）
```kotlin
interface SearchService<T : SearchServiceOptions> {
    val name: String
    fun parameters(options: T): InputSchema?          // search_web 工具参数 schema
    fun scrapingParameters(options: T): InputSchema?  // scrape_web 参数(不支持则 null)
    @Composable fun Description()
    suspend fun search(params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: T): Result<SearchResult>
    suspend fun scrape(...): Result<ScrapedResult>
}
```
- companion：`getService(options)` 按 Options 类型映射到各单例 object；`init(client, context)` 注入共享 OkHttpClient(app 的代理/UA 拦截链) + KeyRoulette.lru(API key 轮换持久化)
- 模型：`SearchResult(answer?, items[{title,url,text}], images[])`、`ScrapedResult(urls[{url,content,metadata{title?,description?,language?}}])`、`SearchCommonOptions(resultSize=10)`
- Options sealed 全部 @SerialName 持久化于 settings.search_services

### 服务商清单（19 个 object）

| 实现 | 鉴权/要点 |
|---|---|
| BingSearchService(bing_local) | 免 key，本地抓取解析 |
| RikkaHubSearchService | 官方聚合服务，depth standard/advanced |
| ZhipuSearchService | apiKey |
| DoubaoSearchService | apiKey + mode GLOBAL/CUSTOM |
| TavilySearchService | apiKey + depth advanced |
| ExaSearchService | apiKey |
| SearXNGService | 自建 url + engines + language + basic auth |
| LinkUpService | apiKey + depth standard |
| BraveSearchService | apiKey |
| MetasoSearchService(秘塔) | apiKey |
| OllamaSearchService | 本地 Ollama |
| PerplexitySearchService | apiKey + maxTokens/maxTokensPerPage |
| FirecrawlSearchService | apiKey（偏抓取） |
| JinaSearchService | apiKey；searchUrl 默认 s.jina.ai、scrapeUrl r.jina.ai 可覆盖 |
| BochaSearchService(博查) | apiKey + summary 开关 |
| GrokSearchService | customUrl 默认 api.x.ai/v1/responses，model grok-4-1-fast-non-reasoning，可配 systemPrompt |
| TinyfishSearchService / SerperSearchService | apiKey |
| CustomJsSearchService | 用户自写 JS：search(query,resultSize)/scrape(urls)，QuickJS 环境提供 fetch polyfill(common/js/QuickJSFetch)；默认脚本模板内置 |

新增服务商步骤：实现 object + Options 子类(@SerialName) + getService 映射 + TYPES 显示名 map + 设置页 UI。

测试：DoubaoSearchServiceTest。

## 3. :speech —— TTS + ASR

包 `me.rerere.tts` / `me.rerere.asr`。media3 exoplayer/ui/common。

### TTS
```kotlin
interface TTSProvider<T : TTSProviderSetting> {
    fun generateSpeech(context, providerSetting, request: TTSRequest): Flow<AudioChunk>
    val promptGuidance: String get() = ""   // 语气标记指引, 注入 text_to_speech 工具 systemPrompt
}
```
promptGuidance 两条约束（注释明示）：标记只进工具参数不进正文；避免会被 stripMarkdown/TextChunker 清洗掉的符号与标点。

实现（11 云 + 系统）：OpenAI(gpt-4o-mini-tts/alloy)、Gemini(gemini-2.5-flash-preview-tts/Kore)、SystemTTS(speechRate/pitch)、MiniMax(speech-2.6-turbo)、Qwen(qwen3-tts-flash/dashscope)、Groq(orpheus-v1-english)、XAI(eve)、MiMo(mimo-v2.5-tts/xiaomimimo)、Step、ElevenLabs、FishAudio。

controller 层：TtsController(对外门面)/TtsSynthesizer(合成调度)/TextChunker(分句切块)/AudioPlayer(media3 ExoPlayer 封装)；model/: TTSRequest/TTSResponse/PlaybackState。
TTSManager(Koin single in app)：按 settings.tts_providers 注册管理全部 Provider。

### ASR
```kotlin
interface ASRController {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop(); fun dispose()
}
```
实现 providers/：OpenAIRealtimeASRController(WS)、DashScopeASRController、VolcengineASRController、MiMoASRController(HTTP)、StepASRController(HTTP SSE)。AudioAmplitude.kt 录音波形振幅。ASRProviderSetting sealed 对应五家。

UI 接入：ui/hooks/ASR·TTS.kt 的 remember*State 按 selectedASR/TTSProviderId 构建 DisposableEffect 重建。

## 4. :document —— 文档解析

包 `com.artifex.mupdf.fitz`(vendored MuPDF Java bindings 全量) + `me.rerere.document`：

| Parser | 输入输出 |
|---|---|
| PdfParser.parserPdf(file): String | MuPDF 提取文本（jniLibs libmupdf_java.so arm64+x86_64） |
| DocxParser.parse(file): String | zip→document.xml→Markdown 化文本 |
| PptxParser.parse(file): String | slide XMLs(+备注 notesXml)→Markdown 化 |
| EpubParser.parse(file): String | OPF manifest 顺序→XHTML→Markdown 化 |

**四个 Parser 均直接返回 Markdown 化字符串**，无中间结构化模型。consumer-rules.pro keep `com.artifex.mupdf.fitz.**`（JNI 按名绑定不可混淆）。由 DocumentAsPromptTransformer 在发送前调用。

## 5. :highlight —— 代码高亮（hljs Kotlin 移植）

包 `me.rerere.highlight`。**无运行时依赖的自研实现**：highlight.js 11.11.1 的语法+引擎整体 Kotlin 移植。

### core/
- Mode.kt / ModeCompiler.kt（hljs mode 编译）/ MultiRegex.kt（多正则并行匹配核心）/ Regexes.kt / Keywords.kt / CommonModes.kt / TokenEmitter.kt / HighlightEngine.kt（入口，MAX_CODE_LENGTH=4096 截断）

### languages/
30 种语言 Kotlin 定义：bash c cmake cpp csharp css dart diff dockerfile glsl go ini java javascript json kotlin latex lua markdown php powershell properties python ruby rust sql swift typescript xml yaml（JavaShared/CssShared 共享片段）。Languages.kt 汇总 builtinLanguages()。

### API
- `class CodeHighlighter { engine = HighlightEngine(builtinLanguages()) }`
- `val LocalCodeHighlighter = staticCompositionLocalOf { CodeHighlighter() }`
- `CodeHighlightText(code, language, colors: HighlightTextColorPalette, ...)` Composable → AnnotatedString
- HighlightToken / HighlightStyle 数据模型

### 测试（golden fixture）
HljsFixtures.kt + LanguageFixtureTest.kt：对拍真 hljs 输出保证行为一致；core 有 HighlightEngineTest/RegexesTest。

## 6. :material3 —— 颜色桥接

仅一个主文件 `DynamicSchemeExt.kt`：`fun DynamicScheme.toColorScheme(): ColorScheme`——把 material-color-utilities 的 DynamicScheme（isDark 分支）逐角色映射到 Compose ColorScheme（primary/onPrimary/.../surfaceContainer 系列）。

git submodule `.gitmodules` 引入 material-color-utilities 的 kotlin 源码目录加入本模块 sourceSets 编译（CI checkout 带 submodules recursive）。用途：自定义主题(CustomTheme)从种子色推导完整配色方案。
