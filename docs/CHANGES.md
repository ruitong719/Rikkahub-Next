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

---

# Rikkahub Next — 2026-08-21 上游同步与 Token 阈值输入改造

## A. 上游同步对账（2.4.9 `0c52b62b` → `6b37912f`，共 12 commits）

对账方法：`git cherry`（patch-id 等价判定）+ fork 历史逐条核对。结论：8 个此前已合入、
1 个本次合入、3 个按决策跳过/改造，上游至此全部处理完毕。

| 上游 commit | 处理 | 说明 |
|---|---|---|
| `d1e8effc` style(ui): 移除推理等级选择器底部刻度 | **跳过** | 保留 fork `a47fab90`（显示全部 7 档标签），与上游方向相反；后续上游再动 ReasoningPicker 需手动解冲突 |
| `de888df2` fix(asr): DashScope 语音识别无文本输出 | 已合入 | fork `b9aaaf6f` |
| `97df86ec` fix(workspace): 修复 SAF 文件存在性判断 | 已合入 | fork `96f70a83` |
| `bca21d4d` fix: 修复 .agc 文件支持 | 已合入 | fork `c94e97ad` |
| `c88822d6` feat: 支持豆包搜索 | 已合入 | fork `3ac6896d` |
| `82758c36` fix(chat): 启用英文句首自动大写 | 已合入 | fork `98c4ae85` |
| `dca7f01c` feat: 正则支持排序 | 已合入 | fork `ae21cb95` |
| `693c2ce5` chore: bump to 2.4.10 | 已合入 | fork `da3ca8b1`（fork 现为 versionCode 178 / 2.4.10） |
| `3b4b80a4` fix: 修复混淆破坏 auth/jwt | 已合入 | fork `bf9bf81e` |
| `85402745` fix(thinking): 忽略正文中内联 think 标签 | **本次合入** | fork `efdbe8d2`，零冲突（fork 原文件与上游父提交逐字节一致，合入即上游原版）；仅消息开头的 `&lt;think&gt;` 转为推理，正文中的字面标签保留显示；有原生 reasoning 时跳过解析；附 `ThinkTagTransformerTest` 8 个用例 |
| `adf333ec` feat(assistant): 上下文条数改数字输入 | **不直接合入** | `contextMessageLimit` 字段已被滚动摘要功能整体移除，上游改的是不存在的 UI；其交互模式已适配到 Token 阈值输入框（见 B） |
| `6b37912f` docs: 移除 claude.md | 跳过 | fork 已在 `15525bea` 删除根目录 CLAUDE.md，空操作 |

> 注意：fork 内 `upstream` 远程引用仍停在 `3b4b80a4`，本次对齐用的是本地 clone
> （临时 remote `local-upstream` → `/workspace/rikkahub`）；下次同步前先 fetch 更新。

## B. Token 阈值滑条 → 数字输入框（`d6629e20`）

- 参考上游 `adf333ec` 的交互（滑条换数字输入 + 失焦校验 + 过小自动重置弹窗），
  适配到 fork 的 `rollingContextCompressionThresholdTokens`
- 输入支持纯数字（`32000`）与 K/M 后缀（`32K` / `1.5M`，大小写与中间空格均可），
  非法字符不进入输入框；`AssistantBasicPage.kt` 新增
  `parseTokenThresholdInput` / `normalizeTokenThreshold`，移除滑条专用的
  `snapContextTokenThreshold`（含"低于最小值一半归零"吸附——打字输入不需要）
- 合法值（0 或 ≥ 4000）输入时实时生效；1~3999 实时标红；失焦 / Done 统一校验：
  非法内容回滚为当前值，1~3999 自动重置为 4000 并弹窗提示，`32K` 写法规范化为 `32000`
- 0 = 默认阈值；下方格式化显示（32K/128K/1M）与警告文案保留
- 键盘用文本键盘（数字键盘打不出 K/M），ImeAction Done；状态沿用本文件 topP
  输入框的 `remember(assistant.id)` 模式，避免打字过程中文本被重写、光标跳动
- 新增中英文字符串各 2 条（hint + 重置弹窗）；ja/ko/ru 缺失时回退英文默认

## C. 验证情况

- 本沙箱无 Android SDK，未跑 Gradle 构建；已做：strings XML（values / values-zh）解析通过、
  阈值解析逻辑以等价正则模拟 13 组输入全部符合预期（含超大值饱和到 Int.MAX，不崩溃）、
  无 `snapContextTokenThreshold` 残留引用、新增 import 均为 Compose/M3 标准 API、
  `UIMessage.assistant()` 工厂与 junit 依赖存在性核对
