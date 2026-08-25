# 08 · Workspace 沙箱专题（:workspace 模块 + app 侧协作）

> 给 AI 一个真实的 Linux 环境。原理：PRoot 用户态 chroot 仿真跑在 Android 上，rootfs 运行时从 URL 安装。
> 模块路径：`workspace/src/main/java/me/rerere/workspace/`；app 侧管理在 `data/repository/WorkspaceRepository.kt`、`data/files/WorkspaceBgManager.kt`、`data/ai/tools/WorkspaceTools*.kt`。

## 1. 架构总览

```
AI 工具(workspace_shell 等) / 终端页
        │
WorkspaceRepository (状态机: DISABLED→INSTALLING→READY/BROKEN; 完整性检查; 审批/提示词覆盖)
        │
WorkspaceManager (门面: 文件双存储区 + shell 执行 + 路径映射)
 ├─ WorkspaceFileSystem   FILES 区(/workspace) 文件 CRUD/glob/grep
 ├─ WorkspaceStorageArea  LINUX 区(rootfs) 只读访问辅助
 ├─ ProotShellRunner      PRoot 进程执行(一次性命令)
 │    └─ libproot_exec.so / libproot_loader.so (预编译, jniLibs)
 ├─ RootfsInstaller       rootfs 下载+自写 tar 解压安装(原子切换)
 └─ RootfsPatcher         安装后/启动前修补(resolv.conf/hosts/locale/group...)
        │
app 侧常驻会话:
 ├─ WorkspaceBgManager    headless proot bash 常驻 → 后台任务 (.l2s.bg/<taskId>/)
 └─ WorkspaceMountManager SAF 手机目录 ↔ /mnt/<name> 物化缓存
```

磁盘布局：`filesDir/workspaces/<root>/`
- `files/` —— FILES 区，proot 内挂载为 **`/workspace`**（用户文件、任务日志 .l2s.bg/）
- `linux/` —— LINUX 区，rootfs 本体（ubuntu-base 等）
- `tmp/` —— PROOT_TMP_DIR

固定 bindMounts（DI 注入）：`/skills`(SkillManager 技能)、`/tool_outputs`(工具大输出)、`/upload`(聊天附件)、`/agent`(AgentMd 目录)——同一份表同时用于 proot `-b` 参数与文件工具路径解析（避免漂移）。

## 2. Shell 执行链路

### 接口与上下文（WorkspaceShellRunner.kt）
```kotlin
interface WorkspaceShellRunner { fun execute(context: WorkspaceShellContext): WorkspaceCommandResult }
class WorkspaceShellContext(command, cwd, filesDir, linuxDir, tempDir, workingDir,
    timeoutMillis=30_000, stdin?, bindMounts, onOutput: ((isStderr, chunk) -> Unit)?)  // 实时输出回调
```
- `HostShellRunner`：无 rootfs 兜底（/system/bin/sh 直接跑宿主，测试用）

### Process.readResult 扩展（ProotShellRunner 也复用）
- stdout/stderr 各一条 daemon StreamCollector 线程：4096 char 缓冲读取，每块先 onChunk 回调再入 StringBuilder；单流上限 **128KB**(MAX_OUTPUT_CHARS) 超限置 truncated=true 但继续读到 EOF 丢弃（防管道写满子进程无法退出）
- StreamWriter daemon 线程写 stdin；waitFor(timeout) 超时 destroyForcibly；InterruptedException 时杀进程并 join 回收线程（配合协程取消防泄漏）

### ProotShellRunner 命令组装
前置检查 `linux/bin/sh` 存在否则 exitCode=127 "Rootfs is not installed"。每次执行前 patcher.patch(linuxDir)。环境变量 PROOT_LOADER/PROOT_TMP_DIR/TMPDIR；进程 cwd=filesDir。

```
libproot_exec.so --root-id --link2symlink --kill-on-exit \
  -r <linuxDir> -w <cwd 映射为 /workspace[/相对]> \
  -b <filesDir>:/workspace [-b <mount.source>:<mount.target>]... \
  -b /dev -b /proc -b /sys \
  /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:...:/bin TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 \
  /bin/bash -l -c 'cd -- "$1" && eval "$2"' rikkahub <cwd> <command>
```
**关键设计**：命令经位置参数 $1/$2 传入而非字符串拼接，`eval "$2"` 只求值一次——完全避免转义问题。

实时输出链路：工具 executeCommandStreaming(onOutput) → WorkspaceManager.executeCommand → ProotShellRunner.readResult StreamCollector 逐块回调(isStderr 区分) → ShellRunMonitor(app 内存态, ShellRunKey 协程上下文元素关联 toolCallId) → UI 直播（实验开关 enableShellLiveOutput 默认关）。

## 3. rootfs 安装（RootfsInstaller.kt）

`install(root, url, onProgress)`：
1. ensureWorkspace → 按 URL 判断格式（.tar.xz/.txz→XZ，否则 GZ）
2. HttpURLConnection 下载（跟随重定向，连接 30s/读 60s）到 tmp/rootfs.<ext>，每 512KB 回调 DOWNLOADING
3. 解包到 tmp/rootfs-staging
4. 删旧 linuxDir → staging rename 成 linuxDir（原子切换）
5. patcher.patch() → INSTALLED；finally 清理临时

