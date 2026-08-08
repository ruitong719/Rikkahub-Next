# RikkaHub 工作区新功能开发计划

分支：`dev`（基于 `master`，HEAD `576f2341`）
状态：**功能一~八全部已实施**（HEAD `2bce87cc`）；功能六（Subagent）、功能七（Todo）、功能八（per-tool prompt）为本次新增

---

## 背景（已确认的架构事实）

| 事实 | 位置 |
|---|---|
| 工作区工具统一在 `createWorkspaceTools()` 定义 | `app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt:36` |
| 工具在 ChatService 组装时注入 | `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:540` |
| 工具审批默认值表 `WorkspaceToolDefaultApprovals` | `WorkspaceTools.kt:24` |
| 工具内可通过 `getKoin().get<T>()` 取依赖（现成模式） | `WorkspaceTools.kt:304` |
| rootfs 绝对路径 → 宿主机真实文件统一映射 `resolveRootfsPath()`（支持 `/workspace`、bind mount、rootfs 内部路径） | `workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt:117` |
| PRoot bind mount：`-b <source真实路径>:<target>`，**source 必须是宿主机真实 File**，SAF content:// URI 不能直接挂载 | `workspace/.../ProotShellRunner.kt:76` |
| `bindMounts` 当前是构造时传入的**全局静态列表**（/skills、/upload、/tool_outputs），所有工作区共享；per-workspace 挂载需要改为动态计算 | `app/.../di/RepositoryModule.kt:60` |
| 工作区实体 `WorkspaceEntity` 已存工具配置（`toolApprovals`），可照此模式扩展 | `app/.../data/db/entity/WorkspaceEntity.kt` |
| DB 版本 24，Room AutoMigration；加可空列可直接 `AutoMigration(from=24, to=25)` | `app/.../data/db/AppDatabase.kt:41` |
| 项目尚无 SAF 目录树（OpenDocumentTree）使用；现有 UI 导出是单文件 CreateDocument | `WorkspaceDetailPage.kt:117` |
| 工作区详情页 Basic tab 已有"工具审批"卡片模式可复用 | `WorkspaceDetailPage.kt:401` |
| `androidx.documentfile` 未声明依赖（需新增） | `gradle/libs.versions.toml` |

---

## 功能一：导出工作区文件/文件夹到手机指定目录（LLM 工具）

### 核心机制

Android 10+ 分区存储下 App 不能任意写手机目录，标准做法是 SAF：

1. 用户在工作区设置里通过系统文件夹选择器（`OpenDocumentTree`）**一次性**选定导出根目录；
2. App 用 `takePersistableUriPermission()` 持久化读写权限；
3. LLM 调用工具时把 rootfs 文件/文件夹**递归拷贝**到该授权树目录下。

### 工具语义

```
workspace_export_to_phone
  source:     必填。rootfs 内绝对路径（/workspace/xxx、/root/xxx、/skills/xxx 均可）
  target_dir: 可选。导出根目录下的相对子路径，如 "exports/2026-08"，缺省为根目录
  overwrite:  可选。默认 false = 目标已存在则跳过；true = 覆盖
  审批：默认需要用户确认（写手机存储）
```

返回 JSON：文件数、目录数、总字节、覆盖/跳过/失败明细。

### 实施步骤

1. **依赖**：`gradle/libs.versions.toml` 加 `documentfile = "1.1.0"`；`app/build.gradle.kts` 加 `implementation(libs.androidx.documentfile)`
2. **数据层**（导出目标按工作区持久化）
   - `WorkspaceEntity` 加可空列 `exportTargetUri: String?`
   - `WorkspaceDAO.updateExportTargetUri(id, uri, updatedAt)`
   - `AppDatabase` version 24→25，`AutoMigration(from=24, to=25)`
   - `WorkspaceRepository.setExportTargetUri/clearExportTargetUri`
3. **导出核心**（新文件 `app/.../data/files/WorkspacePhoneExporter.kt`）
   - `export(workspaceRoot, sourcePath, treeUri, targetDir, overwrite): ExportResult`
   - `WorkspaceManager.resolveRootfsPath()` 解析 source → 递归遍历
   - `DocumentFile` 建目录/文件，`openOutputStream` 流式拷贝（不整文件载入内存）
   - 安全：`target_dir` 拒绝绝对路径/`..`/空段；跳过 `.l2s.`、符号链接；总大小上限 1GB；逐文件 `runCatching` 不中断整体；循环内 `ensureActive()` 支持取消
   - 附 `resolveTreeDisplayName(context, uri)` 供 UI 显示目录名
   - `di/RepositoryModule.kt` 注册 `single { WorkspacePhoneExporter(get(), get()) }`
4. **工具**（新文件 `app/.../data/ai/tools/WorkspaceExportTools.kt` + 注册）
   - `createWorkspaceExportTool()`；`WorkspaceTools.kt` 的 `createWorkspaceTools()` 加入
   - `WorkspaceToolDefaultApprovals` 加 `"workspace_export_to_phone" to true`
5. **UI**（工作区详情页 Basic tab）
   - `WorkspaceDetailVM`：`setExportTargetUri/clearExportTargetUri/exportTargetDisplayName`
   - 新增 `WorkspaceExportTargetCard`：当前目标目录名、"选择目录"（OpenDocumentTree + 持久化权限）、"清除"、说明文案
   - `workspaceToolApprovalItems()` 增加该工具条目
   - `values/strings.xml` 新增英文文案（只加 base）
6. **测试与验证**：纯函数（target_dir 清洗）可加 JVM 单测；**沙箱无 Android SDK，无法 gradle 编译验证**，需在 Android Studio assembleDebug 确认

### 深度复盘补充（已并入设计）

1. **SAF 权限失效处理**：用户可能在系统设置撤销 App 存储权限 → 工具执行捕获 `SecurityException`，报"导出目录权限已失效，请在工作区设置重新选择"；UI 启动/进入页面时检测并提示
2. **文件名 sanitize**：rootfs 文件名可能含 SAF 不允许的字符（`\ : * ? " < > |`）→ `DocumentFile.createFile` 失败时给出清晰错误；展示层做字符替换（实施时定策略：替换 vs 报错）
3. **overwrite 兼容**：部分 DocumentsProvider 不支持 `openOutputStream(uri, "w")` 截断 → 尝试截断失败后兜底"删旧建新"
4. **Room schema 导出**：v24→v25 autoMigration 依赖 schema 目录配置，实施第一步确认 `room.schemaLocation` 已配置