- 待下次正常构建时回归：`:app:testDebugUnitTest`（含新合入的 ThinkTagTransformerTest）
  与助手编辑页手动验证

---

# Rikkahub Next — 2026-08-21 Shell 实时输出直播（实验性）

## A. 功能概述

阻塞式 `workspace_shell` 改造为可流式直播 stdout/stderr：命令执行中在聊天内实时滚动显示
尾部输出（详情弹窗自动吸底 + 折叠态最新一行）。设置 → 偏好 → 常规新增
「Shell 实时输出（实验性）」开关，**默认关闭**；关闭时行为与原版本完全一致。

## B. 架构（纯展示旁路，ai 模块零改动）

```
ProotShellRunner 收集线程 --chunk--> ShellRunMonitor(Koin single)
                                        │ 按 toolCallId 分槽
ShellToolUI(loading 时订阅) ────────────┘ 弹窗实时滚动 + 折叠态单行
```

最终 tool output 与消息持久化完全不变; monitor 为内存态, App 被杀后直播丢失
（与 SubAgentRunMonitor 同一决策）。

## C. 各层改动

- **workspace 模块**（接口零改动）：`WorkspaceShellContext` 新增可选 `onOutput`
  回调字段，`StreamCollector` 读循环逐块回调（解码由 BufferedReader 完成，
  UTF-8 多字节边界安全）；`WorkspaceManager.executeCommand` 加同名透传参数；
  回调不传时行为与原来逐字节一致
- **身份注入**：`GenerationHandler` 执行工具处包一层
  `withContext(ShellRunKey(tool.toolCallId))`，工具侧用
  `coroutineContext[ShellRunKey]` 取回调用身份——不改 `Tool.execute` 签名；
  `ShellRunKey` 为通用机制，未来其他需要调用身份的工具可复用
- **ShellRunMonitor**（新文件，`data/ai/`）：按 toolCallId 存 `ShellRunState`
  （command/cwd/startedAt/running/stdoutTail/stderrTail）；tail 窗口 16K chars、
  截断对齐换行防半行；条目上限 16 自动淘汰最旧已结束条目；
  `StateFlow.update` 原子 + conflation 天然节流（UI 每帧最多消费一个值），
  无需显式节流协程
- **工具层**（`createShellTool`）：开关开启时走新增的
  `WorkspaceRepository.executeCommandStreaming` 并喂 monitor，`finally` 中
  `finish()`（取消/超时路径同样收尾）；关闭时走原 `executeCommand` 阻塞路径。
  runKey 取 `coroutineContext[ShellRunKey]?.id`，缺失时回退 conversationId/workspaceId
- **UI**（`ShellToolUI`）：`hasSummary` 在 loading 态也保留摘要位；
  Preview 弹窗在 content 为空（执行中）时插入实时区——stdout 尾部 +
  stderr 红色尾部，新输出到达 `animateScrollTo` 自动吸底；折叠态 Summary
  显示最新一行（120 chars 截断）。命令结束后消息更新到达，
  自动切回完整输出渲染，与原有行为无缝衔接
- **设置**：`DisplaySetting.enableShellLiveOutput = false`（带默认值，旧数据兼容）；
  设置页偏好-常规新增开关行；新增中英文字符串各 3 条

## D. 已知限制

- 非 tty 管道下 stdio 为块缓冲，个别程序（python 裸 print 等）输出成块到达而非
  逐行——管道固有行为，bg 任务日志相同；提示词可建议 `stdbuf -oL`，本期不修
- 直播为进程内内存态，App 被杀后丢失（最终结果以消息里的完整 output 为准，无损）
- UI 按 toolCallId 精确匹配，无歧义；toolCallId 缺失的异常路径下直播静默降级为不显示

---

# Rikkahub Next — 2026-08-21 上游同步（`6b37912f` → `c167c70e`，共 4 commits）

对账方法：`git cherry`（patch-id）确认 `6b37912f` 及之前全部处理完毕（见上文对账表），
本次仅新增上游 4 个提交，按时间顺序逐个 cherry-pick，全部保留原作者署名。

