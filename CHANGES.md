# Rikkahub Next — 工作区功能合入记录

日期：2026-08-13
分支：`chore/remove-google-services`（合并目标：`master`）

---

## 1. 背景与基线

- **基线（官方实现）**：`master` @ `576f2341`（chore: trigger build - sync 20260807-161340）
- **开发分支（自研）**：`dev` @ `fcafc878`，在 master 之上包含 18 个提交，实现 plan.md 的 **8 个工作区功能**
- **任务**：以 master 为基线，将 dev 的 18 个提交按功能逐个 cherry-pick 到 `chore/remove-google-services`，**每个功能先 review 修正、再编译验证通过，才进入下一个**；最后重命名应用为 Rikkahub Next 并产出已签名的 release APK

> dev 上所有提交均未在本机编译过（plan.md 明确说明沙箱无 Android SDK），
> 因此逐功能 review + 修正编译错误是本任务的核心工作。

---

## 2. dev 提交与功能映射（cherry-pick 顺序 = dev 历史顺序）

| # | 提交 | 内容 | 对应功能 |
|---|------|------|---------|
| 1 | `0defd746` | docs: add dev plan for five workspace features | 计划文档（功能一~五） |
| 2 | `c9c3e025` | fix(backup): consistent DB snapshot, safe restore, expanded scope | **功能四** 备份修复 |
| 3 | `c520a03d` | feat(workspace): workspace_export_to_phone tool | **功能一** 导出到手机 |
| 4 | `a91aa243` | feat(workspace): mount phone SAF directories at /mnt/<name> | **功能二** SAF 挂载 |
| 5 | `a7b15ec7` | feat(workspace): persistent background tasks | **功能三** 后台任务 |
| 6 | `e8139c82` | feat(workspace): workspace_create_backup tool | **功能五** backup.zip 工具 |
| 7 | `10498cb5` | fix(workspace): compile fixes for mount/bg integration | 官方修复 |
| 8 | `1e0f9fd1` | docs: add dev plans for subagent / todo / per-tool prompts | 计划文档（功能六~八） |
| 9 | `2e14546e` | feat(local-tools): per-conversation todo list tools | **功能七** Todo |
| 10 | `6b4030d1` | feat(workspace): per-tool injectable prompts | **功能八** per-tool 提示词 |
| 11 | `efffc28a` | feat(subagent): data model + presets + storage | **功能六** Subagent（1/8） |
| 12 | `c69ab79a` | feat(subagent): pure logic + JVM tests | 功能六（2/8） |
| 13 | `b7437323` | feat(subagent): SubAgentRunner | 功能六（3/8） |
| 14 | `ae1ebc17` | feat(subagent): tool registration + ChatService refactor | 功能六（4/8） |
| 15 | `7d0e2633` | feat(subagent): extensions entry + routes + list page | 功能六（5/8） |
| 16 | `a2994118` | feat(subagent): full edit page (create/edit) | 功能六（6/8） |
| 17 | `2bce87cc` | feat(subagent): per-assistant opt-in | 功能六（7/8） |
| 18 | `fcafc878` | docs: mark features 6-8 implemented | 文档收尾 |

---

## 3. 逐功能 review 发现并修复的问题

> 括号内为修正提交。全部问题均为 **dev 从未编译过** 导致的编译错误或实现缺陷。

### 功能四：备份修复（一次通过）
无编译问题。

### 功能一：导出到手机（6 处，fix commit `3b466dd0`）
- `WorkspaceExportTools.kt`：缺 `jsonObject` import
- `WorkspacePhoneExporter.kt`：`DocumentFile.openOutputStream(context)` — **documentfile 1.1.0 没有该方法**
  （经 javap 验证 API 只有 fromTreeUri/createFile/findFile/listFiles 等），改为 `context.contentResolver.openOutputStream(uri)`
- 字符串模板把函数 `relativePath()` 当属性用
- **严重 bug**：`WorkspaceEntity.toolApprovals` 默认值被从 `"{}"` 改坏成 `"{"`（非法 JSON，
  会破坏所有工作区的工具审批解析）→ 恢复 `"{}"`
- `WorkspaceDetailPage.kt`：缺 `Context` import；`statusText` smart-cast 失败（`produceState` delegate 不能智能转换）
- `WorkspacePhoneExporterTest`：`emptyList()` 在 JUnit 泛型重载下无法推断，需显式 `emptyList<String>()`