---

## 功能二：把手机指定目录"挂载"到工作区（类似 Linux /mnt）

### 核心约束（决定实现路径）

- PRoot `-b` 只认**宿主机真实路径**；SAF `content://` URI 无法直接挂载
- 因此必须"物化"：把 SAF 树复制到 app 私有目录（`filesDir/mnt/<mountId>/`），再把该私有目录 bind mount 到 rootfs `/mnt/<name>`
- 读写都发生在物化缓存上；SAF 与缓存之间需要同步策略

### 已确认决策（用户拍板）

| 决策点 | 选择 |
|---|---|
| 同步模型 | **快照式双向**：挂载（add）时 SAF→缓存拉取；卸载/同步时缓存→SAF 写回；挂载期间改动集中在缓存 |
| 写回时机 | **手动触发**（卸载时 / 用户点同步 / LLM 调 sync 工具） |
| 配置范围 | **全局共享**一份挂载点列表，所有工作区看到相同的 /mnt 内容 |
| 与功能一关系 | **两者并存**：导出=一次性拷贝；挂载=长期可读写固定挂载点 |

### 设计

- **数据层（全局配置 → SettingsStore/DataStore）**
  - 挂载列表存 `SettingsStore`，`stringPreferencesKey("workspace_mounts")`，JSON 数组
    `[{ id, name, treeUri, lastSyncAt }]`（参照 MCP_SERVERS/WEBDAV_CONFIG 模式）
  - 无需 DB 迁移（导出目标仍 per-workspace 存 WorkspaceEntity，两处归属不同，各有理由）
- **挂载缓存目录**：`filesDir/mnt/<mountId>/`，一个挂载点一个目录；PRoot 动态 bind 到 `/mnt/<name>`
- **MountManager**（新类 `app/.../data/files/WorkspaceMountManager.kt`，注册进 Koin）
  - `addMount(name, treeUri)`：校验 name（`[a-zA-Z0-9._-]+`，防路径注入）→ SAF→缓存全量拉取 → 保存配置
  - `removeMount(id)`：缓存→SAF 写回（确认后）→ 删缓存 → 删配置
  - `syncMount(id, direction)`：pull（SAF→缓存）/ push（缓存→SAF），增量：目标已存在且大小相同则跳过
  - `listMounts()`：配置 + 状态 flow
  - **删除语义 v1 不做**：push 只增改、不删除 SAF 中已不存在的文件（避免误删手机文件，风险写入文档）
  - 拉取/写回复用功能一的流式拷贝与大小上限逻辑
- **动态挂载表**（workspace 模块最小改动）
  - `WorkspaceManager.executeCommand(..., extraBindMounts: List<WorkspaceBindMount> = emptyList())`
  - `WorkspaceManager.resolveRootfsPath(..., extraBindMounts: List<WorkspaceBindMount> = emptyList())`
  - `WorkspaceRepository` 内部从 SettingsStore 读挂载列表 → 把缓存目录转成 `WorkspaceBindMount(source=filesDir/mnt/<id>, target=/mnt/<name>)` 传入 manager
  - 现有调用方零改动（默认参数）；`ProotShellRunner`、`WorkspaceShellContext` 无需改
  - bind 是**每次执行命令时动态计算**，无需常驻进程或启动挂载
  - 文件工具自动生效：`workspace_read_file` / `workspace_shell` 天然支持 `/mnt/<name>`（resolveRootfsPath 复用挂载表）
- **LLM 工具**（注册进 createWorkspaceTools）
  - `workspace_mount_list`：列出挂载点 + 状态（默认免审批）
  - `workspace_mount_sync`：同步指定挂载点，`mount` + `direction`(pull/push) 参数（涉及写 SAF，默认需审批）
  - v1 不暴露 add/remove 给 LLM（add 必须用户用 SAF 选择器交互；remove 会写回覆盖手机文件，风险高），由 UI 管理
- **UI**：工作区详情页 Basic tab 新增"手机目录挂载"卡片
  - 列出全局挂载点（名称、最后同步时间、状态）
  - "添加挂载"（OpenDocumentTree → 输入挂载名）/"卸载（写回并移除）"/"同步"按钮
  - `workspaceToolApprovalItems()` 增加 list/sync 两个工具条目

### 深度复盘补充（已并入设计）

1. **增量条件改为 size + mtime 双条件**：仅"大小相同跳过"会漏掉内容变了但大小没变的文件；SAF 的 lastModified 可靠，pull/push 都按"size 相同且 mtime 相同"才跳过
2. **pull 覆盖风险提示**：挂载期间 LLM 在工作区修改 `/mnt/<name>` 下的文件，用户再 pull 会用手机内容覆盖这些改动 → sync 工具描述明示"pull 会覆盖工作区内的修改"，UI 同步时二次确认
3. **挂载名唯一性**：添加时校验 name 全局唯一，避免两个挂载点同名互相遮蔽
4. **挂载列表读取性能**：`WorkspaceRepository` 每次 shell 命令都读 SettingsStore（异步 DataStore）成本高 → 挂载列表内存缓存 + 变更时失效（settings flow 收集或显式 invalidate）

---

## 功能三：持久化后台任务 + 完成提醒（LLM 工具）

### 核心约束（可行性调研结论）

- 每次 `workspace_shell` 是**一次性 proot 进程**，命令结束即退出（`--kill-on-exit` 清理全部子进程），后台进程无法存活
- 项目已有**长生命周期 proot 会话**实现可复用：`WorkspaceTerminalSession.kt`（常驻 `proot ... /bin/bash`，管道通信）→ 做无 UI 的 headless 会话
- 时间注入 = `TimeReminderTransformer`（`InputMessageTransformer` 每次生成前转换消息）→ "完成提醒"同模式
- `/workspace` 即 `filesDir`（bind mount），文件工具自动过滤 `.l2s.` 前缀 → 任务状态/输出放 `/workspace/.l2s.bg/<taskId>/`，App 直接读文件，天然兼容 LLM 文件工具

