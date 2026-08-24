# 01 · 项目总览与构建体系

## 1. 项目定位

**RikkaHub Next**：原生 Android LLM 聊天客户端，支持在多个 AI 提供商之间切换对话。
- 上游：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)（AGPL-3.0），fork 基线为上游 2.4.9（`0c52b62b`）
- 本 fork（origin: `ruitong719/Rikkahub-Next`）持续 cherry-pick 上游更新（对账文档 `docs/UPSTREAM_SYNC.md`），当前同步至 `986b9c39`
- 相对上游主要改动：
  - **移除**：Firebase/google-services、S3 备份（云备份仅 WebDAV）、模式注入/世界书
  - **新增**：Workspace 沙箱增强（持久后台任务+完成自动拉起 LLM、SAF 目录挂载、导出、backup.zip）、生成中消息队列、SubAgent 子代理、Todo 工具与 per-tool 提示词、悬浮球、Token-aware 滚动摘要上下文、Shell 实时输出（实验性）、每日 nightly 构建
  - 应用更名 RikkaHub Next，applicationId 改为 `me.rerere.rikkahubnext`（与上游可共存）
- fork 改动的按日全记录：`docs/CHANGES.md`（830 行）

## 2. 模块地图

Gradle 模块（`settings.gradle.kts`）：`:app :highlight :ai :search :speech :common :document :web :material3 :workspace :app:baselineprofile`

```
┌─────────────────────────── :app (me.rerere.rikkahub) ───────────────────────────┐
│  ui/        页面·组件·主题·悬浮球(Compose + Navigation3)                          │
│  service/   ChatService(生成中枢单例)·ConversationSession·WebServerService·       │
│             FloatingBubbleService·ChatNotificationManager·FloatingActivityHub    │
│  data/      db(Room v28)·datastore(Settings)·model·repository·ai(管线)·          │
│             files·sync(WebDAV)·export·favorite·network(代理/身份伪装)·event      │
│  di/        Koin 四大模块 AppModule/DataSourceModule/RepositoryModule/VMModule   │
│  web/       内置 Ktor 服务器业务路由(/api) + DTO                                  │
│  utils/     CrashHandler·UpdateChecker·SoundEffectPlayer 等                       │
└──────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬──────────────┘
       │         │         │         │         │         │         │
     :ai      :common  :workspace  :search  :speech  :document :highlight :material3
  LLM核心抽象  基础工具  PRoot沙箱   19家搜索  TTS+ASR  文档解析  hljs高亮   颜色桥接
  Provider/    Logging  rootfs安装  SDK       media3   MuPDF     Kotlin移植  submodule
  StreamChunk  Cache    shell执行                                30语言
       │
     :web ← static 资源来自 web-ui/(React Router 7 SPA, pnpm 构建)
```

非 Gradle 辅助项目（根目录）：

| 目录 | 说明 |
|---|---|
| `web-ui/` | React Router 7 + Vite7 + Tailwind4 的 Web 前端，产物进 `:web` 的 resources/static |
| `trace-cli/` | Bun/TS CLI：录制真实 Provider SSE → `events.jsonl`，供 ai 模块 `StreamTraceReplayTest` 回放测试 |
| `locale-tui/` | Python uv + textual TUI：strings.xml 六语言翻译管理 / AI 补译 / 死键检测 |
| `build-logic/` | composite build，仅 2 个 convention plugin（见 §4） |
| 根 `package.json` | 仅 `@types/ink` 一个依赖的遗留清单，与构建无关 |

## 3. 技术栈（gradle/libs.versions.toml 关键项）

### 工具链
- AGP **9.3.1** / Kotlin **2.4.10** / KSP 2.3.10 / Gradle wrapper **9.5.0**
- daemon JVM 锁定 JetBrains JDK 21（`gradle/gradle-daemon-jvm.properties`，foojay 自动下载）；CI 客户端 JVM 为 Temurin 17
- 纯 Android（无 KMP）；Compose Compiler 由 kotlin-compose-gradle-plugin 提供
- `org.gradle.configuration-cache=true`；jvmargs `-Xmx4096m`