### 功能二：SAF 挂载（5 处，fix commit `7e6b585a`）
- `WorkspaceMountManager`：`child.openInputStream(context)` / `fileDoc.openOutputStream(context)`
  均不存在 → `contentResolver` 对应 API
- **`SyncStats` 是不可变 data class 却被 `stats.skipped++` 自增** → 新增内部可变状态类；
  （dev 官方修复 `10498cb5` 后来把 SyncStats 改成 var 字段，冗余包装类随后移除，refactor `4c4aa66c`）
- `WorkspaceMountTools.kt`：缺 `jsonObject` import
- `WorkspaceRepository.executeCommand`：suspend 函数 `dynamicBindMounts()` 被调用在非 suspend 的
  `runInterruptible{ }` 内 → 提前取值传入
- `PreferencesStore.kt`：缺 `WorkspaceMountConfig` import
- 顺手补上从未更新的 `dirsCreated` 统计

### 功能三：后台任务（4 处，fix commit `734cb9ac`）
- `WorkspaceBgManager.HeadlessSession.start()` 内调用 suspend 的 `mountManager.activeBindMounts()`
  → `start(extraBindMounts)` 参数化、`ensureSession` 改为 suspend
- 缺 `PROOT_EXEC` / `PROOT_LOADER` 常量定义（ProotShellRunner 里是 private）
- `WorkspaceBgTools.kt`：缺 `jsonObject` import
- `RikkaHubApp.incrementLaunchCount()` 缩进损坏（`{        get<AppScope>()...`）→ 还原格式

### 功能五：backup.zip 工具（无编译问题）
`WorkspaceBackupTool` 依赖的 `WebDavSync.prepareBackupFile(config)`、`BackupItem.entries`、
`settingsFlow.value` 均已确认存在，一次通过。

### 功能七：Todo（4 处，fix commit `b08f985b`）
- `TodoStore.updateList`：`todos()` 返回只读 `StateFlow` 却执行 `flow.value = newList`
  → 拆出 `todosMutable()` 私有方法，公开接口保持 StateFlow
- `TodoSheet.kt` 图标：HugeIcons 是空 object，图标是 `stroke` 包的**扩展属性**，
  需同时 import `me.rerere.hugeicons.HugeIcons` + `me.rerere.hugeicons.stroke.XXX`
- `TodoStoreTest`：**JUnit4 要求测试方法返回 void**，`= runBlocking { }` 表达式体编译为泛型返回值
  → 改为块体 `{ runBlocking { ... } }`；随后补齐 5 处大括号配对
- `AppModule.kt`：cherry-pick 冲突时被误删的 `import android.content.Context`（TodoStore 注册需要）
- `ChatPage.kt`：`},onUpdateAssistant = {` 缩进损坏 → 还原

### 功能八：per-tool 提示词（一次通过）
数据层（toolPrompts 可空列 + DB 25→26 AutoMigration）+ 默认表 + 注入改造 + 编辑对话框
+ 单测，全部干净。DB schema 26.json 已提交。

### 功能六：Subagent（2 处，fix commit `f495b075`）
- `ChatService.kt`：`createSubAgentTools` 的 `memories = memories` 引用了 `generateText`
  的**命名参数**（作用域内不存在）→ 提取为局部变量 `val memories: List<AssistantMemory>?`
  （含 `useGlobalMemory` 分支），并补 `AssistantMemory` import
- `AssistantExtensionsPage.kt`：缺 `androidx.compose.ui.unit.dp` import

---

## 4. 构建与测试验证（本机 Windows）

构建环境：
- JDK 17（Eclipse Adoptium）、Gradle 9.5.0 wrapper、SDK platform 35/37、build-tools 35/36
- 网络受限：Maven 仓库经 `D:\Code2\rikkahub-ci\init.gradle` 切换阿里云镜像，
  jitpack 缺失制品预置于 `~/.m2`（mavenLocal 优先）
- 构建命令（release 签名从 local.properties 读取）：
  ```
  ./gradlew.bat -I D:/Code2/rikkahub-ci/init.gradle --no-configuration-cache \
    :app:assembleDebug / :app:testDebugUnitTest / :app:assembleRelease -x :web:buildWebUi
  ```
