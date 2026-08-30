# 上游同步对账（Upstream Sync）

记录本 fork 与上游 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 每个 commit 的合并情况。
功能合入过程、review 细节与构建验证见 [CHANGES.md](CHANGES.md)。

## 当前状态

- **基线**：merge-base `0c52b62b`（上游 2.4.9，v1.00 迭代时整体 merge）
- **已同步至**：`9365c297`（2026-08-30）—— 上游 `master` 全部提交处理完毕

## 全量对账表（`c16fe44f..9365c297`，2026-08-30）

| # | 上游 commit | 主题 | 处理 | fork 落点 |
|---|---|---|---|---|
| 49 | `5b58c957` | chore: 注册 glm 5.3 模型 | 已合入 | `d4aaa74c`，零冲突 |
| 50 | `170a612e` | chore: bump to 2.4.13 | **跳过（语义）** | fork 自行 bump：183 / 2.4.15（见下三连 bump 说明） |
| 51 | `53dc38b8` | chore: 移除无用 mcp transport 代码 | 已合入 | `de3a5551`，fork 中两文件本就是整文件注释的死代码，删除安全 |
| 52 | `ca31612d` | fix: 改进输入栏折叠动画和易用性 | **跳过** | 见批次说明 B：上游 7aa909b8 自己又回滚了动画，净效果是放弃 IME 自适应（上游 #29/#37 行为被整体回退）；fork 保留自研键盘自适应+队列角标，不跟 |
| 53 | `8f4f1286` | refactor(ai): extract translation handler | 已合入（适配） | `6a5c1bc5`，GenerationHandler import 区双保留冲突；translateText 摘入新 TranslationHandler.kt（与上游逐字节一致） |
| 54 | `7714f2bc` | feat: 支持 chatbox v2 备份导入 | 已合入（适配） | `b2ea6e1b`，ChatboxImporter 重写+测试+ImportExportTab 自动；BackupVM 人工适配 imports + 函数体自动 |
| 55 | `0533acde` | chore: 移除默认 rikkahub 提供商 | 已合入 | `cfb361f0`，零冲突 |
| 56 | `979cd169` | chore: 调整 kimi icon | 已合入 | `a922e10b`，纯资源 |
| 57 | `6e26affe` | feat: mimo tts 支持快速选择音色 | 已合入 | `7fadb340`，零冲突 |
| 58 | `da5fd77b` | chore: bump to 2.4.14 | **跳过（语义）** | 同上，并入 183 / 2.4.15 |
| 59 | `8a9f4f0f` | chore: 新增抖音群加群信息 | **跳过** | 用户决策：fork 设置页有自研布局（90 行漂移），低价值不加 |
| 60 | `9687f97a` | build: 北京时间下午五点也触发自动构建 | 已合入 | `ad1be4a3`，零冲突 |
| 61 | `eba2e96c` | chore: update dependencies | 已合入 | `aaecf790`，material3 alpha27 / nav3Core 1.1.7 / okhttp 5.5.0 / baselineprofile rc02 / sqlite-vector 1.0.0；**删除 nav2**（全仓无引用已验证）；不动 fork 自选 huge-icons/haze |
| 62 | `7aa909b8` | chore: 回滚输入栏折叠功能 | **跳过** | 见 #52 说明（同一决策） |
| 63 | `5b890d22` | fix(ai): remove vision support from hy4 | 已合入 | `13d0f5ba`，单行；HY4 注册保留仅去 visionInput |
| 64 | `321443d8` | fix(chat): keep actions visible for tool-only messages | 已合入 | `3d8caa6c`，含 MessageVisibilityTest |
| 65 | `ecc6d910` | chore: 简化 claude api skill | **跳过** | fork 无 `.agents`（`15525bea` 已整体删除），文件不存在，空操作 |
| 66 | `b6df5f04` | refactor: 改进 oauth 流程 | 已合入（适配） | `fe0b96c8`，新增 `:oauth` 模块（loopback 回调服务器/PKCE/前台服务保活）+ McpOAuthDiscoveryClient RFC 9728 发现；4 处冲突均为删除/清理类（RouteActivity 因行尾符 CRLF vs LF 整文件伪冲突，手工落 1 行删除） |
| 67 | `9851d037` | fix: gemini 允许服务端工具和客户端工具同时使用 | 已合入 | `f6efc376`，零冲突 |
| 68 | `7da69770` | feat(settings): add scoped file cleanup | 已合入（适配） | `f055aad1`，FilesManager/SettingFilesPage 零漂移自动；en/zh strings 自动，ja/ko-rKR/ru/zh-rTW 按 fork 惯例保持删除 |
| 69 | `5662945c` | chore: bump to 2.4.15 | **跳过（语义）** | 同上，并入 183 / 2.4.15（`b449f0d9`） |
| 70 | `5403bc96` | fix: 改进 TTS 稳定性，支持自动重试 | 已合入 | `95f4762c`，零冲突 |
| 71 | `2f05019b` | chore: 改进 tts 重试判断 | 已合入（适配） | `e7e3d746`，TTSProviderException+statusCode 语义（配套自动重试）；Groq/Step 两处冲突解为"fork body 非空风格 + 上游异常语义"，与 ElevenLabs 等自动合并结果一致 |
| 72 | `ef94834a` | chore: mcp 仅使用 client sdk | 已合入 | `895e9c22`，kotlin-sdk → kotlin-sdk-client（fork 的 sdk.types/shared 导入与上游一致，client 工件包含这些包） |
| 73 | `8ea375a9` | fix: 修复 cloudflare mcp 没有匹配到 oauth 的问题 | 已合入 | `1f220993`，位于 b6df5f04 **之后**（针对重构后 coordinator 的修复）；先合 #66 再合本 commit 即零冲突 |
| 74 | `f6a5330f` | feat: trace-cli 支持 Google Interactions API | 已合入 | `a1c78e93`，trace-cli + 录制 fixtures + 测试 |
| 75 | `1231b8af` | test: 修正加密 reasoning 的请求断言 | 已合入 | `b452d9f1`，fork 主代码已支持 encrypted_content |
| 76 | `0651cad9` | fix: round bubble opacity percentage | 已合入 | `ac987a9a`，单行 |
| 77 | `f7869e35` | fix: 修复 gemini mix server/client tool 下的错误 | 已合入 | `03ea4838`，三路合并零冲突（fork 的模型发现/ReasoningEffortMappings 私有代码自动保留），Googlestream 等 4 文件与上游最终版一致 |
| 78 | `1b9dd092` | fix: 移除硅基流动余额查询 | 已合入 | `89cc2d52` |
| 79 | `5c217d2e` | fix: 默认关闭 HTML/SVG 内嵌预览 | 已合入 | `673b58ae`，默认值变化（行为变更） |
| 80 | `9365c297` | fix: 支持配置快速模型思考级别，并移除单独的标题和建议模型配置 | **部分合入** | `c063279e`（fork 适配 commit），见批次说明 F |

