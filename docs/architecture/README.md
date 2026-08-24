# RikkaHub Next 架构文档

> 面向 AI Agent / 新接手开发者的完整架构参考。目标：读完本目录即可动手改代码，无需重新考古源码。
> 生成时间：2026-08-25（基于 master @ `33a422c0`，versionCode 179 / versionName 2.4.11）。

## 文档目录

| 文件 | 内容 |
|---|---|
| [01-overview.md](01-overview.md) | 项目定位、模块地图、技术栈、构建体系、CI、仓库流程约定（上游同步 / i18n / 文档规范） |
| [02-core-models.md](02-core-models.md) | `ai` 模块核心模型：UIMessage / UIMessagePart / StreamChunk / Tool / Provider 抽象与三家实现 / ModelRegistry / 思考深度映射 |
| [03-data-layer.md](03-data-layer.md) | app 数据层：Room 数据库(28版)、DataStore 设置项全集、Repository、文件/技能管理、DI 清单 |
| [04-generation-pipeline.md](04-generation-pipeline.md) | 聊天生成的完整链路：ChatService → GenerationHandler → Transformer 管道 → 工具循环与审批 → 滚动摘要上下文 → SubAgent → 消息队列 → 后台任务自动拉起 |
| [05-tools-and-mcp.md](05-tools-and-mcp.md) | 工具系统全景：全部本地工具、搜索工具、Workspace 工具、技能、子代理工具、MCP 客户端与 OAuth |
| [06-ui-layer.md](06-ui-layer.md) | UI 层：应用入口、Navigation3 路由表、页面清单、ChatPage/ChatInput 专项、组件库、主题系统、悬浮球 |
| [07-library-modules.md](07-library-modules.md) | 库模块：common / search(19家搜索) / speech(TTS+ASR) / document / highlight / material3 |
| [08-workspace-sandbox.md](08-workspace-sandbox.md) | Workspace 沙箱专题：PRoot 原理、rootfs 安装、双存储区文件系统、SAF 挂载、后台任务、终端 |
| [09-web-platform.md](09-web-platform.md) | 内置 Web 平台：Ktor 服务器、REST/SSE API 合同、JWT 鉴权、web-ui React 前端 |

## 快速上手（Agent 必读）

### 一句话定位

原生 Android LLM 聊天客户端（Kotlin + Jetpack Compose + Koin + Room），上游 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 的 fork，新增 Workspace 沙箱、SubAgent、消息队列等 agentic 能力。**无 KMP，纯 Android 工程；无 Firebase。**

### 十个最常被问到的位置

| 问题 | 答案 |
|---|---|
| 聊天生成入口在哪？ | `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt` 的 `handleMessageComplete()`（注意：ChatService 是普通单例不是 Android Service） |
| 消息数据结构？ | `ai/src/main/java/me/rerere/ai/ui/Message.kt` (UIMessage) + `UIMessagePart.kt` (sealed part) + `app/.../data/model/Conversation.kt` (MessageNode 分支树) |
| 如何加一个 AI 工具？ | 见 README「常见任务手册」§T3 与 05 文档 §1 |
| Provider 怎么抽象的？ | `ai/src/main/java/me/rerere/ai/provider/Provider.kt`，三家实现 openai/google/claude，经 `ProviderManager` 注册 |
| 数据库 schema 在哪改？ | `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`（当前 v28），schema JSON 输出到 `app/schemas/` |
| 用户设置存哪？ | DataStore 文件名 `settings`，入口 `data/datastore/PreferencesStore.kt`(类名 SettingsStore)，键清单见 03 文档 §2 |
| 导航路由怎么加？ | `RouteActivity.kt` 尾部 `sealed interface Screen : NavKey`，见 06 文档 §2 |
| Web API 合同？ | `app/src/main/java/me/rerere/rikkahub/web/WebApiModule.kt`，完整路由表见 09 文档 |
| 构建命令？ | `./gradlew assembleDebug` / `./gradlew test` / `./gradlew lint`；release 需根目录 `local.properties` 提供签名信息 |
| 上游同步流程？ | `docs/UPSTREAM_SYNC.md`（patch-id 对账 + 逐 commit cherry-pick） |

### 术语表

| 术语 | 含义 |
|---|---|
| **Assistant（助手）** | 一套对话配置：系统提示词、模型参数、工具开关、正则变换、记忆开关等，会话间相互隔离 |
| **Conversation（会话）** | 持久化的对话线程，持有 MessageNode 树、标题、置顶、文件夹归属、滚动摘要 |
| **MessageNode（消息节点）** | 一个"消息位"的分支容器：多条 UIMessage + selectIndex，支撑重新生成/切换分支 |
| **UIMessage** | 平台无关的消息抽象：role + parts 列表 + 用量/时长元数据（定义在 ai 模块） |
| **Transformer** | 发送前(Input)/接收后(Output)的消息变换管道（模板渲染、think 标签抽取、OCR 等） |
| **Tool** | AI 工具（`ai/core/Tool.kt`）：name/description/parameters/systemPrompt/needsApproval/execute |
| **ApprovalState** | 工具审批状态机：Auto/Pending/Approved/Denied(reason)/Answered(answer) |
| **Workspace（工作区）** | PRoot 沙箱 Linux 环境，含 FILES(/workspace) 与 LINUX(rootfs) 双存储区 |
| **Rolling Context** | Token 阈值触发的滚动摘要压缩机制（默认 32K tokens） |
| **SubAgent** | 子代理：主代理通过 `subagent_<slug>` 工具派发任务，独立步数/超时/审批豁免 |
| **Session** | ChatService 内每会话的运行时容器（状态流 + 生成 Job + 引用计数 + 排队消息） |