### 已确认决策（用户拍板）

| 决策点 | 选择 |
|---|---|
| 运行载体 | 常驻 headless proot 会话，生命周期 = App 进程（App 被杀任务丢失，v1 接受） |
| 绑定语义 | **绑定对话（conversation）**：任务创建时记录 conversationId，完成提醒只注入该对话 |
| 提醒时机 | **下次生成前注入**（用户发下一条消息时检查，简单可靠） |
| 并发限制 | 每工作区最多 **3 个**并发后台任务 |

### 设计（拆三个子功能，A→B→C 顺序实现）

**块 A：基础设施（headless 会话 + 任务状态机）**
- `WorkspaceBgSession`：每工作区一个常驻 headless proot bash（无 UI，`ProcessBuilder` 持 stdin/stdout 管道，交互式 bash 读 stdin）；首次 `bg_start` 时惰性启动
- `WorkspaceBgManager`（app 模块，Koin single）：`start/status/output/kill/list`，App 侧读文件轮询状态
- 任务状态目录 `/workspace/.l2s.bg/<taskId>/`（= `filesDir/<root>/files/.l2s.bg/<taskId>/`）：
  - `pid`、`status`(running/done/failed)、`exit_code`、`started_at`、`finished_at`、`conversation_id`、`notified`
  - `stdout.log`（stdout+stderr 合并，`2>&1`）
- 启动命令：会话内执行 `nohup <command> > .l2s.bg/<id>/stdout.log 2>&1 & echo $! > .l2s.bg/<id>/pid`，立即返回
- 并发控制：每工作区 >3 个运行中任务时拒绝新任务
- 清理：任务结束后保留日志文件（LLM 可继续读），由 LLM 显式清理或会话销毁时清理

**块 B：工具接口（注册进 createWorkspaceTools）**
- `workspace_bg_start(command, cwd?)` → bg_id；记录当前 conversationId（`createWorkspaceTools` 增加 conversationId 参数）；**默认需审批**
- `workspace_bg_status(bg_id)` → 状态/运行时长/退出码/pid；免审批
- `workspace_bg_output(bg_id, tail_lines?, max_bytes?)` → stdout.log 内容（支持增量 tail）；免审批
- `workspace_bg_kill(bg_id)` → 会话内 `kill <pid>`；**默认需审批**
- `workspace_bg_list()` → 当前工作区任务列表；免审批
- `WorkspaceToolDefaultApprovals` 与 UI 审批列表同步增加

**块 C：完成提醒（参考时间注入）**
- 新 `BackgroundTaskReminderTransformer : InputMessageTransformer`（构造注入 `WorkspaceBgManager` + conversationId）
- 在 `ChatService.kt:526` 的动态 `inputTransformers` 组装处追加（不能进静态 lazy 列表，因需构造参数）
- 每次生成前扫描**本对话绑定**的任务：`status ∈ {done, failed}` 且 `notified=false` →
  - 注入一条 SYSTEM 角色消息 `<bg_reminder>任务 <id> 已完成，退出码 X，耗时 YmZs，输出摘要: ...</bg_reminder>`
  - 标记 `notified=true`（写状态文件），不重复注入
- 输出摘要从 `stdout.log` 尾部截取（如最后 2000 字符）

### 深度复盘补充（已并入设计）

1. **孤儿任务清理**：App 被杀（决策接受的场景）后残留 `status=running` 状态文件但进程已死 → App 启动时扫描 `.l2s.bg/`，pid 不存在则标记 failed（保留日志供查看）；`bg_status` 也做同样兜底判断
2. **完成标志用 exit_code 文件而非 kill -0**：裸 pid 轮询会被 pid 复用误判；改为任务包装脚本 `bash -c '<cmd>; echo $? > .l2s.bg/<id>/exit_code' &`，完成 = exit_code 文件出现（更可靠），pid 仅作参考
3. **stdout.log 体积控制**：任务完成时若 stdout.log 超过阈值（如 10MB）截断保留尾部 10MB，避免无人清理占空间
4. **工作区删除时清理会话**：`WorkspaceRepository.delete` 时同步 kill 该工作区的 headless 会话（否则 proot 持续持有已删除的 rootfs）
5. **stdin EOF 陷阱**：headless 会话是管道 stdin，管道关闭 → bash EOF 退出 → proot 退出 → `--kill-on-exit` 杀任务；会话管理器必须严格持有管道不误关（与"App 被杀任务丢失"决策一致）
6. **无 pty 限制**：headless 会话无终端，需要 tty 的程序（交互命令）不可用；工具描述说明"仅适合非交互长任务"

### 实施顺序与拆分

1. 块 A（基础设施）→ 可独立测试（终端/UI 验证常驻会话）
2. 块 B（工具）→ LLM 可驱动后台任务
3. 块 C（提醒）→ 闭环：任务完成自动告知 LLM

每个块独立提交到 dev 分支，块间无硬耦合。

---

## 功能四：备份机制修复（已确认 bug，方案已深度复盘修正）

### 排查结论（已确认）

**Bug 存在**：备份数据库时用**裸文件复制**（`FileInputStream` 直接拷贝 `rikka_hub.db` + `-wal` + `-shm`），没有任何一致性保证。

证据链：
- 数据库显式启用 WAL：`setJournalMode(WRITE_AHEAD_LOGGING)`（`app/.../di/DataSourceModule.kt:56`）→ 最新事务先写 `-wal`，主 `-db` 文件落后
- `WebDavSync.prepareBackupFile`（`app/.../data/sync/webdav/WebDavSync.kt`）与 `S3Sync`（`S3Sync.kt`）均为裸复制，无 checkpoint、无备份 API、无写锁
- 聊天应用几乎持续写库（用户发消息/AI 回复），备份复制期间无任何同步

### 作者设计动机分析（供决策参考）