## 首次全量对账表（`0c52b62b..c167c70e`）

| # | 上游 commit | 主题 | 处理 | fork 落点 |
|---|---|---|---|---|
| 1 | `d1e8effc` | style(ui): 移除推理等级选择器底部刻度 | **跳过** | fork `a47fab90` 方向相反（显示全部 7 档标签并修复空标签），保留 fork 实现 |
| 2 | `de888df2` | fix(asr): 修复 DashScope 语音识别无文本输出 | 已合入 | `b9aaaf6f` |
| 3 | `97df86ec` | fix(workspace): 修复 SAF 文件存在性判断 | 已合入 | `96f70a83` |
| 4 | `bca21d4d` | fix: 修复 .agc 文件支持 | 已合入 | `c94e97ad` |
| 5 | `c88822d6` | feat: 支持豆包搜索 | 已合入 | `3ac6896d` |
| 6 | `82758c36` | fix(chat): 启用英文句首自动大写 | 已合入 | `98c4ae85` |
| 7 | `dca7f01c` | feat: 正则支持排序 | 已合入 | `ae21cb95` |
| 8 | `693c2ce5` | chore: bump to 2.4.10 | 已合入 | `da3ca8b1`（fork 为 versionCode 178 / 2.4.10） |
| 9 | `3b4b80a4` | fix: 修复混淆破坏 auth/jwt 的问题 | 已合入 | `bf9bf81e` |
| 10 | `85402745` | fix(thinking): 忽略正文中内联 think 标签 | 已合入 | `efdbe8d2`，零冲突；附 `ThinkTagTransformerTest` 8 用例 |
| 11 | `adf333ec` | feat(assistant): 上下文条数改数字输入 | **不直接合入** | 字段已被滚动摘要功能整体移除，上游改的是不存在的 UI；交互模式已适配到 Token 阈值输入框 → `d6629e20` |
| 12 | `6b37912f` | docs: 移除 claude.md | 跳过 | fork 已在 `15525bea` 删除根目录 CLAUDE.md，空操作 |
| 13 | `f167a855` | chore: 适配 deepseek-v4-flash-vision-exp 能力 | 已合入 | `00234b94`，零冲突 |
| 14 | `8b3a1f84` | feat: 适配小米 MiMo 思考参数（#1751） | 已合入 | `3a52630c` |
| 15 | `91b81fef` | chore: 更新模型图标（gemma/kimi/qwen） | 已合入 | `6e3993dd`，零冲突 |
| 16 | `c167c70e` | feat: ModelRegistry 支持注册模型上下文长度 | 已合入 | `a882ce60` |
| 17 | `7a93c92a` | feat(backup): 本地备份支持选择内容并调整为首页 tab | 已合入（适配） | `a8ed4ee7` |
| 18 | `f557cef5` | feat(backup): 本地备份导入前增加覆盖确认 | 已合入 | `ab127800` |
| 19 | `54b3ba79` | fix: 修复更新检查频繁调用的问题 | 已合入（适配） | `9a57d7b9` |
| 20 | `02a0c81c` | chore: 更新依赖（huge-icons 1.4 / haze beta01） | 已合入 | `9e9afd94` |
| 21 | `986b9c39` | feat: 新增网络配置页 支持配置 user agent 和 代理 | 已合入 | `f55e67ea` |
| 22 | `3509406b` | fix: 代理测试消息改用 LocalResources | 已合入 | `0d621367` |
| 23 | `b270766f` | chore: bump to 2.4.11 | 已合入（语义） | fork 自行 bump：179 / 2.4.11 |