- `-x :web:buildWebUi`：本机无 node/pnpm，跳过前端构建（CI 中由 pnpm build 负责）

| 验证项 | 结果 |
|--------|------|
| 功能四~八逐个 assembleDebug | ✅ 全部 BUILD SUCCESSFUL |
| 全量单元测试（4 个新增测试类） | ✅ BUILD SUCCESSFUL |
| dev 18 个提交按标题核对 | ✅ 全部在分支历史中 |
| assembleRelease（R8 优化） | ✅ BUILD SUCCESSFUL in ~5min |

---

## 5. 应用重命名（commit `feed2b85`）

| 项 | 旧值 | 新值 |
|----|------|------|
| applicationId | `me.rerere.rikkahub` | `me.rerere.rikkahubnext` |
| 应用显示名（app_name） | RikkaHub | **Rikkahub Next** |
| 测试断言 | 硬编码包名 | `BuildConfig.APPLICATION_ID` |

> FileProvider / DocumentsProvider / startup 等 authority 均用 `${applicationId}` 动态生成，无需修改。
> ⚠️ applicationId 变更后与旧版是**不同应用**（全新安装，不保留旧数据），为预期行为。

---

## 6. 签名信息（release）

- **keystore**：`app/keystore/rikkahubnext-release.jks`（已 gitignore，**务必备份**）
  - alias：`rikkahubnext`
  - storePassword / keyPassword：`RikkahubNext2026!`（同时存于 `local.properties`，已 gitignore）
  - 证书：CN=Rikkahub Next, OU=Mobile, O=Rikkahub, L=Beijing, ST=Beijing, C=CN；RSA 2048，有效期 10000 天
  - SHA-256：`5bbf4d25701ad159e1db3fe72f288d48a4f915e8c129c1057da1a11cd884a518`