### 高频修改手册（Playbooks）

#### T1. 新增数据库字段/表
1. 改 Entity（`data/db/entity/`）→ `AppDatabase.version += 1`
2. 优先 AutoMigration（编译器自动生成 `app/schemas/N.json`）；删列需写 AutoMigrationSpec
3. 涉及 messages JSON 结构的必须手写 Migration（复用 `data/db/migrations/MigrationUtils.kt` 的 partTypeMapping）
4. 同时更新 `di/DataSourceModule.kt` 的 `addMigrations(...)` 列表
5. ⚠️ FTS 表(`message_fts`)不在 Room schema 管辖内——它在 onOpen 回调里幂等 CREATE（DataSourceModule.kt）

#### T2. 新增设置项
1. `SettingsStore` companion 加 Preferences.Key
2. `Settings` data class 加字段（`data/model/Settings.kt`）
3. `settingsFlowRaw` 的 map 与 `update(Settings)` 各补一行序列化/反序列化
4. 如需旧数据迁移：新增 V(n)Migration 并同步 `SettingsJsonMigrator`（备份恢复走 JSON 整对象迁移路径）

#### T3. 新增本地 AI 工具
1. 实现 `ai.core.Tool`（execute 返回 `List<UIMessagePart>`，异常会被捕获转 JSON error）
2. 本地工具：加入 `LocalTools.getTools()` + `LocalToolOption` sealed（@SerialName 持久化）
3. workspace 工具：同步三张表——`WorkspaceToolDefaultApprovals`（审批默认）、`WORKSPACE_TOOL_NAMES`、`DEFAULT_WORKSPACE_TOOL_PROMPTS`（system prompt 文案）
4. UI 卡片：在 `ui/components/message/tools/ToolUIRegistry` 注册 ToolUIRenderer（不注册则显示 JSON 兜底）

#### T4. 新增页面/路由
1. `RouteActivity.kt` 的 `Screen` sealed 加 @Serializable 路由类
2. `AppRoutes()` 的 NavDisplay entry 里挂页面 Composable
3. ViewModel 在 `di/ViewModelModule.kt` 注册（带参用 `viewModel<VM> { params -> ... }`）
4. 字符串按需进 `res/values*/strings.xml`（6 语言；可用 locale-tui 补译）

#### T5. 新增 Web API 端点
1. `web/routes/` 下新建 Route 扩展函数并在 `WebApiModule.configureWebApi` 注册（注意 JWT 包裹范围）
2. DTO 加进 `web/dto/WebDto.kt`
3. web-ui 前端对应改 `web-ui/app/services/api.ts` 与 types/

#### T6. 加新的搜索/TTS/ASR 服务商
- 搜索：`search/` 下实现 `SearchService<T>` 单例 object + `SearchServiceOptions` 子类(@SerialName) + `getService()` 映射 + `TYPES` 显示名表
- TTS/ASR：`speech/` 下实现 Provider + Setting，UI 配置页在 `setting/components/*ProviderConfigure`

### 已知坑（Gotchas）

1. **sqlite-android 是 `-SNAPSHOT`**（jitpack/mavenLocal 解析 `com.github.rikkahub:sqlite-android`）：干净环境首次构建可能失败，必要时先 `mavenLocal()` 安装或用 jitpack 缓存。
2. **Gradle daemon 强制 JetBrains JDK 21**（`gradle/gradle-daemon-jvm.properties` + foojay 自动下载），而 CI 只装 Temurin 17 作为客户端 JVM。
3. **`:web` 模块 preBuild 依赖 `buildWebUi`**（到 `web-ui/` 执行 pnpm build）——不想构建前端时可用 `-x :web:buildWebUi`。
4. **settings.gradle.kts 里残留 ObjectBox plugin resolutionStrategy**（上游死代码，实际用 Room，勿被误导）。
5. **DB 版本 27→28 后 CHANGES.md 尾部仍写着 27**（文档滞后，以代码为准）；README 的上游基线行也略滞后于 UPSTREAM_SYNC.md（后者为准）。
6. **生成中消息队列有两条 flush 路径**：快路径在 GenerationHandler 步进循环顶部 poll；慢路径是 Job finally 的 `flushQueuedMessages` 兜底——改动任一处都要考虑另一处。
7. **流式期间 UI 不用 SelectionContainer**（避免 ConcurrentModificationException，ChatMessage 有注释说明）。
8. **工具输出 >32KB 且有 workspace 时自动截断**：全文落盘 `files/tool_outputs/<callId>.txt`，只留 4KB 预览。
9. **subagent 工具池禁止嵌套**：catalog 传入时已剔除 subagent 工具；ask_user 对 subagent 一律排除。
10. **rootfs 无内置**，运行时从 URL 下载（默认 Ubuntu Base 24.04.3 arm64），自写 tar 解析器带 zip-slip 防护。