- 推测：作者采用"文件级完整性"思路（把 db 目录三个文件整体打包），**在低写入频率下（用户空闲时手动备份）大部分时候能碰巧拿到一致快照**，因此从未复现问题；shm 同目录顺手带上，未区分 wal/shm 语义；未用 backup API 可能与 requery（第三方 SQLite 实现）支持性有关
- 结论：竞态真实存在（用户已复现），修复必要；但**修复方案必须分层，保证"至少不比现状差"**

### 修复方案（修正版）

1. **备份主路径**：`db.backup(File)` 生成单文件一致快照 `rikka_hub_snapshot.db`（**实施第一步真机验证 requery 是否支持**）
2. **fallback**（backup API 不可用时）：`PRAGMA wal_checkpoint(FULL)` + 复制主库 + **保留复制 wal**（checkpoint 后 wal 极小，撕裂概率大降且兜底 checkpoint 后的新写入；**禁止只复制主库**，那会必丢 checkpoint 后新写入，比现状更糟）
3. **恢复端**：`AppDatabase.close()` + 覆盖 db + **删除旧 wal/shm** + 标记需重启，UI 提示用户重启 App 使恢复生效（Room 单例 close 后不可重开，不提示重启会崩）
4. **兼容旧格式**：zip 内 `rikka_hub.db`（旧）与 `rikka_hub_snapshot.db`（新）两种恢复路径都支持
5. **zip slip 防护**：新增/已有恢复路径统一 `resolve()` + 前缀校验（现有 upload 恢复的 `File(uploadFolder, fileName)` 存在 `../` 穿越漏洞，一并修复）
6. **备份范围（用户已确认）**：`settings.json` + 数据库快照 + `upload/` + `skills/`（已有）+ `fonts/`（已有）+ 新增：
   - `/workspace` = `filesDir/workspaces/<id>/files`（每个工作区的文件区）
   - `/root` = `filesDir/workspaces/<id>/linux/root`（rootfs 用户主目录，不含整个 rootfs）
   - zip 内路径如 `workspaces/<id>/files/...`，恢复时严格路径校验后原样还原
   - 体积提示：workspaces 文件可能很大，备份/上传耗时变长需在 UI 提示

### 验证清单

- [ ] requery `SQLiteDatabase.backup()` 支持性（真机/单元验证）
- [ ] 恢复后重启流程（Room 单例终态处理）
- [ ] 新旧备份格式双向兼容
- [ ] zip slip 回归测试（恶意路径拒绝）
- [ ] 备份范围扩展后 WebDAV/S3 上传大文件

---

## 功能五：备份 zip 生成工具（workspace 内创建 backup.zip）

### 需求（已确认，替代原"自动导出聊天数据"方案）

在工作区层面暴露一个工具，调用时复用"备份与恢复—导出到本地"的备份生成逻辑，在 `/workspace` 下生成 `backup.zip`，返回文件路径告知 LLM。

### 设计

1. **工具**：`workspace_create_backup`（注册进 `createWorkspaceTools`）
   - 无参数（v1 固定文件名 `backup.zip`，每次覆盖；如需要多份可后续加 filename 参数）
   - 执行流程：
     1. 调 `WebDavSync.prepareBackupFile(config)`（config = `webDavConfig.copy(items = BackupItem.entries)`，即"导出到本地"同款全量备份）生成 zip 到 cacheDir
     2. 把 zip 复制到当前工作区 files 目录（`/workspace/backup.zip`）：经 `WorkspaceRepository`/`WorkspaceManager` 取 `filesDir(root)`，`File.copyTo` 流式拷贝（不走 `importFile` 的冲突改名逻辑）
     3. 返回 JSON：`{ path: "/workspace/backup.zip", name, sizeBytes, createdAt }`
   - **默认需审批**（备份 zip 含全部聊天记录/设置/上传文件，隐私敏感）
   - `WorkspaceToolDefaultApprovals` + UI 审批列表同步增加
2. **与功能四的关系**：直接复用 `prepareBackupFile`；功能四将 `prepareBackupFile` 内部改为一致性数据库快照后，本工具自动受益（zip 内的 db 快照正确）
3. **LLM 使用**：拿到路径后可 `workspace_read_file`（zip 是二进制，更适合 `workspace_shell` unzip / python 处理）——在工具描述中提示
4. **隐私注意**：备份含全量数据，生成到工作区 = LLM 可读，需审批兜底

---

## 功能六：Subagent（子智能体，主 Agent 可调用的专家工具）

### 需求来源

用户调研了主流 Agent 框架的 subagent 实现（LangGraph / CrewAI / AutoGen / OpenAI Agents SDK / Claude Code / Semantic Kernel 等），总结出四种实现路径：**tool-based（函数调用）、handoff（控制权移交）、graph/workflow（子图编排）、message-passing（消息总线）**，以及通用工程要点：上下文隔离、权限隔离、结构化输出、防无限递归、可观测性。

### 已确认决策（用户拍板）

| 决策点 | 选择 |
|---|---|
| 触发方式 | **仅 tool-based**：主 Agent 通过工具调用启动 subagent，不引入 handoff/graph/消息总线 |
| 定义存储 | **SettingsStore JSON 列表**（与 Assistant/MCP_SERVERS 同模式，无 DB 迁移） |
| 模型 | 定义里**可选模型**，缺省继承主 Agent 当前模型（`assistant.chatModelId ?: settings.chatModelId`） |
| 审批 | **派发时一次审批**：`run_subagent` 按定义默认需审批；subagent **内部工具自动执行**，不再弹审批 |
| 上下文 | **继承主 Agent 系统提示/记忆 + 带入主 Agent 对话记录（过滤 think 过程），再叠加任务** |
| 执行方式 | v1 **同步阻塞 + 超时**；异步列为后续版本 |
| 嵌套 | **v1 禁止嵌套**：subagent 工具池不含任何 subagent 工具，无递归问题 |
| Skills | **共享扩展管理里的 Skills**：SubAgent 定义内 `enabledSkills` 按需启用（与 Assistant.enabledSkills 同模式）；skill 工具由 subagent 自己的启用列表构建，不走 allowlist |
| UI | 管理入口放**设置—通用设置—扩展管理**（`ExtensionsPage` 加一项）；Assistant 配置仍勾选可用 subagent（`subagentIds`）；运行过程按**普通 tool call** 展示 |
| 预设 | 内置 **3 个示例预设**（可删除、可复制修改） |