| 上游 commit | fork commit | 说明 |
|---|---|---|
| `f167a855` chore: 适配 deepseek-v4-flash-vision-exp 能力 | `00234b94` | 零冲突 |
| `8b3a1f84` feat: 适配小米 MiMo 思考参数（#1751） | `3a52630c` | ChatCompletionsAPI.kt 因 fork 的 moonshot K2.6 keep 逻辑自动合并；MiMo 块落在 bigmodel 与 moonshot 之间，K2.6 逻辑完好 |
| `91b81fef` chore: 更新模型图标（gemma/kimi/qwen） | `6e3993dd` | 零冲突 |
| `c167c70e` feat: ModelRegistry 支持注册模型上下文长度 | `a882ce60` | 依赖 `f167a855` 先行（给 DEEPSEEK_V4_FLASH_VISION_EXP 补 contextLength），按序应用后零冲突 |

说明：fork 的"模型上下文窗口自动发现"是运行时 API 发现（`contextWindowTokensOrNull`），
与注册表新增的静态 `MODEL_CONTEXT_LENGTH` 互补，无重复实现。

验证情况：沙箱无 Android SDK 未跑 Gradle。已做：4 补丁按序 `git apply --check` 通过；
ModelRegistry/ModelDsl/测试文件在合入前与基线 `6b37912f` 逐字节一致（上游已测代码原样落地）；
合并结果逐文件人工核对，总差异 +95/-20 与 4 提交并集一致。待下次构建回归 `:ai:testDebugUnitTest`
（新增 ModelRegistryTest 上下文长度用例）。

至此上游 `master`（`c167c70e`）已全部同步完毕，无待处理提交。

## E. 验证情况

沙箱无 Android SDK 未跑构建。已做：11 个改动 Kotlin 文件括号平衡检查、
双语 strings XML 解析通过、引用 API（LocalSettings/settingsFlow/
collectAsStateWithLifecycle/Koin 注册）逐一确认存在。待真机构建回归：
开关关闭时 shell 行为与线上一致；开启后长输出命令（如 gradle build）弹窗直播效果。

---

# Rikkahub Next — 2026-08-22 后台任务自动拉起 + 生成中消息队列

本次两个功能直接提交到 `master`（`f3316b2a` / `3c20d5bf`），均为对现有机制
的补全：前者补上"后台任务完成主动通知 LLM"的闭环，后者补上"生成期间补充消息
不必打断"的交互。

## A. 后台任务完成自动拉起（`f3316b2a`，仅 ChatService.kt +73）

**背景**：plan.md 功能三拍板"提醒时机 = 下次生成前注入"，任务完成时没有任何
机制主动触发生成，用户必须手动发消息提醒 LLM 继续——与工具描述里
"when it finishes you will be notified automatically"的承诺不符。

**改动**：

- `ChatService` 新增 watcher：每 2s 扫描活跃会话，发现**本对话绑定**的后台任务
  已完成（done/failed）且未提醒过（`notified=false`）时，自动触发一次生成
- 触发守卫：
  - 会话生成中跳过（不打断进行中的生成）
  - 存在挂起审批/未恢复工具跳过（不打断审批流程）
  - 所有挂起点（settingsFlow / workspace 查询 / listTasks）之后复查
    `isGenerating`，避免与用户消息/审批重复拉起
  - 与 `sendMessage` 一样 `session.setJob` 登记生成任务——用户此刻主动发消息
    会取消本次拉起（用户优先）
- 提醒内容仍由 `BackgroundTaskReminderTransformer` 在生成前注入
  `<bg_reminder>` 并标记 notified，LLM 看到后自行调用 `workspace_bg_output` 继续

**边界**：只拉起活跃会话（聊天页打开中）；App 内会话 5s 空闲回收，关闭的
聊天页不自动生成。

## B. 生成中消息队列（`3c20d5bf`，8 文件 +163/-22）

**背景**：生成期间 `sendMessage` 无条件 `previousJob?.cancel()`，补充消息必须先
打断。目标：LLM 输出时用户输入进入队列，在**合适时机自动插入**；也可
**强制打断立即插入**。

### 时机设计（快路径 + 慢路径兜底）

- **快路径（核心）**：`GenerationHandler.generateText` 新增
  `onPollQueuedMessages: () -> List<UIMessage>` 回调，在 Step 循环顶部
  （工具执行完、下一轮 LLM 调用之前）drain 队列并追加到 messages、
  `emit(GenerationChunk.Messages)`——与工具结果同链路，UI 显示与持久化自动生效。
  LLM 连续工具调用时，补充消息最快下一轮就被看到；插入不会推翻已执行的
  工具决策（上一步已定、下一步未定，天然缝隙）