### UI
| 库 | 版本 | 用途 |
|---|---|---|
| Compose BOM | 2026.08.00 | — |
| material3 | **1.5.0-alpha26**（显式覆盖 BOM，Expressive） | `MaterialExpressiveTheme` |
| Navigation3 | 1.1.6 (+adaptive-navigation3 1.3.0, lifecycle-viewmodel-navigation3 2.11.0) | 类型安全路由 |
| Coil3 | 3.5.0（gif/network-okhttp/svg/cache-control） | 图片加载 |
| Haze | 2.0.0-beta01 | 毛玻璃 |
| floatingx | 2.3.7 | 悬浮球 |
| ucrop 2.2.11-native / image-viewer(scale) 1.1.0-alpha.7 / reorderable 3.1.0 / sonner 0.4.0(toast) | | |
| lucide-icons 1.1.0 / hugeicons(jitpack) 1.4 / 自绘 icons 包 | 图标 | |
| JLatexMath 1.5（含 greek/cyrillic 字体包） | LaTeX 渲染 | |
| markdown renderer | jitpack `com.github.rikkahub:markdown@d79a97cc8e`（catalog 登记，UI 主力实为自绘 org.intellij.markdown，见 06 文档 §4） | |

### 数据 / 网络
| 库 | 版本 | 用途 |
|---|---|---|
| Room | 2.8.4（runtime/compiler/paging/ktx/testing） | 主数据库（**不是 ObjectBox**） |
| Paging | 3.5.1 | 会话列表/图片历史 |
| DataStore preferences | 1.2.1 | 设置存储（文件名 `settings`） |
| OkHttp | 5.4.0（core+sse+logging） | HTTP |
| Retrofit | 3.0.0 + kotlinx converter | 仅 SponsorAPI 占位 |
| Ktor | 3.5.2（client okhttp/content-negotiation + server cio/auth-jwt/sse/cors/compression/status-pages） | WebDAV 客户端 + 内嵌 Web 服务器 |
| kotlinx-serialization-json | 1.11.0 | 全项目序列化 |
| coroutines / datetime | 1.11.0 / 0.8.0 | — |
| WorkManager | 2.11.2（Koin workManagerFactory 接管，InitializationProvider 中 remove 默认初始化器） | |

### AI / 运行时 / 特色
| 库 | 版本 | 用途 |
|---|---|---|
| MCP Kotlin SDK | io.modelcontextprotocol:kotlin-sdk（catalog 0.15.0） | MCP 客户端 |
| QuickJS | wang.harlon.quickjs:wrapper-android 3.2.3 | eval_javascript 工具 + CustomJs 搜索服务 |
| sqlite-android | com.github.rikkahub:sqlite-android **-SNAPSHOT**（requery fork，jitpack/mavenLocal） | SQLite 运行时 + libsimple 中文分词扩展 |
| sqlite-vector | ai.sqlite:vector 0.9.92 | （catalog 引入） |
| Pebble | 4.1.1 | Prompt 模板引擎（TemplateTransformer） |
| java-diff-utils 4.17 / jsoup 1.23.1 / commons-text 1.15.0 | diff 视图 / HTML 解析 / 文本工具 | |
| jmDNS 3.6.3 | Web 服务器 mDNS 广播 | |
| termux terminal-view 0.118.0 + libtermux.so | 工作区交互终端（PTY） | |
| xz 1.12 | workspace rootfs .tar.xz 解压 | |
| zxing 3.5.4 + quickie-bundled 1.11.0 + ML Kit barcode 17.3.0 | 扫码 | |
| media3 1.11.0 + metadata-extractor 2.21.0 | 音频播放(TTS)/媒体元数据 | |

## 4. build-logic（convention plugins）

composite build（根 settings `includeBuild("build-logic")`）。只有两个插件：

| 插件 ID | 文件 | 内容 |
|---|---|---|
| `rikkahub.android.library` | `build-logic/src/main/kotlin/AndroidLibraryConventionPlugin.kt` | com.android.library；compileSdk=37、minSdk=26、testInstrumentationRunner androidx；Java 17 + Kotlin jvmTarget 17 |
| `rikkahub.android.library.compose` | `AndroidLibraryComposeConventionPlugin.kt` | 先套 library 再套 org.jetbrains.kotlin.plugin.compose，开 buildFeatures.compose |

无 application 级 convention——`:app` 直接用官方插件自行配置。各模块差异覆写：web minSdk 24、highlight minSdk 24、search minSdk 23。

## 5. :app 构建配置要点（app/build.gradle.kts）