### 设计

#### 1. 数据模型（新文件 `app/.../data/model/SubAgent.kt`）

```kotlin
@Serializable
data class SubAgent(
    val id: Uuid = Uuid.random(),
    val name: String = "",                  // 显示名；slug 化后作为工具名的一部分
    val description: String = "",           // 工具描述：主 Agent 据此决定何时调用
    val systemPrompt: String = "",          // 追加到主 Agent system prompt 之后的专属提示
    val modelId: Uuid? = null,              // null = 继承主 Agent 模型
    val toolAllowlist: Set<String> = emptySet(), // 工具名或类别标签（见 §4），空 = 纯文本专家
    val enabledSkills: Set<String> = emptySet(), // 启用的 skill 名称（共享扩展管理里的 Skills，见 §4.1）
    val maxSteps: Int = 64,                 // 内部循环步数上限（主循环默认 256）
    val timeoutMs: Long = 120_000,          // 超时；超时返回 {status:"timeout"}
    val requiresApproval: Boolean = true,   // 派发该 subagent 是否需用户审批
)
```

#### 2. 存储（`app/.../data/datastore/PreferencesStore.kt`）

- 新增 `val SUBAGENTS = stringPreferencesKey("subagents")`（companion，参照 `MCP_SERVERS:118`）
- `Settings` 数据类（`PreferencesStore.kt:517`）加 `val subagents: List<SubAgent> = DEFAULT_SUBAGENTS`
- 读取（参照 `workspaceMounts:222`）：`subagents = preferences[SUBAGENTS]?.let { decode } ?: DEFAULT_SUBAGENTS`——**key 不存在才注入默认**，用户保存过（含删光）后以用户数据为准，天然满足"预设可删除"
- 保存（参照 `WORKSPACE_MOUNTS:402`）：`preferences[SUBAGENTS] = JsonInstant.encodeToString(settings.subagents)`
- `DEFAULT_SUBAGENTS` 定义 3 个预设（参照 `DEFAULT_ASSISTANTS:721` 模式，固定 id）：
  - `code-reviewer`：审查代码质量/安全/可维护性，只读分析不改文件；tools=`[workspace_read, workspace_shell]`；`requiresApproval=true`（有 shell）
  - `researcher`：调研问题、收集归纳信息；tools=`[search, workspace_read]`；`requiresApproval=false`（只读+搜索，低危）
  - `data-analyst`：在工作区运行脚本分析数据；tools=`[workspace_read, workspace_write, workspace_shell, workspace_bg]`；`requiresApproval=true`

#### 3. Assistant 关联（`app/.../data/model/Assistant.kt`）

- `Assistant` 加 `val subagentIds: Set<Uuid> = emptySet()`（参照 `enabledSkills` 模式：全局定义、per-assistant 启用）

#### 4. 工具 allowlist（类别标签 + 精确工具名）

`toolAllowlist` 元素支持两种：
- **类别标签**（UI 按组勾选），固定映射表：

| 标签 | 匹配的工具（前缀） |
|---|---|
| `workspace_read` | `workspace_read_file` |
| `workspace_write` | `workspace_write_file`、`workspace_edit_file` |
| `workspace_shell` | `workspace_shell` |
| `workspace_other` | `workspace_export_to_phone`、`workspace_mount_*`、`workspace_bg_*`、`workspace_create_backup` |
| `search` | `search_web`、`scrape_web` |
| `mcp` | `mcp__*`（所有 MCP 工具） |
| `conversation` | `recent_chats`、`conversation_search` |
| `local` | `ask_user` 除外：`calendar_*`、`clipboard_tool`、`eval_javascript`、`get_screen_time`、`text_to_speech`、`get_time_info`、`todo_*` |

- **精确工具名**：如 `workspace_read_file`、`search_web`，直接命中
- 匹配规则：`entry == toolName || entry 是工具名前缀`；**`ask_user` 一律排除**（子循环内等待用户交互会死锁/挂起）
- 空 allowlist = 无工具，subagent 退化为"只读文本专家"

#### 4.1 Skill 工具（共享扩展管理的 Skills）

- `SubAgent.enabledSkills` 与 `Assistant.enabledSkills` 同语义：从扩展管理维护的全局 skills 库（`SkillManager.listSkills()`）按名称勾选
- 构建：subagent 的 skill 工具 = `createSkillTools(enabledSkills = subAgent.enabledSkills, allSkills = skillManager.listSkills())`（现有工厂，`SkillsTools.kt`）
- **不走 allowlist**：勾了就给 `use_skill` 工具，没勾就没有——完全由用户按需启用

#### 5. 执行核心（新文件 `app/.../data/ai/SubAgentRunner.kt`）

**复用现有 `GenerationHandler.generateText` 作为嵌套循环**（`GenerationHandler.kt:73` 的循环本身无共享可变状态，每次 flow 局部变量，理论上可重入），不重写 Agent 循环：

```
run(subAgent, assistant, settings, conversationSystemPrompt, conversationHistory,
    task, context, memories, toolCatalog, allSkills): String(JSON)
├─ 解析 model = settings.findModelById(subAgent.modelId ?: assistant.chatModelId ?: settings.chatModelId)
├─ 合成"虚拟 Assistant"：
│    systemPrompt = 主 effectiveSystemPrompt（含 conversation 覆盖逻辑）
│                   + "\n\n" + subAgent.systemPrompt
│    chatModelId = subAgent.modelId，其余参数继承主 Assistant
├─ 工具集 =
│    toolCatalog.filter(allowlist 匹配).map { it.copy(needsApproval = { false }) }
│    + createSkillTools(subAgent.enabledSkills, allSkills)      // 共享的 skills，按需启用
│    （catalog = 主 Agent 工具池剔除 subagent 工具后传入，v1 无嵌套）
├─ 消息 =
│    stripReasoning(conversationHistory)                        // 主 Agent 对话记录，过滤 think
│    + [ UIMessage.user(task + [<context>…</context>]) ]        // 叠加任务
├─ memories = 主 Agent memories（enableMemory 继承时生效）
├─ withTimeout(timeoutMs) { generationHandler.generateText(…, maxSteps=subAgent.maxSteps,
│     processingStatus=独立 MutableStateFlow, input/outputTransformers=空).collect(取最后 messages) }
└─ 提取最后一条 assistant 文本 → 结构化 JSON
```