**自写 ustar 解析器**（不依赖 commons-compress）：
- 手解 512B header(name/prefix/mode/size/mtime/type/linkName)；支持 GNU longname(L)/longlink(K)/PAX extended header(x, path=/linkpath= record)；entry 类型 FILE/DIRECTORY/SYMLINK/HARDLINK
- 安全：normalizeTarPath(拒 NUL/../)+safeResolve(canonical 必须留在目标内)；symlink 目标逃逸检测；hardlink 失败降级复制并拷权限位
- 写后按 header mode setReadable/Writable/Executable；非 symlink 恢复 mtime
- 循环 checkInterrupted() 配合 runInterruptible 让协程取消立刻打断阻塞 IO
- xz 用 org.tukaani.xz.XZInputStream（xz 依赖的原因）

**下载源无内置**——app 侧默认值在 `ui/pages/extensions/workspace/WorkspaceDetailPage.kt` 底部：
`DEFAULT_ROOTFS_URL = https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz`（可填任意 tar.gz/tar.xz）

## 4. RootfsPatcher.patch(linuxDir)（幂等五件事）
1. etc/resolv.conf：无有效 nameserver 时重写为 1.1.1.1/8.8.8.8/223.5.5.5 + options edns0 trust-ad
2. etc/hosts：确保 127.0.0.1 localhost 与 ::1 条目（追加不覆盖）
3. etc/hostname：空则 localhost
4. etc/default/locale：无 LANG 则 C.UTF-8
5. etc/group：读 /proc/self/status Groups 行，把 Android 补充组 GID 追加为 android_gid_<id> 条目
另建 tmp、var/tmp(全局 rwx)、root(rwx)。

## 5. 路径映射核心（WorkspaceManager.resolveRootfsPath）

rootfs 内绝对路径 → 宿主真实文件：
1. bindMounts(+extra) 按 target 前缀**长度降序最长匹配**
2. `/workspace` 前缀 → filesDir(root) 下相对路径
3. /dev /proc /sys（KERNEL_FS_MOUNTS）→ 显式报错 "is a kernel filesystem..., use workspace_shell instead"
4. 其余 → linuxDir(root) 下路径

由此 workspace_read_file 可直读三类文件无需进 proot。约束：root 名必须 `[A-Za-z0-9._-]+` 防穿越。

## 6. FILES 区文件系统（WorkspaceFileSystem.kt）

- resolvePath 规范化+canonical 强制留在 root 内（"Path escapes workspace root"）
- list：目录优先排序、过滤 `.l2s.` 前缀隐藏文件、截断 maxListEntries
- readText/writeText 受大小限制；importBytes 冲突自动改名 `name (n).ext`
- delete：拒删根；目录需 recursive；move 支持 overwrite
- glob：NIO PathMatcher；grep：正则/字面量+ignoreCase+includeGlob+跳过超大文件+结果截断

LINUX 区（WorkspaceStorageArea）提供 rootfs 内只读列举/读取辅助。

## 7. 后台任务（app/data/files/WorkspaceBgManager.kt）

- 原理：每工作区一个**常驻 headless proot bash 会话**(内部类 HeadlessSession)，任务以 `(cmd > log 2>&1; echo $? > exit_code) &` 在其中运行
- 产物：FILES 区 `.l2s.bg/<taskId>/stdout.log`（stderr 同理）+ exit_code 出现即完成
- API：startTask/listTasks/listUnNotifiedFinishedTasks/markNotified/truncateOutputIfLarge/killSession/cleanupOrphanTasks(App 被杀遗留标 failed)
- MAX_CONCURRENT_TASKS=3；输出尾部窗口截断
- 完成自动拉起 LLM 的 watcher 见 04 文档 §9；`.l2s.` 前缀文件对 AI 的文件列表不可见（过滤规则）

## 8. SAF 挂载（app/data/files/WorkspaceMountManager.kt）

- 手机 SAF 树 URI ↔ 工作区 `/mnt/<name>`；SAF 无法直接进 proot，先物化到 `filesDir/mnt/<mountId>/` 缓存
- 快照式双向同步 PULL/PUSH（size+mtime 增量；push 不删手机文件）；配置存 settings.workspaceMounts
- **后台自动同步**（2026-08-26 起，替代已删除的 mount 工具）：startAutoSyncLoop 由
  App 启动拉起，每周期先 PUSH 再 PULL；间隔 settings.workspaceAutoSyncIntervalSeconds
  （0=关闭/30/60/300，默认 60s），运行时读值即改即生效；设置页挂载卡片可选手动同步
- 启动自动 pullAllAtStartup（RikkaHubApp 清理阶段调用）；activeBindMounts() 注入每次 shell 执行

## 9. 导出到手机（WorkspacePhoneExporter.kt）
rootfs/FILES → WorkspaceEntity.exportTargetUri(SAF 树)：拒绝绝对路径与 ../..、跳过 `.l2s.` 与符号链接、总量上限流式拷贝。

## 10. 交互终端页（ui/pages/extensions/workspace/WorkspaceTerminalPage + WorkspaceTerminalSession.kt）

- 基于 termux terminal-view 0.118.0 + 自编译 JNI `termux_pty.cpp`：createSubprocess(posix_openpt/fork/setsid/TIOCSCTTY/execve)/setPtyWindowSize/waitFor/close
- PTY 会话跑 proot 进入同一 rootfs；**与 ProotShellRunner(管道)相互独立**
- native 目录还有 workspace.cpp（模板占位，无实际 JNI）

## 11. 备份一致性快照
workspace_create_backup 工具复用 WebDavSync.prepareBackupFile(DATABASE scope) 产出 `/workspace/backup.zip`（DB 一致快照 wal_checkpoint 后复制）。