## 全量对账表（`b270766f..e8293d35`，2026-08-25）

| # | 上游 commit | 主题 | 处理 | fork 落点 |
|---|---|---|---|---|
| 24 | `3a533a6a` | feat: 工作区终端后台运行 + 多Tab | 已合入（适配） | `cbc7cb18`，DI 三处双保留冲突 |
| 25 | `f4508dfa` | chore: 更新 proot lib | 已合入 | `350f6b8a`，纯二进制替换 |
| 26 | `e6ebcf59` | feat: OpenRouter 加 session_id 头（#1760） | 已合入（适配） | `5e17d313`，GenerationHandler 两处参数错位冲突 |
| 27 | `54030ec4` | chore: SnakeYAML 解析 skill | 已合入 | `a130e226`，零冲突 |
| 28 | `f86d6e82` | fix: 键盘弹出时 ChatInput 保持圆角 | 已合入 | `45b6a770`，零冲突 |
| 29 | `e6e0dfd4` | feat: 键盘弹出时收起工具栏并上移发送按钮 | 已合入（手工移植） | `b1d379b1`，单文件 +298/-249 |
| 30 | `1e3351f7` | chore: 初始化 videogen 模块 | 已合入 | `01d4bfb3` |
| 31 | `96fbe7e3` | chore: 初始化 video 生成 api 层 | 已合入 | `b7aaab1e`（依赖 #30 先行） |
| 32 | `4fcb590a` | fix(setting): 测试连接对话框补全本地化 | 已合入（适配） | `6d4828ac`，values/strings.xml 双追加冲突 |
| 33 | `6c6a8458` | fix: fork 会话继承 folder id / workspace cwd | 已合入（适配） | `0da70328`，含 createForkConversation 助手函数与 JVM 测试 |
| 34 | `aab5026e` | fix(notification): Live Update 胶囊图标（#1782） | 已合入 | `d092f176` |
| 35 | `0c056d08` | chore: bump to 2.4.12 | 已合入（语义） | fork 自行 bump：180 / 2.4.12 |
| 36 | `e8293d35` | fix(ai): 规范化空 tool schema（#1781） | 已合入 | `4add3eea`，含 ChatCompletionsToolSchemaTest |