- **上下文语义**（用户拍板）：继承 persona（system prompt）+ 记忆 + **带入主 Agent 对话记录**，再叠加任务
- **不带入 think 过程**：`stripReasoning()` 纯函数 = 过滤每条消息的 `UIMessagePart.Reasoning` part，并剥离 `Text` part 中残留的 `<think>...</think>` 片段（复用 `ThinkTagTransformer` 的 `THINKING_REGEX`）；对话历史的长度由 `generateInternal` 的 `limitContext(assistant.contextMessageLimit)` 统一截断（subagent 合成 Assistant 继承主 limit）
- 不应用主 Agent 的 input/output transformers（避免时间注入/OCR/占位符等副作用污染子循环）
- **结果 JSON**（返回主 Agent 的 tool output）：
  ```json
  { "status": "success|error|timeout",
    "result": "最终回答或错误信息",
    "steps": 6,
    "usage": { "inputTokens": 0, "outputTokens": 0 } }
  ```
- 循环因 `maxSteps` 耗尽而结束时无最终文本 → `result` 附"达到最大步数" + 最近消息摘要
- 实例化：`ChatService` 内部构造 `SubAgentRunner(generationHandler)`（与 `BackgroundTaskReminderTransformer` 同模式，无需 Koin 注册）

#### 6. 工具注册（新文件 `app/.../data/ai/tools/SubAgentTools.kt` + `ChatService.kt`）

- **每个启用的 subagent 生成一个独立工具**（Claude Code 式：主 Agent 同时看到多个"专家"，各自有精准 description）：
  - 工具名：`subagent_<slug>`（slug = name 小写、非字母数字转 `_`；冲突时追加短 id，如 `subagent_code_reviewer_a1b2`）
  - 参数：`task`（必填 string）+ `context`（可选 string，主 Agent 传必要背景）
  - `description`：subAgent.description + 附注（超时/步数/返回 JSON 格式提示）
  - `needsApproval = { subAgent.requiresApproval }`
- `ChatService.handleMessageComplete` 工具组装（`ChatService.kt:536` buildList）重构为：
  1. 先构建 `mainTools`（现有全部工具：search/local/conversation/workspace/skills/MCP，逻辑不变）
  2. `addAll(mainTools)`
  3. `if (assistant.subagentIds.isNotEmpty()) addAll(createSubAgentTools(…))`，工厂参数：
     - `subAgents`（按 `assistant.subagentIds` 过滤后的定义列表）、`assistant`、`settings`、`memories`（handleMessageComplete 局部变量）、`conversationSystemPrompt`（= conversation.customSystemPrompt）、`conversationHistory`（= conversation.currentMessages，生成前快照，Runner 内 stripReasoning）、`workspaceCwd`、`conversationId`、`toolCatalog = mainTools`、`allSkills`（= skillManager.listSkills()）
- **workspace 工具复用**：subagent 若 allowlist 含 workspace 类别，其工具来自 `createWorkspaceTools(workspaceId, repo, cwd, conversationId)`（同一工厂，自动带审批映射与挂载表）

#### 7. UI

- **设置—通用设置—扩展管理**（`ExtensionsPage.kt`）新增 "Subagents" 入口 item（Icon + 标题 + 描述，navigate `Screen.SubAgents`，参照现有 Skills 条目 `ExtensionsPage.kt:53`）
- subagent 管理页（参照 `SkillsPage.kt` 模式）：
  - `app/.../ui/pages/extensions/subagents/SubAgentsPage.kt`：列表 + 新增 + 删除 + "复制预设"
  - `SubAgentEditPage.kt`：name / description / systemPrompt / 模型选择（可选，参照 Assistant 模型选择）/ 工具 allowlist 分组勾选（按 §4 类别）/ **Skills 勾选（共享扩展管理 Skills，复用 `SkillsContent` 勾选模式，参照 `AssistantExtensionsPage.kt:195-206`）** / maxSteps / timeoutMs / requiresApproval
  - `RouteActivity.kt`：`Screen.SubAgents`、`Screen.SubAgentEdit(id)` + 路由条目（参照 `Screen.Skills:493`）
- Assistant 配置页（`AssistantExtensionsPage.kt`，参照 `SkillsContent:191-206`）：新增"可用 subagent"卡片——勾选 `assistant.subagentIds` + "管理"按钮跳转扩展管理
- `values/strings.xml`：新增英文文案（只加 base）
- 聊天内：subagent 运行按普通 tool call 渲染（结果 JSON 文本），v1 不做特殊折叠展示

### 实施步骤（每步独立提交到 dev）

1. **数据层**：`SubAgent.kt`（模型 + 3 预设，含 `enabledSkills`）+ `PreferencesStore` 存储 + `Assistant.subagentIds`——无行为变化，可独立验证
2. **执行层**：`SubAgentRunner.kt`（含 `stripReasoning` 纯函数）+ `SubAgentTools.kt` + `ChatService` 组装重构（传入 conversationHistory/allSkills）
3. **UI**：扩展管理入口 + `SubAgentsPage` / `SubAgentEditPage`（含 Skills 勾选）+ 路由 + Assistant 配置勾选 + strings
4. **测试**：allowlist 匹配、slug 化、`stripReasoning`、结果 JSON 组装为纯函数 → JVM 单测（参照 `WorkspacePhoneExporterTest`）

### 风险与验证

- **GenerationHandler 重入**：嵌套调用同一实例是否安全需代码 review + 真机验证；若发现问题，备选方案是 SubAgentRunner 自实现精简循环（不复用 generateText），代价是重复工具执行/审批状态逻辑
- **超时取消**：`withTimeout` 取消依赖 provider 调用响应取消（现有 CancellationException 传播链已处理，需真机确认）
- **记忆可写**：enableMemory 继承主值 → subagent 内部生成的 memory 工具可读写记忆（与主 Agent 同权限）；v1 接受，文档提示
- **token 成本**：每次派发 = 子循环 + 主 Agent 消费结果 + **带入对话记录（去 think 后仍可能较大）**；工具描述提示；历史长度由 contextMessageLimit 截断兜底
- **工具名冲突**：slug 冲突追加短 id
- **沙箱无 Android SDK**：全部改动需在 Android Studio `assembleDebug` 验证编译，嵌套循环重入与超时取消需真机验证