- **慢路径（兜底）**：4 个生成入口（sendMessage / regenerateAtMessage /
  handleToolApproval / 自动拉起 watcher）的 Job `finally` 统一
  `flushQueuedMessages`——正常结束、报错、被打断都触发；因此
  **"打断并立即发送队列"零额外逻辑**：`stopGeneration` 取消 Job → finally
  自动把队列发出。快路径已消费的队列，兜底时为空，不会重复

### 交互（单按钮两次点击机制）

生成中同一按钮位状态切换，不新增按钮：

| 状态 | 按钮样式 | 点击行为 |
|---|---|---|
| 队列空 + 有输入 | 发送（↑，primary） | **入队**（Toast 提示，输入框清空） |
| 队列非空 | 打断（✕，errorContainer）+ 队列数角标 | **打断并立即发送队列** |
| 无输入 | 打断（✕） | 只打断（保留现状） |
| 非生成 | 发送 | 发送（现状） |

### 各层改动

- **`ConversationSession`**：新增内存队列 `StateFlow<List<UIMessage>>` +
  `enqueue` / `drainQueue`（`update` 原子取出清空，防并发丢消息）
- **`ChatService`**：
  - `sendMessage` 生成中入队并返回 `true`（调用方据此 Toast）；
  - 抽取 `sendMessageInternal` / `launchSendUserMessage`
    （`waitPrevious` 参数：flush 场景不能 cancel/join 上一个 Job——它正在
    finally 中调用本方法，否则自杀/死锁）；
  - 新增 `flushQueuedMessages`（慢路径，多条合并为一条用户消息发送）、
    `getQueuedMessagesFlow`（驱动 UI 角标）
- **`GenerationHandler`**：循环顶部插入队列消息
- **`ChatInput` / `ChatPage` / `ChatVM`**：单按钮状态切换 + 角标 +
  入队 Toast 接线；`ChatVM.handleMessageSend` 返回是否入队
- **strings**：新增 `message_queued_toast`（中/英）

### 顺手修复的竞态

`ConversationSession.setJob`：旧 Job 完成回调原来无条件把 `_generationJob`
置 null——flush 场景旧 Job 完成时可能已登记新 Job，会把新 Job 顶掉
（`isGenerating` 误报 false，UI 状态错乱）。改为 `===` 身份校验，仅当当前
登记的仍是该 Job 时才清空。

### 已知限制

- 队列在内存，聊天页关闭（会话 5s 空闲回收）后清空
- 生成中长按发送按钮仍为打断（快捷打断）
- web 端 `sendMessage` 生成中同样入队（无角标提示）
- 快路径多条队列消息逐条插入；慢路径 flush 合并为一条

## C. 验证情况

沙箱 Ubuntu：kotlinc 语法检查通过；`./gradlew :app:compileDebugKotlin`
（`-x :web:buildWebUi`）BUILD SUCCESSFUL（含全部库模块类型检查）。

待真机回归：
- 自动拉起：后台任务完成 → 无操作下 LLM 自动继续
- 快路径：长工具链生成中入队 → 下一轮 LLM 即看到补充
- 打断立即发送：入队后点打断 → 停止当前生成并立即发送队列
- 入队后生成自然结束 → 队列自动发送
- 审批流 / 重新生成 / 用户主动发送与队列的竞态

---

## D. Review 修正批次：Token 阈值控件 + 消息队列/自动拉起加固

### 1. 上下文 Token 阈值控件：预设档位 + 对话量换算

- **预设档位 chips**：默认 / 64K / 128K / 256K / 512K 一键选择（FlowRow +
  FilterChip），自定义输入框保留（K/M 简写解析不变）
- **引入上限** `MAX_CONTEXT_TOKEN_THRESHOLD = 512K`：`normalizeTokenThreshold`
  收拢到 [4K, 512K]，此前无上限（"999M" 会原样入库）；超范围弹窗提示已自动调整
  （`assistant_page_context_message_limit_out_of_range` 取代 `_too_small`）
- **携带量换算行**：按该助手最近一次会话的真实消息长度（`estimateContextTokens`）
  估算阈值 ≈ 携带最近 N 条消息（约 M 轮对话）；`AssistantDetailVM` 新增
  `recentConversation`（复用 DAO 现成的 limit=1 查询）。机制不变：存储仍是
  token 阈值，滚动摘要逻辑零改动