- 验证：apksigner v2 签名通过
- 产物（`D:\Code2\rikkahub-ci\dist\`）：
  - `RikkahubNext-arm64-v8a-release.apk`（34,954,044 字节，arm64 手机用）
  - `RikkahubNext-universal-release.apk`（45,052,046 字节，全 ABI）
  - （另有 x86_64 分包在 `app/build/outputs/apk/release/`，供模拟器）

### 6.1 Release 2（2026-08-13，含中文翻译）

- 新增功能的中文翻译全部合入 `values-zh`（75 条，commit `a9a016b8`），
  各语言 `app_name` 统一为 Rikkahub Next，`Pull/Push` 硬编码提取为 string resource
- 重新构建 release：`BUILD SUCCESSFUL in 3m 8s`
- 产物（已更新至 `D:\Code2\rikkahub-ci\dist\`）：
  - `RikkahubNext-arm64-v8a-release.apk`（34,954,044 字节）
  - `RikkahubNext-universal-release.apk`（45,052,046 字节）
- 同一 keystore 签名（指纹不变）

---

## 7. 分支最终状态

- 合并后 `master` 将包含 27 个提交（基线 + 移除 google-services + 18 个 dev 提交 + 5 个修复/改名单提交）
- 分支历史关键节点（按时间顺序尾部）：
  ```
  9bf1d16b  chore: remove google-services.json and Firebase dependencies（独立先行任务）
  bbe36382  docs: add dev plan for five workspace features
  495a5fb8  fix(backup): consistent DB snapshot...
  80880b83  feat(workspace): workspace_export_to_phone tool...
  3b466dd0  fix(workspace): compile fixes for export-to-phone feature
  7eb57a64  feat(workspace): mount phone SAF directories...
  7e6b585a  fix(workspace): compile fixes for SAF mount feature
  8b958a27  feat(workspace): persistent background tasks...
  734cb9ac  fix(workspace): compile fixes for background tasks feature
  b6d3d98e  feat(workspace): workspace_create_backup tool...
  ceab7863  fix(workspace): compile fixes for mount/bg integration
  4c4aa66c  refactor(workspace): drop redundant MutableSyncStats wrapper
  9e93eb76  docs: add dev plans for subagent, todo local tool, per-tool workspace prompts
  f2317b1c  feat(local-tools): per-conversation todo list tools...
  b08f985b  fix(local-tools): compile fixes for todo feature
  0bbd3199  feat(workspace): per-tool injectable prompts...
  3b5ad955  feat(workspace): per-tool injectable prompts (cherry-picked merge)
  17cbdb0d  feat(subagent): data model...
  600eb2b5  feat(subagent): pure logic...
  eeb83e24  feat(subagent): SubAgentRunner...
  3aeb4926  feat(subagent): tool registration...
  3b5d0abc  feat(subagent): extensions entry...
  f20087cd  feat(subagent): full edit page...
  26e96b2c  feat(subagent): per-assistant opt-in...
  f7e732e9  docs: mark features 6-8 implemented
  f495b075  fix(subagent): compile fixes for subagent feature
  feed2b85  chore: rename app to Rikkahub Next
  ```

---

## 8. 已知注意事项（后续接手者）

1. **keystore 与签名密码**：`app/keystore/` 已 gitignore，换机器/CI 构建 release 需手动恢复
2. **`init.gradle` 是仓库外的临时镜像脚本**（`D:\Code2\rikkahub-ci\init.gradle`），正常网络环境不需要；
   仓库内 settings.gradle.kts 保持官方仓库配置
3. **web-ui 前端**：本机构建用 `-x :web:buildWebUi` 跳过；CI 需要 node/pnpm 执行 web-ui 构建
4. **网络代理历史**：dl.google.com / jitpack.io 本机直连超时，需代理或镜像（记录见会话历史，
   当前构建环境已留全套镜像缓存于 `~/.m2` 与 Gradle cache）
5. **功能真机验证**：SAF 权限（导出/挂载）、headless proot 后台任务、Subagent 嵌套循环重入、
   超时取消等运行时行为需要真机/模拟器验证（编译与单测已验证，运行时未验证）
6. **Room DB 迁移**：v24→25（exportTargetUri）、v25→26（toolPrompts）AutoMigration 依赖
   已提交的 schema JSON（1~26.json），运行时迁移正确性需真机确认
---

# 2026-08-14 迭代记录（master 直接提交）

日期：2026-08-14
分支：`master`（基于 `fc22cfb1` 之后的本地提交，共 26 个新提交）

## A. 聊天与工作区体验（10 项）

1. **backup 导出修复**（`d81cbcd3` 等 3 个提交）
   - requery SQLite 的 `execSQL` 不允许 PRAGMA，`wal_checkpoint(TRUNCATE)` 改走
     `SupportSQLiteDatabase.query`（androidx.sqlite 2.6 已移除 rawQuery，bindArgs 非空用 emptyArray）
   - zip 内数据库条目改为 `rikka_hub.db`（+`rikka_hub-wal`），对齐 rikkahub-to-csv skill
     step1 的硬编码查找；restore 新旧条目名均兼容
2. **手机目录挂载**：启动时自动 PULL 一次（`pullAllAtStartup` + RikkaHubApp 接线）；
   `activeBindMounts` 目录缺失时先创建，保证 shell/headless 会话恒定可访问 `/mnt/<name>`
3. **偏好设置**：UI 页新增「底栏图标」4 个开关（websearch/推理强度/todo/subagent），
   `DisplaySetting` 新增对应布尔字段（默认 true，旧数据容错）
4. **聊天 + 弹窗**：扩展管理新增「子智能体」tab；工作区项在设置/终端图标后新增
   文件夹图标直达文件管理页
5. **todo**：底栏面板新增清空当前 todolist（二次确认）+ `todo_clear` 工具
6. **subagent 监看**：底栏新增监看图标（启用时显示+角标），面板展示最近一次执行状态
   （未调用/执行中/成功/失败/超时），点击跳编辑页
7. **提供商类型标签**：添加提供商时改用本地化显示名（OpenAI/Google Gemini/Claude）
8. **删除模式注入/世界书**（详见下文 C）

## B. 新增功能（4 项）

1. **更新地址配置化**（`40aee835`）：`Settings.updateUrl` 配置项，`UpdateChecker` 从
   SettingsStore 读取，空串回退 `https://updates.rikka-ai.com/`；设置→关于可编辑
2. **AGENTS.md 双源注入**（`55a418fd`）：`AgentMdTransformer` 注入首条 system 消息；
   助手绑定工作区且存在 `/workspace/agent.md` 时以文件为准（优先级更高），
   否则用设置里的全局文本（设置→偏好→常规可编辑）；subagent 不经过该转换器