## 全量对账表（`e8293d35..c16fe44f`，2026-08-26）

| # | 上游 commit | 主题 | 处理 | fork 落点 |
|---|---|---|---|---|
| 37 | `daae3749` | fix: chat input IME 动画稳定性（imeAnimationTarget） | 已合入（适配） | `6259531b`，唯一冲突在 trailingContent：保留 fork 的 Row/附件入口/队列角标，取上游变量与动画改动 |
| 38 | `fa0305ba` | fix: 模型搜索 IME 打开自动关闭 | 已合入 | `cabe90b7`，**依赖 #37 先行**（新代码插在其 imeTargetVisible 之后）；ModelSelector 拆分对其他 4 个调用方透明 |
| 39 | `0826a3b9` | feat: Qwen 3.8 模型匹配 | 已合入 | `7add950b`，零冲突；fork 此前只有 QWEN_3_8_MAX |
| 40 | `86c85236` | chore: hy4 模型注册 | 已合入 | `ee8ba223`，零冲突 |
| 41 | `bce78766` | feat(workspace): 终端 Tab 关闭确认 | 已合入（适配） | `690675b9`，kt+en+zh 干净；git rm 掉 merge 复活的 ja/ko-rKR/ru/zh-rTW 四个已删 locale |
| 42 | `e9b98a4b` | fix: qwen tts 音色拼写 Serena | 已合入 | `1839b5d9`，单行；必须先于 #43 |
| 43 | `03534d14` | feat: qwen audio 3.0 tts | 已合入 | `409f7f96`，4 文件与上游改前逐字节一致故干净；⚠️ 上游有意废弃 qwen3-tts-\*，旧配置会 require 报错提示迁移 |
| 44 | `b62d29d1` | fix(workspace): 非交互命令 stdin 立即 EOF（#1605） | 已合入 | `2ee17849`，零冲突（fork 的 onOutput 分歧区不重叠）；`:workspace:testDebugUnitTest` 本地实跑通过 |
| 45 | `942d0d28` | fix: 合成消息不参与消息模版 | 已合入（适配） | `0e14024f`，cherry-pick -n 后 git rm 剔除被静默复活的 PromptInjectionTransformer(+Test)——fork 无该类型必编译失败；GenHandler 冲突取上游标记行保留 fork slicedMessages |
| 46 | `2dc50126` | docs(contributing) | **跳过** | 上游贡献政策（不收功能 PR）与 fork 定位相反；README 是 fork 重写版无对应段落 |
| 47 | `c62f1eb1` | chore: 前台服务避免后台生成断开 | **跳过** | fork 悬浮球 FloatingBubbleService 即常驻 specialUse 前台服务，进程保活等价（保护是进程优先级而非通知本身）；再挂一条生成常驻通知属重复。若未来做"无悬浮球用户的保活"再回搬 |
| 48 | `c16fe44f` | feat: 自动重试 | **部分合入（路径 A）** | 见下方批次说明 |

