# RikkaHub 工作区新功能开发计划

分支：`dev`（基于 `master`，HEAD `576f2341`）
状态：**草稿，待确认后实施**

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

## 验证方式

- 沙箱内无法跑 Android 构建（无 SDK）→ 逐文件编译级 review + 纯逻辑 JVM 单测
- 最终需用户在 Android Studio 执行 `assembleDebug` 验证编译，真机验证 SAF 权限与同步行为

## 风险

- 双向同步的冲突与一致性（挂载期间崩溃可能丢未回写修改）
- DB 迁移（v24→v25）需在 Android Studio 验证
- 大目录挂载的性能（首次拉取耗时）
- 功能三：常驻 headless proot 会话的稳定性/内存占用需真机验证；App 被杀任务丢失；多工作区同时常驻的资源占用；`--kill-on-exit` 仅影响 proot 退出时的子进程，常驻会话内后台进程不受影响（需真机确认）
- 沙箱无 Android SDK，全部改动需在 Android Studio 编译验证