---

## 功能七：Todo 本地工具（替代 todo-manager skill）

### 需求（用户拍板）

把 `/skills/todo-manager` 的 todo 能力改为 **RikkaHub 内置本地工具**（不再作为 skill 使用）。预期保持简单：**仅在一个对话内使用**，只需三个操作：创建任务、编辑任务、标记已完成。不做 list/get/search/summarize/delete。

> 注：原 skill 的 `todo` 是 workspace 里的 Linux 二进制（`/skills/todo-manager/todo`），Android App 进程无法直接执行，因此用 Kotlin 复刻逻辑，数据存 App 私有目录。

### 设计

- **数据作用域 = 单个对话**：`context.filesDir/todo/<conversationId>.json`，每个对话一个独立任务列表；LLM 在对话内创建/编辑/完成的任务只对该对话可见
- **文件格式**：裸 JSON 数组（与原 CLI 兼容）`[{id, title, description, created_at, completed, completed_at}]`；id = 12 位随机 hex（与原 CLI 一致）；时间 ISO-8601
- **工具**（3 个，单职责，参照 `CalendarTool` 拆分模式）：
  - `todo_create`：`title`（必填）+ `description`（可选）
  - `todo_update`：`id`（必填）+ `title`/`description`（可选，至少一个）
  - `todo_complete`：`id`（必填）+ `completed`（可选，默认 true，可取消完成）
  - 返回 JSON：`{status:"ok", todo:{...}}` 或 `{status:"error", message:"未找到 ID 为 'xxx' 的待办事项"}`
- **接入**：
  - `LocalToolOption` 加 `data object Todo`（`LocalToolOption.kt`）
  - `LocalTools.getTools(options, conversationId: Uuid? = null)` 加 conversationId 参数；`ChatService.kt:540` 调用处传入；conversationId 为 null 时不注册 todo 工具（仅此一处调用，影响面小）
  - 新文件 `app/.../data/ai/tools/local/TodoTool.kt`（三个 build 函数）
  - `AssistantLocalToolPage.kt` 加勾选行 + `strings.xml` 加 title/desc 文案（参照 `calendar_title/calendar_desc:1292`）
  - 功能六 §4 allowlist 的 `local` 类别前缀表追加 `todo_*`（subagent 可勾选 todo 工具）
- **并发与清理**：单文件读写做原子写（临时文件 + rename）；对话删除不清理 todo 文件，v1 接受

### 对话内 UI（用户拍板：聊天页输入框下加 todo 图标，位于思考深度之后）

- **入口**：`ChatInput` 工具条横向滚动 Row（`ChatInput.kt:241`），位置 = `ModelSelector → SearchPickerButton → ReasoningButton` 之后；仅当 `assistant.localTools` 含 `Todo` 时显示（未启用则该助手不出现入口）
- **图标**：`TodoStatusButton`——HugeIcons 清单图标 + Material3 `BadgedBox` 角标（未完成任务数 N>0 时显示红色数字；N=0 灰显），样式对齐 `ReasoningButton` 的 `ToggleSurface`
- **展示**：点击弹 `ModalBottomSheet`（参照 `ReasoningPicker.kt` 的 ModalBottomSheet 用法）：
  - 标题：Todo（未完成 X / 共 Y）
  - 未完成组在前：title、description（次要色小字）、创建时间（`M/d HH:mm`）
  - 已完成组置灰 + 删除线
  - 空态："暂无待办，可以让我帮你记录"
  - **纯只读展示，无任何用户操作**；完成/取消完成**仅由模型通过 `todo_complete` 工具更新**（用户在对话里让模型标记完成）——不做新增/编辑/删除/勾选等交互，新增编辑同样走对话自然语言 + `todo_*` 工具
- **数据接入**：抽 `TodoStore`（`app/.../data/ai/tools/local/TodoStore.kt`）：封装 `filesDir/todo/<conversationId>.json` 读写 + `MutableStateFlow<List<TodoItem>>` 缓存；`TodoTool.kt` 三个工具与 UI 共用同一数据源 → LLM 创建任务后图标角标**实时更新**
  - `fun todos(conversationId: Uuid): StateFlow<List<TodoItem>>`（UI 只读）
  - `suspend fun setCompleted(conversationId: Uuid, id: String, completed: Boolean)`（仅 `todo_complete` 工具调用）
- **ChatVM**：注入 TodoStore，加 `val todos: StateFlow<List<TodoItem>>`（按 `_conversationId`）；`ChatPageContent` 只读传给 `ChatInput`（新增参数 `todos`，无交互回调）
- **图标选择**：项目内已确认存在 `LeftToRightListBullet`；优先尝试 `Task01`/`ListChecklist`（hugeicons 外部库，沙箱无法验证），编译不过则回退 `LeftToRightListBullet`

### 实施步骤

1. `TodoStore.kt`（读写 + flow，纯 Kotlin 可 JVM 测）＋ `TodoTool.kt`（三个工具）＋ `LocalToolOption.Todo` + `LocalTools.getTools` 签名
2. `ChatService.kt:540` 传 conversationId + `AssistantLocalToolPage` 勾选行 + strings + 功能六 allowlist 表补 `todo_*`
3. UI：`TodoStatusButton`/`TodoSheet`（新文件 `app/.../ui/components/ai/TodoSheet.kt`）+ `ChatInput` 工具条接入 + `ChatVM.todos` + `ChatPageContent` 传参
4. 测试：TodoStore 读写/ID 生成/错误语义/角标计数 → JVM 单测

### 已知缺陷记录（暂不单独修复，随功能八顺带解决）