3. **思考深度映射表**（`9c879313`、`481a15a9`）：
   - `ReasoningEffortMappings` 集中映射：用户配置 > 模型 id 定向覆盖（deepseek-v4:
     XHIGH→max）> 供应商作用域默认（openai_chat/nvidia: OFF→low；gemini3: HIGH/XHIGH→high）
     > 全局托底（none/auto/low/medium/high/xhigh）
   - 模型编辑页新增第 4 个 tab「思考深度映射」：六等级可填自定义发送值（`Model.reasoningEffortMap`），
     留空用内置表；「关闭思考」仅在 effort 语义接入点（OpenAI 系/OpenRouter/NVIDIA）可自定义，
     Claude/Gemini 用结构字段关闭（disabled/minimal）故置灰，布尔开关类平台忽略用户值
4. **构建验证**：`:app:assembleRelease -x :web:buildWebUi` 通过（JDK17 + 仓库外 init.gradle），
   新增单测全绿（RootfsPathResolution / ReasoningEffortMappings）

## C. 删除模式注入/世界书（功能整体下线）

- 数据模型：`PromptInjection`/`ModeInjection`/`RegexInjection`/`Lorebook`/`InjectionPosition`、
  Assistant/Conversation 相关字段、`LearningMode.kt`、`PromptInjectionTransformer` 及其测试
- 逻辑层：Transformer 上下文、GenerationHandler/ChatService 链路、`ModeInjectionSerializer`/
  `LorebookSerializer`（含 SillyTavern 导入）、PreferencesStore key/字段/sanitizer
- UI：`PromptPage`/`PromptVM` 整页删除，扩展管理/聊天弹窗/助手扩展页收敛为三 tab
- web：`POST /{id}/injections` 路由、DTO 字段、web-ui extension-picker 只留快捷消息；
  6 个 locale 清理 50+ 字符串键
- DB：`Migration_26_27`（@DeleteColumn ×2，version 27），schema 27.json 已提交

## D. 已知注意事项（更新）

- Room DB 当前版本 27；删除模式注入/世界书后旧库升级会丢弃
  `mode_injection_ids`/`lorebook_ids` 两列（AutoMigration 自动完成）
- web-ui 本机构建仍以 `-x :web:buildWebUi` 跳过（无 node/pnpm 环境）

---

# Rikkahub Next v1.00 — 2026-08-15 变更记录

## A. 合并上游 2.4.9

- `git merge upstream/master`（22 commits，bump 2.4.9/176）：保留 fork 全部功能
  （workspace 八工具/后台任务/subagent/思考深度映射/移除 Firebase），采用上游修复与新功能
- 冲突解决：provider 导入并集、deepseek/nvidia 保留映射表逻辑、搜索按钮保留显示开关、
  strings 并集；`ReasoningEffortMappings` 补充上游新增 `ReasoningLevel.MAX`（DEFAULT 与
  deepseek-v4 覆盖均加 MAX→max）

## B. 版本与品牌

- 版本号 `1.00`（versionCode 177，取上游 176 + 1）
- 图标：按 CI 工作流"还原旧版图标"步骤直接应用进仓库（ci-assets → mipmap 全套
  ic_launcher/background/foreground/monochrome + assets/icons/rikkahub.svg），不再依赖 CI 替换
- 删除兔子加载动画（RabbitLoading.kt / rabbit.xml），一律用默认 ContainedLoadingIndicator；
  移除"使用APP图标风格加载指示器"开关（保留 Settings 字段兼容旧数据）

## C. 文案与页面收敛

- 提供商类型三段选择器：`Google Gemini` → `Gemini`（en/zh）
- 模型编辑页「思考深度映射」→「思考映射」（zh；en 已是 Reasoning Mapping）
- 删除设置页「使用文档 / 赞助 / 分享」三个入口、赞助弹窗、`SettingDonate` 页/路由及相关
  字符串（**保留** ShareHandler 接收外部分享与提供商配置分享能力）
- 删除 ➕ 菜单中两个「导入酒馆角色卡」（AssistantImporter.kt 整体删除、相关字符串、
  `ImageUtils.getTavernCharacterMeta`）