## #49–#80 批次说明（2026-08-30）

- **版本三连 bump（#50/#58/#69）**：上游 2.4.13/2.4.14/2.4.15 三次 bump 均不照抄，fork 合并完成
  后一次到位：`b449f0d9`（versionCode 183 / versionName 2.4.15），延续 da3ca8b1/dac3e0db 惯例
- **B（#52/#62 输入栏）**：上游 `ca31612d` 引入折叠动画并大改 ChatInput/ChatPage（附件选择器
  抽成新文件 ChatAttachmentPicker.kt +195），`7aa909b8` 又把折叠动画连同 #37 的
  imeAnimationTarget/工具栏收起/SendButton 上移**整体回滚**。净效果 = 放弃键盘自适应行为 +
  附件选择器重构。fork 该区域有 #29/#37 深度定制（队列角标、发送按钮、全屏按钮等，
  ChatInput 漂移 300+ 行），完整合并将**回退 fork 用户已有的 IME 自适应能力**且收益是纯结构
  重构 → 两个都跳过，保留 fork 现状。若未来想移植附件选择器重构可单独做
- **C（#53/#71/#77）**：
  - #53 翻译提取：translateText 从 GenerationHandler 摘出到新 `TranslationHandler.kt`
    （新文件与上游逐字节一致）；fork 冲突仅在 GenerationHandler imports 区（fork 的
    MemoryRepository/网络异常 imports 双保留）与 ChatService imports 区（SubAgent imports +
    TranslationHandler 共存）。AppModule/DataSourceModule/TranslatorVM 三路自动合并
  - #71 TTS 重试判断：`TTSProviderException`（含 statusCode）替代裸 Exception——与 #70 的
    自动重试配套；Groq/Step 两处解为"fork 的 body 非空风格 + 上游异常语义"，与 ElevenLabs
    等自动合并结果一致（12 文件 87+/12- 与上游 stat 完全相同）
  - #77 gemini mix 修复：三路合并零冲突（fork 的模型上下文自动发现/ReasoningEffortMappings
    等私有改动在 GoogleProvider 与上游 hunk 不重叠，自动保留）；GoogleStreamDecoder/
    MessageMetadata/StreamChunk 与上游最终版逐字节一致，StreamChunkHandler 35 行差异为
    fork #48 重连环私有
- **E（#66/#73 OAuth）**：新 `:oauth` 模块（OAuthLoopbackCallbackServer 回环回调 +
  OAuthHttpClient PKCE + OAuthCallbackForegroundService 保活 + CustomTabs launcher）+
  McpOAuthDiscoveryClient（资源发现：401 resource_metadata → RFC 9728 well-known）；
  删除 deep-link 回调 Activity/AppEvent.McpOAuthCallback/旧 McpOAuthClient。30 文件中
  24 个自动；4 处冲突全部为删除/清理类。**RouteActivity 因上游文件为 CRLF 行尾、fork 为 LF
  导致整文件伪冲突**，取 fork 版后手工删 1 行（`is AppEvent.McpOAuthCallback` 分支）恢复
  ✓⚠️ 首次讲解时曾误称 #73 的 cloudflare 修复"包含在 #66 重构版内"——实际 #73 在 #66
  **之后**、针对重构后 coordinator；正确顺序是先 #66 后 #73，后者即零冲突（`1f220993`），
  合并后 coordinator 与上游最终版逐字节一致
- **D（#54/#68）**：#54 ChatboxImporter 整体重写（v1+v2 ZIP 统一解析、图片资源经
  `saveUploadFromBytes` 落盘、`shouldImportConversation` 去重回调）—— importer/main
  代码与 fork 零漂移自动落入，新增 ChatboxImporterTest；唯一人工 = BackupVM imports +
  `settingsStore.update` lambda 化（函数体三路自动合并）。fork 无 S3 备份，与本次无交集
  #68 scoped file cleanup：FilesManager/SettingFilesPage 零漂移自动；en/zh strings 自动，
  删除的 4 个 locale（ja/ko-rKR/ru/zh-rTW）按 fork 惯例保持删除