1. **`buildWorkspacePrompt` 的 "Available tools" 列表与工具集不同步**（`WorkspaceReminderTransformer.kt:47-50`）：系统提示里只列了 `workspace_read_file` / `write_file` / `edit_file` / `shell` 四个，后加的 `workspace_export_to_phone`、`workspace_mount_*`、`workspace_bg_*`、`workspace_create_backup` 都不在列表里。模型仍能通过函数定义的 description 知道这些工具，但 `<workspace>` 块的说明是过时的。

---

## 功能八：workspace 工具提示词可编辑（per-tool prompt）

### 需求（用户拍板）

把 `workspace_` 系列工具（13 个）的**注入提示词**提取为 per-workspace、per-tool 可编辑配置。入口：**设置—扩展管理—工作区—某一工作区—Basic tab—工具审批**卡片，点击某个工具行 → 编辑该工具对应的注入提示词。

### 设计

#### 1. 数据层（参照 `toolApprovals` 现有模式）

- `WorkspaceEntity`（`app/.../data/db/entity/WorkspaceEntity.kt`）加可空列：
  ```kotlin
  // 工具提示词的用户覆盖项 (toolName -> prompt)，未覆盖的工具沿用默认提示词
  @ColumnInfo("tool_prompts")
  val toolPrompts: String? = null,   // JSON Map<String, String>
  ```
- `AppDatabase` version 25→26，`AutoMigration(from = 25, to = 26)`（加可空列，自动迁移即可）
- `WorkspaceDAO` 加 `updateToolPrompts(id, toolPrompts, updatedAt)`
- `WorkspaceRepository` 加：
  - `setToolPrompt(id, toolName, prompt)`：读实体 → `toolPromptOverrides() + (toolName to prompt)` → upsert（参照 `setToolApproval:105`）
  - `clearToolPrompt(id, toolName)`：删除覆盖项（恢复默认）
- 实体方法 `toolPromptOverrides(): Map<String, String> = JsonInstant.decodeFromString(toolPrompts ?: "{}")`

#### 2. 默认提示词表（新文件 `app/.../data/ai/tools/WorkspaceToolPrompts.kt`）

- `DEFAULT_WORKSPACE_TOOL_PROMPTS: Map<String, String>`——13 个工具的默认注入提示词（英文，从各工具 description 提炼，风格对齐现有 `<workspace>` 块 Available tools 条目）：
  - `workspace_read_file` / `write_file` / `edit_file` / `shell`：沿用 `WorkspaceReminderTransformer.kt:48-50` 现有描述
  - `workspace_export_to_phone` / `mount_list` / `mount_sync` / `bg_*` / `create_backup`：从各自 `Tool.description` 提炼一句话说明
- 同时定义 `WORKSPACE_TOOL_NAMES`（13 个工具名列表，供 UI 与注入逻辑遍历）

#### 3. 注入逻辑改造（`WorkspaceReminderTransformer.kt`）

- `buildWorkspacePrompt` 保留通用说明（workspace 名、`/workspace` 挂载、绝对路径、`/skills`、`/upload`、cwd、偏好提示）
- **"Available tools" 列表改为动态生成**：
  ```
  val prompts = workspace.toolPromptOverrides() + DEFAULT_WORKSPACE_TOOL_PROMPTS  // 覆盖优先
  WORKSPACE_TOOL_NAMES.forEach { name -> appendLine("  - `$name`: ${prompts[name]}") }
  ```
- 顺带修复缺陷 1：13 个工具全覆盖，列表不再与工具集脱节
- 注意：`WorkspaceReminderTransformer` 已注入 `workspaceRepository`，直接读最新配置；用户改提示词后下次生成即生效

#### 4. UI（`WorkspaceDetailPage.kt` 工具审批卡片）

- `WorkspaceToolApprovalCard` 每行工具：保留 Switch（审批），**行本身可点击**（`Row.clickable`）打开编辑对话框
- 新组件 `ToolPromptEditDialog`（同文件或 `WorkspaceToolPromptDialog.kt`）：
  - 标题：工具名 + 显示名
  - 多行 `OutlinedTextField`：当前提示词（覆盖值；未覆盖时显示默认提示词并标注"默认"）
  - "恢复默认"按钮（调用 `clearToolPrompt`）、保存（`setToolPrompt`）、取消
- `WorkspaceDetailVM` 加 `setToolPrompt(toolName, prompt)` / `clearToolPrompt(toolName)`（参照 `setToolApproval:186` 模式 + `loadWorkspace()`）
- `strings.xml`：对话框标题/按钮/默认标记文案（只加 base）

### 已定案（v1 边界，用户确认）

1. **编辑范围**：v1 只编辑注入 `<workspace>` 块的 per-tool 提示词文本；**不动** `Tool.description`（函数定义，模型经 function calling schema 看到）。description 可编辑列为 v2。
2. **UI 形态**：采用对话框（工具行点击打开 `ToolPromptEditDialog`）。

### 实施步骤

1. 数据层：`WorkspaceEntity.toolPrompts` + DAO + Repository + DB 25→26 AutoMigration
2. 默认表：`WorkspaceToolPrompts.kt`
3. 注入改造：`WorkspaceReminderTransformer` 动态生成 Available tools（顺带修复缺陷 1）
4. UI：工具行点击 + `ToolPromptEditDialog` + VM + strings
5. 测试：默认表覆盖全部工具名、覆盖/恢复逻辑 → JVM 单测

---

## 验证方式

- 沙箱内无法跑 Android 构建（无 SDK）→ 逐文件编译级 review + 纯逻辑 JVM 单测
- 最终需用户在 Android Studio 执行 `assembleDebug` 验证编译，真机验证 SAF 权限与同步行为

## 风险

- 双向同步的冲突与一致性（挂载期间崩溃可能丢未回写修改）
- DB 迁移（v24→v25）需在 Android Studio 验证
- 大目录挂载的性能（首次拉取耗时）
- 功能三：常驻 headless proot 会话的稳定性/内存占用需真机验证；App 被杀任务丢失；多工作区同时常驻的资源占用；`--kill-on-exit` 仅影响 proot 退出时的子进程，常驻会话内后台进程不受影响（需真机确认）
- 沙箱无 Android SDK，全部改动需在 Android Studio 编译验证