## D. AGENT 指令 / /agent 目录 / 视觉模型

- 「AGENT 指令（AGENTS.md）」设置上移至 设置→默认模型和提示词→提示词 tab（从偏好-常规页移出）
- 新增 `/agent` 目录（挂载自 `filesDir/agent`，对齐 /upload 模式）：RepositoryModule 与
  WorkspaceBgManager 内置挂载同步；Workspace 提示词新增 /agent 说明；WebDavSync FILES 备份覆盖
- `AgentMdTransformer` 重写：读取 `/agent` 下全部 `*.md`（agent.md 优先、其余按文件名排序）
  拼接注入首条 system 消息；目录为空时回退设置里的全局文本
- 新增「视觉模型」设置（`Settings.visionModelId`，模型页可清空）与
  `VisionImageToTextTransformer`（image-router 式降级）：主模型 inputModalities 不含 IMAGE 且
  消息含图片时，用视觉模型逐张生成描述替换为文本（url 缓存防重复调用；历史消息一并修复，
  避免纯文本模型解析图片报错）；视觉模型缺失/失败时透传

## E. 聊天底栏

- 智能体监看按钮图标 24→20dp
- 新增「后台任务」按钮（智能体按钮之后）：仅当助手绑定工作区且存在后台任务时显示
  （4s 轮询 WorkspaceBgManager.listTasks）；点击弹出任务列表面板（命令/状态/时间/输出预览，
  运行中可 Kill、可刷新）

## F. Subagent

- 模型选择改为聊天底栏同款 `ModelListSheet`（sheet 顶部「跟随主聊天模型」项），
  `modelId=null` 默认跟随主模型
- 新增 `SubAgentRunMonitor`（Koin single，内存态轨迹注册表）+ `SubAgentRunner` 实时上报
  （start / 工具调用步骤 / finish）
- 智能体页：运行中的智能体显示「执行中」标记，点击进入新页面 `Screen.SubAgentTrace`
  （状态/任务/工具调用步骤/最终结果，实时刷新）；已完成的可用轨迹图标查看最近一次运行

## G. workspace_backup 工具

- 只导出 `rikka_hub.db` 一致性快照：`items = [DATABASE]`，`prepareBackupFile` 新增
  `includeSettings=false`（zip 不再含 settings.json）；工具描述同步更新

## H. 备份/上传确认（无代码改动）

- 备份已覆盖 `/skills` 整目录递归 + `workspaces/<ws>/linux/root`（= 沙箱 /root，含 .bashrc）
- 上传接口图片与文件同流程（multipart + MIME 透传，无图片专用分支）

## I. web-ui 构建（pnpm 修复完成）

- 根因：本机从未安装 node/pnpm（此前一直以 `-x :web:buildWebUi` 跳过，static 目录为空）
- 修复：安装 Node.js v22.12.0（C:\Development\nodejs）+ pnpm 10.34.5（npm 全局），
  `pnpm install`（15s，lockfile 正常）→ `pnpm build`（react-router build + copy.ts →
  web/src/main/resources/static，13MB 产物）
- 验证：去掉 `-x :web:buildWebUi` 后 `assembleRelease` 全流水线（gradle → cmd → pnpm → static → APK）通过

## J. 上游测试修复（#1719）

- `ResponseApiStreamDecoderTest.raw reasoning and summary with the same index should remain distinct`
  在上游 master 上本身为红（干净 worktree 复现确认）：#1719 实现出于安全考虑
  （encrypted_content 存在时不回放明文 raw reasoning），测试却传了 encrypted 还断言
  content 存在，二者矛盾
- 修复：该测试的 done 事件不再携带 encrypted_content（明文场景下 content 正常回传），
  distinctness 断言保持不变；encrypted 场景由另一条测试覆盖

## K. 构建验证结论

- `:ai:testDebugUnitTest`：177 个测试全绿（含 ReasoningEffortMappings / 修复后的
  ResponseApiStreamDecoder）
- `:app:assembleRelease`（含 :web:buildWebUi）：BUILD SUCCESSFUL
  - app-arm64-v8a-release.apk（38.7MB）/ app-x86_64-release.apk（39.4MB）/
    app-universal-release.apk（48.8MB，含 web-ui）
- 版本号 1.00 / versionCode 177（`app/build.gradle.kts`）