- 最小值常量改用 `RollingContext.kt` 的 `MIN_ROLLING_CONTEXT_THRESHOLD_TOKENS`，
  消除两处定义的漂移风险；输入框展示统一走 `formatThresholdInput`
  （0 显示 "0"，其余 "64K"/"1M" 形式，保证可解析回环）

### 2. 后台任务自动拉起加固

- **只拉起活跃会话**：补上 `refCount > 0` 检查与注释对齐。页面关闭后的未提醒
  任务留给下次生成前的提醒注入兜底，不再出现无人观看的后台生成或
  重开聊天页即凭旧任务自动生成
- **失败退避**：拉起结束后按结果判断——任务仍未被消费（生成在提醒注入前就
  失败，如 provider 解析失败）计一次连续失败，达 3 次停止重试并报错提示手动
  查看。原实现在这类前置错误上会每 2s 无限重试（刷错误列表 + 反复计费调用）
- **自我续跑上限**：连续自动拉起 ≥3 次暂停，防止模型每轮都开新后台任务形成
  无用户输入的自我续跑；用户发消息/审批时计数清零（计数器内聚
  `ConversationSession.autoResumeStreak / autoResumeFailures`）
- **判定收敛**：新增 `WorkspaceBgManager.listUnNotifiedFinishedTasks`，watcher
  与提醒 transformer 共用 DONE/FAILED && !notified 判定，防状态枚举加值时
  两处过滤条件漂移
- **竞态修复**：`ConversationSession.beginGenerationIfIdle` 预留式登记
  （占位 Job 保证预留到真实登记之间 `isGenerating` 为真），消除 watcher
  二次空闲检查与 setJob 之间被用户发送穿插导致的双生成窗口

### 3. 生成中消息队列修正

- **插入失败回滚**：快路径 emit 失败（被打断等，此时消息尚未持久化）→
  新回调 `onRequeueQueuedMessages` 把消息按原序放回队首
  （`requeueFront`），消除"toast 已入队但消息静默消失"的丢失窗口
- **快路径/flush 行为一致**：入队前先做 `preprocessUserInputParts`
  （正则变量替换）；flush 跳过二次预处理（`launchSendUserMessage` 新增
  `alreadyProcessed`）；队列元素改为 `QueuedUserMessage(message, answer)`，
  flush 按任一条要求生成就触发（保留长按"发送但不回答"的意图）；
  多条合并为一条发送维持不变（逐条会引发 N 连生成）
- **交互去陷阱**：打断条件从"队列非空 或 无输入"收窄为仅"无输入"——
  有新输入时点击一律入队（原实现在队列非空时会无声丢弃刚输入的文字并
  直接打断），图标始终如实反映单击行为；Toast 文案同步更新

### 4. 清理

- `FloatingActivityHub` 待办订阅改用注入的 `appScope`，移除自建且从不
  取消的 CoroutineScope；移除 ChatService 失效的 `BgTaskStatus` import

### 验证

kotlinc 语法级检查通过；`:app:compileDebugKotlin` BUILD SUCCESSFUL。
待真机回归：档位切换与换算行数值合理性、超限自动调整弹窗、
自动拉起限流（连续失败 / 连续续跑）、打断时排队消息回滚、
入队消息的正则变量替换生效。

---

## E. 后台任务点击查看实时输出

- **入口**：后台任务列表（BackgroundTaskSheet）的行整体可点击，
  打开 `BackgroundTaskOutputSheet` 输出详情
- **实时刷新**：运行中的任务每 1s 轮询 `WorkspaceBgManager.output()`
  （stdout.log 尾部窗口：500 行 / 64KB），新输出到达自动吸底；
  轮询中同步刷新 taskInfo，任务结束即停轮询、允许自由回看
- **展示**：命令标题 + 状态色（运行中/失败/完成）+ 短 ID + "实时输出中…"提示；
  stdout.log 超过 64KB 时显示"仅显示尾部"提示；等宽小字号渲染，
  高度上限 420dp 内滚动
- 详情 sheet 独立于列表面板生命周期：列表清空/关闭后仍可回看最后一次打开的任务；
  输出只读，kill/delete 仍在列表行操作

---

## F. 恢复「不检查更新」开关（与暂停检查并列）

**背景**：`9215976e` 曾加入 `DisplaySetting.showUpdates` 永久关闭更新显示的开关，
`5b390510` 将其替换为"暂停至某日"机制后该能力丢失。本次以开关形式恢复并升级：