- **F（#80 快速模型思考级别）**：**部分合入**（`c063279e` 为 fork 自适配 commit，非 cherry-pick）：
  采纳——Settings 新增 `fastModelReasoningLevel`（默认 AUTO）+ DataStore key 持久化 +
  SettingModelPage 快速模型块增加 ReasoningButton 思考级别 + 标题/建议后台生成参数传入该级别；
  保留——`TITLE_MODEL`/`SUGGESTION_MODEL` key 与 titleModelId/suggestionModelId 字段、
  设置页标题/建议模型选择块、ErrorCard 的 CheckTitleModelSettings 文案（用户决策：标题/
  建议模型都不移除，上游删除部分全部不采纳）
- **其他跳过**：#59 抖音群卡片（用户决策，fork 设置页自研布局、低价值）；#65 claude skill
  删除（fork 无 `.agents`，`15525bea` 已整体删除，空操作）
- **验证状态**：全部 26 个 commit 已过 kotlinc 解析级语法检查（64 个变更 .kt 文件，0 语法错误）；
  Gradle 完整类型检查**未能完成**——沙箱到 dl.google.com 不通且缓存只有旧版本构件，
  `eba2e96c` 升级的 baselineprofile-rc02/okhttp-5.5.0/material3-alpha27 等无法在沙箱解析
  （已加 `~/.gradle/init.gradle` 阿里云 google 镜像回退，仍需重试）。**建议真机/CI 编译回归**：
  重点冒烟 = 聊天输入栏行为（未动，应无变化）、OAuth MCP 登录（行为变更：deep-link →
  loopback 回调）、ChatBox v2 导入、设置页快速模型思考级别、TTS 重试

## #37–#48 批次说明（2026-08-26）

- **#48 自动重试走路径 A**：fork 的自动重连环（`enableAutoReconnect` 开关、cause 链判定、
  attemptSnapshot 整轮回滚）在判定精度与请求体防污染上优于上游实现，主循环保持不动；
  从上游摘取三样：① `getProcessingStatusFlow` 改 `getOrCreateSession`（修 UI 观察孤儿
  Flow 的真 bug）；② 5 条网络错误中英文案；③ 下游错误隔离 + 重试原因上屏：
  - `onAutoReconnect` 回调签名扩为 `(attempt, maxAttempts, error)`，ChatService 在发
    StreamReconnectNotice 同时写 `session.processingStatus`（带本地化失败原因）
  - 文案生命周期：新尝试开始即清（GenerationHandler try 顶部）、取消时清
    （CancellationException 分支）、成功后无残留；fork 该管道此前无人写入，
    本次起由这对写/清点全权负责
  - 新增 `StreamChunkHandlingException` 包装流式 collect 内的转换/UI 异常并重抛原始
    cause，不再误触发重连（上游同款思路，非流式路径维持 fork 原状未包）
  - 未采用上游的 responseBaseMessages/预创建 ASSISTANT 设计：fork 的 internalMessages
    循环外构建，请求体本就不受半截回复污染，语义等价且改动面小

## #24–#36 批次说明（2026-08-25）

- **#24 终端后台运行**：上游新增 `WorkspaceTerminalSessionManager`（240 行，
  会话脱离 Compose 组合保活）+ 重写 `WorkspaceTerminalPage`（多 Tab）。fork 该页面
  与基线完全一致可整体落入；DI 三处（AppModule import、ViewModelModule/DetailVM
  构造参数）为双方各加一行的双保留冲突。fork 原有 WorkspaceBgManager 是 AI 后台
  任务体系，与本功能（交互终端会话保活）互补无重叠