- namespace `me.rerere.rikkahub`；applicationId **`me.rerere.rikkahubnext`**（debug 加 `.debug` 后缀）
- compileSdk/targetSdk **37**；minSdk **26**
- versionCode **179** / versionName **"2.4.11"**（自 178 起 fork 自行维护版本号）
- ABI：`arm64-v8a` + `x86_64`；splits.abi 打 APK 时启用且 universalApk=true（bundle 任务关闭）
- 签名：release signingConfig 从**根目录 local.properties** 读 storeFile/storePassword/keyAlias/keyPassword（keystore 在 gitignore 的 `app/keystore/`）
- release：AGP9 新 DSL `optimization { enable = true }`（R8），未声明 proguardFiles（仓库无 proguard-rules.pro）
- BuildConfig 注入 VERSION_NAME/VERSION_CODE
- Compose compiler：stability 配置文件 `app/compose_compiler_config.conf`（把 kotlinx.collections.immutable、kotlin.uuid/time、java.time 及 `me.rerere.ai.ui.*`、Conversation/MessageNode 标记 stable）
- `androidResources.generateLocaleConfig = true`
- KSP arg `room.schemaLocation=$projectDir/schemas`（schema JSON 入库，28 个版本）
- packaging：`jniLibs.useLegacyPackaging=true` + pickFirsts `lib/*/libtermux.so`
- 自定义 task `buildAll` = assembleRelease + bundleRelease
- 依赖全部库模块 + `app/libs/*.jar/*.aar` fileTree + baselineProfile(:app:baselineprofile)
- 无 productFlavors

## 6. CI/CD

唯一 workflow：`.github/workflows/daily-build.yml`

- 触发：cron `0 18 * * *`（UTC 18:00 = 北京次日 02:00）+ workflow_dispatch
- Job `check`：过去 24h 有新提交才继续（手动触发跳过检查）
- Job `build`（contents: write）：
  1. checkout（fetch-depth 0 + submodules recursive 拉 material-color-utilities）
  2. setup-java Temurin 17；pnpm 11 + Node 22
  3. `web-ui` 内 `pnpm install --frozen-lockfile`（gradle 的 buildWebUi 只跑 build 不装依赖）
  4. Gradle cache
  5. 签名准备：secrets `KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` 存在则解码出 `app/keystore/release.jks` 并生成 local.properties → assembleRelease；否则降级 assembleDebug
  6. softprops/action-gh-release@v3：固定 tag **`nightly`**、prerelease、每晚覆盖、上传全部 APK

## 7. 仓库流程约定

### 上游同步（docs/UPSTREAM_SYNC.md）
- 以 merge-base `0c52b62b` 为基线，`git cherry HEAD upstream/master`（patch-id）对账
- 按时间顺序逐 commit cherry-pick（保留原作者署名）；pick 前 `git show <sha> | git apply --check -` 预检；注意提交间依赖
- 特殊处理记录：`d1e8effc`（移除推理刻度）有意跳过——fork 反向利用该区域显示 7 档标签；`adf333ec`（上下文条数输入）不直接合入——fork 已改 token 阈值
- 每次同步后更新 UPSTREAM_SYNC.md 与 CHANGES.md

### i18n
- app 支持 6 语言：`values`(英语源) / `values-zh` / `values-zh-rTW` / `values-ja` / `values-ko-rKR` / `values-ru`（各 ~1200–1400 条）
- search/speech 等库模块有自己的 values*
- 命名规范：页面级 key 加前缀如 `setting_page_`；Compose 用 `stringResource(R.string.xxx)`
- 未被明确要求时优先不做事（`Text("Hello")` 直写英文）
- locale-tui（`uv run python src/main.py`）负责比对/AI 补译/死键检测，配置在 `locale-tui/config.yml`

### 测试
- JVM 单测集中在：ai 模块（请求构造/流解码/registry/metadata/迁移）、highlight（hljs golden fixture 对拍）、search（Doubao）、speech（provider setting 解析）
- `./gradlew test` 全量运行；stream 回放测试依赖 trace-cli 生成的 `ai/src/test/resources/stream-traces/generated/**`
- baselineprofile 子模块采集启动性能（产物入库于 `app/src/release/generated/baselineProfiles/`）

### 其他
- `.gitmodules`：material-color-utilities（kotlin 源码进 :material3 编译）
- `git log` 近期方向（2026-08）：客户端身份伪装预设 + OpenCode Zen 接入 → 消息队列/tok/s 统计 → 备份项选择 → 删除内置助手/供应商并防复活 → 悬浮球打磨