- **数据层**：`DisplaySetting.disableUpdateCheck: Boolean = false`，
  与 `updateCheckDisabledUntilEpochMillis` 并存；永久关闭优先于临时暂停
- **检查闸门（两处）**：`ChatVM.updateState` 与 `ChatDrawer` 的 UpdateCard
  均在 `disableUpdateCheck=true` 时不再触发/展示
- **设置页（通知偏好）**：新增"不检查更新"开关；开启时下方"暂停更新"项
  置灰不可点（onClick 置空 + 38% alpha），描述文案切换为"已关闭自动检查"
- 实现注意：CardGroup 的 `item {}` 工厂非 composable 上下文，置灰的
  ListItemColors 需在页面 composable 中预计算；本项目 material3 版本
  使用旧参数名（headlineColor/supportingColor/trailingIconColor）

---

## G. 悬浮球：透明度 / 自定义图标 / 手势扩展 / 滑块卡顿修复

### 滑块卡顿修复（根因）

颜色（HSL 三滑杆）、大小、展开宽高滑块原先每个拖动 tick 都直接
`vm.updateSettings()` 写 DataStore（磁盘序列化）+ settingsFlow 触发全应用
重组。改为：拖动期间只更新本地草稿状态驱动 UI，`onValueChangeFinished`
才提交一次；`ColorPickerRow` 新增 `onColorChangeFinished` 回调（HSL 文本
输入属低频离散提交，直接视为完成）。

### 透明度调节

- `floatingBubbleOpacity`（20–100%，默认 100）滑条；
- Service 侧 `applyBubbleAlpha()`：基础透明度 × 半隐藏系数（贴边再乘 0.5），
  与原有贴边半隐藏逻辑互不干扰。

### 自定义图标

- 设置项走系统 Photo Picker（PickVisualMedia），选中图片居中裁方、采样到
  256px 后拷贝进应用私有目录（`filesDir/floating_bubble_icon.png`，避免
  相册 URI 授权失效），路径存 `floatingBubbleIconPath`；
- `BubbleView` 支持图标绘制：BitmapShader 圆形裁剪 + 四周 2dp 颜色描边，
  透明度跟随整体；清除图标（删除文件 + 置空路径）恢复纯色圆球；
- Service 侧仅在路径变化时于 IO 线程解码并 post 到主线程更新。

### 手势扩展

- **长按（按住 500ms 不动）**：直接回 App 主界面（等同原双击的跳转），
  实现在 floatingx `onTouch` 回调内计时，触发后以时间戳吞掉松手补发的单击
  （700ms 守卫窗口）；
- **双击改为「暂停显示悬浮球」**：隐藏悬浮球与面板，服务保活
  （`tempHidden` 静态标记）；下次回到 App 主界面（RouteActivity.onResume）
  发送 `ACTION_RESUME` 自动恢复；setupBubble 在暂停期间不重新展示，
  防止设置页生命周期误触发提前复显；
- 面板标题栏新增「暂停显示」按钮（ViewOff 图标），与双击同路径。

### 已知边界

- 暂停显示后若进程被杀（服务 START_NOT_STICKY），下次启动悬浮球直接恢复；
- 查看暂停期间任务被删除等极端时序沿用 runCatching 兜底。

---

## H. 助手与供应商全部可删除/可编辑

**背景**：内置助手（DEFAULT_ASSISTANTS_IDS）不显示删除入口；内置供应商
（builtIn）隐藏删除按钮、协议切换被禁用、API 路径输入框置灰。且即使绕过
UI 删除，设置加载器每次读取都会把缺失的默认项重新补回——双重保护导致
"永远删不掉"。

### 改动

- **加载器**：Settings 新增 `deletedAssistantIds` / `deletedProviderIds`
  集合，自动补回默认项时跳过用户显式删除过的 ID；助手列表为空时兜底补一个
  全新助手（getCurrentAssistant 空列表会崩，不允许真空）
- **助手**：删除入口不再按白名单隐藏；`removeAssistant` 记录被删内置 ID，
  并在删除当前选中助手时把指针移到剩余助手的第一个；删光后自动补一个全新
  默认助手
- **供应商**：删除按钮对 builtIn 同样显示，删除时记录 ID 防止复活；
  协议类型切换（OpenAI/Gemini/Claude）对内置供应商开放；API 路径输入框
  不再因 builtIn 置灰