- **#26 session_id**：`TextGenerationParams.sessionId` 仅在 OpenRouter 分支注入
  `session_id` 头；GenerationHandler 公开/私有函数签名加 `conversationId` 参数。
  冲突成因是 git 把上游新增行与 fork 已有的 modeInjection/lorebook 行错位配对
- **#29 移植要点**：① `WindowInsets.isImeVisible` 计算可见性；② 工具栏外层
  Row（缩进 20）包进 `AnimatedVisibility(!imeVisible)`（缩进 16，
  expandVertically/shrinkVertically）；③ 抽 `SendButton` 私有 @Composable，签名
  扩展为 `(loading, empty, queuedCount, onClick, onLongClick, modifier)`，保留
  fork 特有的 showInterrupt 逻辑（生成中有输入时仍为发送样式点击入队）、
  `BadgedBox` 队列角标与 `KeepScreenOn`；④ `TextInputRow` 新增 `trailingContent:
  @Composable () -> Unit = {}` 形参，IME 弹出时调用 SendButton；⑤ `trailingIcon`
  槽改为 `Row{ 全屏按钮(if isFocused); trailingContent() }` 包裹
- **#33 fork 会话继承**：fork 原构造只复制 4 个字段，原因是滚动摘要重构已把
  modeInjectionIds/lorebookIds 从 Conversation 模型整体移除。合入时照搬上游
  8 字段版本导致 app 模块编译失败（`No parameter with name 'modeInjectionIds'`，
  由 `:app:compileDebugKotlin` 基线验证抓出），`e07d6dd7` 修正为复制 6 个字段
  （id/assistantId/messageNodes/customSystemPrompt/workspaceCwd/folderId）；
  rollingContextSummary 属会话自身产物，fork 时不应继承，保持不复制

## #17–#21 批次说明（2026-08-23）

- **#17 备份项选择**：fork 无 S3（已删），tab 排列为 本地/WebDAV/提醒；上游新增的
  `localBackupItems` VM 状态移植到 fork 的 `BackupScope` 类型体系
  （`DATABASE`/`ATTACHMENTS`），替换原先写死的 `BackupScope.selectableEntries`
- **#19 更新检查去频**：上游把 `checkUpdate()` 冷流改为 AppScope 内 `Lazily`
  共享 StateFlow；fork 额外有自定义更新地址设置项，故共享流按 `updateUrl`
  `distinctUntilChanged + flatMapLatest` 组装——地址变化自动重新检查，
  其余场景全进程只发一次请求
- **#21 网络配置页**：`NetworkSetting`（userAgent/proxyUrl/proxyUsername/proxyPassword）
  持久化为独立 DataStore key；代理支持 HTTP/SOCKS5 与鉴权，代理设置变化时
  `connectionPool.evictAll()`；UA 注入在共享 OkHttpClient 拦截器，留空回退
  `RikkaHub-Android/<version>`

## 特殊处理说明

### #1 `d1e8effc` — 跳过

上游删除了推理等级选择器底部刻度；fork `a47fab90` 反而利用该区域显示全部 7 个档位标签
（并修复了 MEDIUM/HIGH/XHIGH 显示为空的问题）。两者方向相反，保留 fork 实现。

⚠️ 后续上游再改动 `ReasoningPicker.kt` 时 cherry-pick 会冲突，需手动解冲突。

### #11 `adf333ec` — 不直接合入

上游把"上下文最大条数"滑条改为数字输入；fork 的滚动摘要功能已将 `contextMessageLimit`
字段整体移除（改为 token 阈值），上游改的是 fork 中不存在的 UI。其交互模式
（数字输入 + 失焦校验 + 过小自动重置弹窗）已适配到 fork 的 Token 阈值输入框（`d6629e20`，
支持 `32000` / `32K` / `1.5M` 写法）。若上游后续在该功能上继续迭代，需重新评估而非直接 pick。

### #13–#16 批次（2026-08-21）

按时间顺序逐个 cherry-pick：

- `8b3a1f84` 是唯一自动合并的文件：fork 的 moonshot K2.6 `thinking.keep` 逻辑导致上下文偏移；
  合并后 MiMo 块落在 bigmodel 与 moonshot 分支之间，K2.6 逻辑经人工核对完好
- `c167c70e` 依赖 `f167a855` 先行（要给 DEEPSEEK_V4_FLASH_VISION_EXP 补 `contextLength(1.m)`），
  单独应用会 patch 失败，必须保持顺序
- fork 的"模型上下文窗口自动发现"是运行时 API 发现（`contextWindowTokensOrNull`），
  与注册表新增的静态 `MODEL_CONTEXT_LENGTH` 互补，无重复实现

## 下次同步流程

```bash
# 1. 更新上游引用（upstream = GitHub；local-upstream = 本地 clone，沙箱环境用后者）
git fetch upstream          # 或 git fetch local-upstream

# 2. 对账：'-' 表示已有等价提交，'+' 为待处理
git cherry HEAD upstream/master

# 3. 预检 + 按时间顺序逐个 cherry-pick（注意提交间依赖，如 c167c70e ← f167a855）
git show <sha> | git apply --check -
git cherry-pick <sha>

# 4. 更新本文档对账表与 docs/CHANGES.md 记录
```

## 注意事项

- fork 的 versionCode 自 178 起自行维护，上游 bump 提交只对齐版本语义，不照抄
- 沙箱环境无 Android SDK，cherry-pick 后的验证以 `git apply --check`、
  逐文件人工核对与 JVM 可跑的单测为准，Gradle 构建回归在真机构建时补做

## `git cherry` 假阳性

`git cherry HEAD local-upstream/master` 在本 fork 长期会显示一批 `+`（上游独有），
但**对账表里它们都标着"已合入"**。原因：

- `git cherry` 用 `git patch-id` 做等价判定，对 hunk 上下文行号和 blob hash 敏感
- 本 fork 大量 cherry-pick 都经过冲突解决（双侧各加一行、字符串双追加、
  修复型简化等），导致落点 commit 的 patch hunk 位置偏移 1-2 行、blob hash
  与上游不同
- 但**实际代码内容与上游等价**（用 `sed` 规范化掉 `index` 行与 `@@ -X,Y +A,B @@`
  行号后 `diff` 0 差异），且 `git log --all --grep` 能逐条找到 fork 中的等价提交

**判别规则**：

1. 看对账表"已合入"行的 fork 落点 commit 是否存在 —— 存在即已合入
2. 用 `git log --all --grep="<上游 commit 标题>"` 在 fork 历史里搜同标题
3. 用规范化 hunk 后的 `diff` 验证改动内容等价（见下面脚本）

**不要被 `git cherry +` 误导就再次 cherry-pick**，会引入重复逻辑或与已合入版本冲突。

### 等价验证脚本

```bash
# 验证"已合入"项的实际代码等价（忽略 hunk 位置）
for up in d1e8effc de888df2 97df86ec ...; do
  fork=$(grep "$up" docs/UPSTREAM_SYNC.md | grep -oP '`[0-9a-f]{7}`' | tail -1 | tr -d '`')
  if [ -z "$fork" ]; then continue; fi
  diff <(git show $up --pretty=format: | tail -n +6 \
    | sed -E 's/^index [0-9a-f]+(\.\.[0-9a-f]+)? /index /' \
    | sed -E 's/^@@ -[0-9]+(,[0-9]+)? \+[0-9]+(,[0-9]+)? @@/@@/') \
       <(git show $fork --pretty=format: | tail -n +6 \
    | sed -E 's/^index [0-9a-f]+(\.\.[0-9a-f]+)? /index /' \
    | sed -E 's/^@@ -[0-9]+(,[0-9]+)? \+[0-9]+(,[0-9]+)? @@/@@/')
done
```

差异在 ±10 行内通常为空白或 fork 私有特性（无 S3、MCP 预设、滚动摘要重构等）。