- 内置供应商的名称/描述/图标仍由加载器同步覆盖（保持上游预设更新能力），
  可编辑的是连接配置本身

### 修复（2026-08-22）

- `deletedAssistantIds` / `deletedProviderIds` 此前只存在于内存中的 Settings
  对象：`update()` 没有写盘、冷启动读取也没有读回，导致删除内置项仅在当前
  进程有效，**重启应用后被删项全部复活**。现新增 DataStore key
  （`deleted_assistant_ids` / `deleted_provider_ids`，stringSet），读取与
  写入双向打通；WebDAV 备份恢复走 `settingsStore.update()`，自动覆盖。

---

## I. tok/s 只按纯吐字时长计算（2026-08-23）

**问题**：NerdLine 的 tok/s = completionTokens / (finishedAt − createdAt)。
agentic 循环中工具输出写回同一条助手消息，多轮 LLM 输出与工具执行时间全部
落入 createdAt..finishedAt 区间——只要带一次工具调用，分母就被执行时间稀释，
速率严重偏低。

### 改动

- **UIMessage** 新增 `generationDurationMs`（持久化字段，旧消息为 0）：
  纯 LLM 输出累计时长，agentic 多轮各自计时后累加，不含工具执行与轮次间隔
- **流式路径**：`StreamChunkHandler` 在首个 chunk 到达时打点（排除连接建立
  与 TTFT），`Finish` 时把本轮窗口累加进消息；handler 每轮独立实例，
  工具执行期间不计时
- **非流式路径**：拿不到首包时刻，由 `generateInternal` 记请求起点传入
  `handleTextGenerationResult`，整轮请求时长计入（含 TTFT）
- **展示层**：tok/s 分母优先取 `generationDurationMs`，为 0（旧消息/流异常
  中断）回退总时长；旁边的总耗时秒数保持原语义不变

---

## J. 客户端身份预设 + OpenCode Zen 接入（2026-08-23）

**背景**：部分服务端按客户端指纹放行/拒绝请求。方案主体借鉴 hermes-agent
的按 host 分发实践，OpenCode Zen 部分对照 opencode 官方客户端源码实现。

### 客户端身份（Client Identity）

- **预设表** `ClientPresets`：Claude Code（api.kimi.com 必须 claude-code UA，
  否则 403）、Codex CLI（chatgpt.com 的 CF 白名单按 originator + UA 形态识别，
  需要 originator: codex_cli_rs 组合）、OpenCode、Gemini CLI、Cherry Studio、
  Chatbox、curl
- **按 host 自动注入**：baseUrl 命中预设 host 时自动应用对应身份（零配置）；
  供应商级自定义身份优先于自动预设，全局 UA 兜底
- **数据**：`NetworkSetting.providerIdentities`（供应商 UUID → header 表），
  拦截器用 OkHttp `header()` 替换语义覆盖全局 UA
- **空 apiKey 通用规则**：匹配到供应商且其 key 为空时整个移除 Authorization——
  Zen 免费档对任何未知 Bearer 直接 401；顺带修掉其他供应商发送 `Bearer `
  空值的行为
- **UI**：供应商详情页新增「客户端身份」卡片（预设 chips / UA 输入 / 自定义
  header 行编辑）；全局网络设置页 UA 字段挂同一组预设 chips

### OpenCode Zen

- 接入信息来自 opencode 官方仓库：base URL `https://opencode.ai/zen/v1`，
  OpenAI-compatible；Go 订阅端点为 `/zen/go/v1`
- 官方客户端 UA 格式 `opencode/{channel}/{version}/{client}`，本仓库当前
  1.18.21 → `opencode/latest/1.18.21/cli`，另发 `x-opencode-client: cli`；
  x-opencode-session/request/project 为会话级动态 id，Rikkahub 不发送
  （hermes 匿名调用验证非必需）
- 推荐供应商列表新增「OpenCode Zen」条目（免费档 keyless），模型靠
  GET /models 自动发现；免费目录含 grok-code、big-pickle、kimi-k2.5-free、
  glm-5-free 等 22 个 cost-0 模型

---

## K. 自定义图标映射 + 悬浮球图标来源重构（2026-08-23）

**背景**：内置图标预设（`AIIconMatcher` 正则表）未命中的供应商/模型只能显示
首字母；悬浮球自定义图标只有相册选图一条路且无预览。统一引入
SVG 源码 / 图片 URL / Emoji 三种图标来源。

### 自定义 AI 图标映射

- **数据模型** `CustomAIIcon(pattern, exactMatch, source)`，source 为 sealed
  `IconSource.Svg/Url/Emoji`（data/model/CustomAIIcon.kt）
- **存储**：Settings 新字段 `customAiIcons`，DataStore key `custom_ai_icons`；
  备份导出的是整个 Settings JSON，随 `settings.json` 自动进出 WebDAV 与本地备份，
  旧备份缺字段反序列化取默认空列表，双向兼容
- **匹配**：`matchCustomAIIcon()` 纯函数——精确条目优先，包含匹配不区分大小写、
  取最长关键词；优先级为 内置预设 > 自定义映射 > TextAvatar 首字母；
  JVM 单测覆盖（AIIconMatcherTest）
- **渲染**：仅改 `AutoAIIcon` 组件内部（koinInject SettingsStore 收集），
  ModelList/供应商设置/聊天气泡/搜索/TTS/导出等 8 处调用点零改动生效；
  SVG 转 base64 data URI 走 coil SvgDecoder（不套 CSS 染色，保留原色）
- **设置页**：`Screen.SettingCustomIcons` → 设置主页「模型与服务」分组新入口；
  列表管理 + 底部弹层编辑（关键词/精确开关/来源三选一/实时预览/校验）

### 悬浮球图标来源重构

- Settings 新字段 `floatingBubbleIcon: IconSource?`（key `floating_bubble_icon`），
  旧 `floatingBubbleIconPath`（相册 PNG）保留为回退链一环：新来源 > 旧 PNG > 纯色圆球
- 服务端渲染（FloatingBubbleService）：服务内独立 ImageLoader（SvgDecoder +
  OkHttp fetcher，不依赖 Compose 单例初始化时序）；coil 结果 drawable 光栅化为
  方形位图，Emoji 用 Canvas 直接绘制
- 设置 UI：相册选图按钮替换为「编辑」入口 + 共用 `IconSourceEditor`
  （SVG 粘贴/URL/Emoji 三选一 + 预览），清除按钮同时清新旧两代配置

### 共用组件

- `ui/components/ui/IconSourceEditor.kt`：来源类型切换 + 输入 + 校验 + 实时预览，
  映射设置页与悬浮球设置共用；`IconSourceImage` 支持尺寸参数供多处预览
- **Web 端** `/ai-icon?name=`：同样查映射表——SVG 直接回源码文本、URL 回 302、
  Emoji 回内联 SVG，与端内优先级一致

### 悬浮球展开窗口：命令行限高 + 运行统计行

- **命令行两行截断**：`CommandLine`（待办页进行中命令 + 实时输出页全部
  function_call 行）加 `maxLines=2 + Ellipsis`，长 shell 命令不再把上方内容顶出视口
- **统计行**：标签行与内容区之间新增一行——耗时秒数 / 输出 k token /
  工具调用次数 / tok/s，口径同聊天页 NerdLine（tok/s 用纯吐字时长
  `generationDurationMs`，不含工具执行间隙）
- **数据来源**：`FloatingActivityHub` 新增跨轮次累加器（RunAccumulator），
  agentic 多轮的 usage/工具数按消息 id 变更逐轮折叠；生成中每 500ms 刷新秒表，
  结束后冻结显示

---

## L. 上游同步 2.4.12（2026-08-25）

上游 `b270766f..e8293d35` 共 13 个 commit 逐条对账合入，详见
[UPSTREAM_SYNC.md](UPSTREAM_SYNC.md) #24–#36 对账表。要点：

- **新能力**：工作区终端后台运行+多Tab（`WorkspaceTerminalSessionManager`
  会话保活）；videogen 模块骨架与视频生成 API 层（阿里云/MiniMax/火山引擎，
  尚未接入 UI）；OpenRouter 请求带会话级 `session_id` 头
- **修复**：fork 会话未继承 folderId/workspaceCwd（抽 `createForkConversation()`
  复制 6 字段，附 JVM 测试；上游版引用 fork 已删字段致编译失败，`e07d6dd7` 修正）；
  Live Update 焦点通知胶囊无图标；供应商测试
  连接对话框补全五语言本地化；Chat Completions 空 tool schema 规范化（附测试）；
  ChatInput 键盘弹出时保持圆角；SnakeYAML 解析 skill frontmatter；proot lib 更新
- **版本**：fork 自行 bump 至 versionCode 180 / versionName 2.4.12（对齐上游语义）
- **遗留**：无

