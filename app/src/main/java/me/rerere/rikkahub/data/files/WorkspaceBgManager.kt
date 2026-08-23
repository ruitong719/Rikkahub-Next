package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import java.io.File
import java.io.OutputStreamWriter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "WorkspaceBgManager"

enum class BgTaskStatus {
    RUNNING,
    DONE,
    FAILED,
}

@Serializable
data class WorkspaceBgTaskMeta(
    val taskId: String,
    val conversationId: String? = null,
    val command: String = "",
    val startedAt: Long = 0L,
)

data class WorkspaceBgTaskInfo(
    val taskId: String,
    val conversationId: String?,
    val command: String,
    val startedAt: Long,
    val status: BgTaskStatus,
    val exitCode: Int?,
    val pid: Long?,
    val notified: Boolean,
    val stdoutSizeBytes: Long,
)

/**
 * 持久化后台任务管理。
 *
 * workspace_shell 是一次性 proot 进程，命令结束进程即退出，后台进程无法存活。
 * 这里为每个工作区维护一个常驻 headless proot bash（无 UI，管道通信），
 * 后台任务在其中以 `( cmd > log 2>&1; echo $? > exit_code ) &` 方式运行：
 * - 输出重定向到 `/workspace/.l2s.bg/<taskId>/stdout.log`（= filesDir 下，App 直接读文件）
 * - 完成标志 = exit_code 文件出现（比轮询 pid 可靠，避免 pid 复用误判）
 * - `.l2s.` 前缀会被文件工具过滤，不污染工作区文件列表
 *
 * 生命周期：会话随 App 进程存活；App 被杀后任务丢失（决策接受），
 * [cleanupOrphanTasks] 在下次启动时把残留 running 任务标记为 failed。
 */
class WorkspaceBgManager(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
    private val mountManager: WorkspaceMountManager,
) {
    private val sessions = mutableMapOf<String, HeadlessSession>()

    companion object {
        const val MAX_CONCURRENT_TASKS = 3
        const val BG_DIR = ".l2s.bg"
        const val MAX_OUTPUT_READ_BYTES = 2 * 1024 * 1024 // 2MB 单次读取上限
        const val PROOT_EXEC = "libproot_exec.so"
        const val PROOT_LOADER = "libproot_loader.so"
    }

    // ---------- 任务操作 ----------

    /**
     * 列出绑定到某对话且尚未提醒的已完成后台任务。
     * 自动拉起 watcher 与 BackgroundTaskReminderTransformer 共用此判定，
     * 防止两处的状态过滤条件漂移（如未来新增枚举值只改一处）。
     */
    suspend fun listUnNotifiedFinishedTasks(
        workspaceRoot: String,
        conversationId: String?,
    ): List<WorkspaceBgTaskInfo> = listTasks(workspaceRoot).filter {
        it.conversationId == conversationId &&
            !it.notified &&
            (it.status == BgTaskStatus.DONE || it.status == BgTaskStatus.FAILED)
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun startTask(
        workspaceRoot: String,
        command: String,
        cwd: String?,
        conversationId: String?,
    ): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "command is required" }
        workspaceManager.ensureWorkspace(workspaceRoot)

        // 并发限制
        val runningCount = listTasksInternal(workspaceRoot).count { it.status == BgTaskStatus.RUNNING }
        require(runningCount < MAX_CONCURRENT_TASKS) {
            "Too many running background tasks ($runningCount/$MAX_CONCURRENT_TASKS). " +
                "Wait for one to finish or kill it first."
        }

        val taskId = Uuid.random().toString()
        val taskDir = taskDir(workspaceRoot, taskId)
        taskDir.mkdirs()

        // App 侧元数据
        val meta = WorkspaceBgTaskMeta(
            taskId = taskId,
            conversationId = conversationId,
            command = command,
            startedAt = System.currentTimeMillis(),
        )
        File(taskDir, "meta.json").writeText(Json.encodeToString(WorkspaceBgTaskMeta.serializer(), meta))

        // 会话内提交任务：wrapper 记录退出码
        val session = ensureSession(workspaceRoot)
        val workingDir = cwd?.takeIf { it.isNotBlank() } ?: "/workspace"
        val wrapped = buildString {
            append("( cd ").append(workingDir.shellQuote()).append(" && ")
            append(command)
            append(" > ").append(taskAbsPath(workspaceRoot, taskId, "stdout.log").shellQuote()).append(" 2>&1; ")
            append("echo $? > ").append(taskAbsPath(workspaceRoot, taskId, "exit_code").shellQuote())
            append(" ) &\n")
            append("echo $! > ").append(taskAbsPath(workspaceRoot, taskId, "pid").shellQuote()).append("\n")
        }
        session.submit(wrapped)
        taskId
    }

    suspend fun taskInfo(workspaceRoot: String, taskId: String): WorkspaceBgTaskInfo =
        withContext(Dispatchers.IO) {
            val dir = taskDir(workspaceRoot, taskId)
            require(dir.isDirectory) { "Background task not found: $taskId" }
            readTaskInfo(dir)
        }

    suspend fun listTasks(workspaceRoot: String): List<WorkspaceBgTaskInfo> =
        withContext(Dispatchers.IO) {
            listTasksInternal(workspaceRoot)
        }

    suspend fun output(
        workspaceRoot: String,
        taskId: String,
        tailLines: Int?,
        maxBytes: Int?,
    ): String = withContext(Dispatchers.IO) {
        val dir = taskDir(workspaceRoot, taskId)
        require(dir.isDirectory) { "Background task not found: $taskId" }
        val log = File(dir, "stdout.log")
        if (!log.exists()) return@withContext ""

        val limit = (maxBytes ?: MAX_OUTPUT_READ_BYTES).coerceIn(1, MAX_OUTPUT_READ_BYTES)
        val text = if (log.length() > limit) {
            log.inputStream().use { input ->
                input.skip(log.length() - limit)
                input.readBytes().toString(Charsets.UTF_8)
            }
        } else {
            log.readText()
        }
        val lines = tailLines?.coerceAtLeast(1)?.let { n ->
            text.lines().takeLast(n)
        } ?: text.lines()
        lines.joinToString("\n")
    }

    suspend fun killTask(workspaceRoot: String, taskId: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = taskDir(workspaceRoot, taskId)
            if (!dir.isDirectory) return@withContext false
            val pid = File(dir, "pid").takeIf { it.exists() }?.readText()?.trim()
            if (pid.isNullOrBlank()) return@withContext false
            val session = ensureSession(workspaceRoot)
            session.submit("kill ${pid.shellQuote()} 2>/dev/null || true\n")
            true
        }

    /** 删除任务：运行中的先 kill，再删除整个任务目录（.l2s.bg/<taskId>） */
    suspend fun deleteTask(workspaceRoot: String, taskId: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = taskDir(workspaceRoot, taskId)
            if (!dir.isDirectory) return@withContext false
            val pid = File(dir, "pid").takeIf { it.exists() }?.readText()?.trim()
            if (!pid.isNullOrBlank()) {
                runCatching {
                    ensureSession(workspaceRoot).submit("kill ${pid.shellQuote()} 2>/dev/null || true\n")
                }
            }
            dir.deleteRecursively()
            true
        }

    /** 标记任务已完成提醒（提醒注入后调用，避免重复提醒） */
    fun markNotified(workspaceRoot: String, taskId: String) {
        File(taskDir(workspaceRoot, taskId), "notified").writeText("1")
    }

    /** 任务完成后把 stdout.log 截断到尾部 [maxBytes]，避免无人清理占空间 */
    suspend fun truncateOutputIfLarge(workspaceRoot: String, taskId: String, maxBytes: Int = 10 * 1024 * 1024) {
        val log = File(taskDir(workspaceRoot, taskId), "stdout.log")
        if (log.exists() && log.length() > maxBytes) {
            val tail = log.inputStream().use { input ->
                input.skip(log.length() - maxBytes)
                input.readBytes()
            }
            log.writeBytes(tail)
        }
    }

    // ---------- 会话管理 ----------

    private suspend fun ensureSession(workspaceRoot: String): HeadlessSession =
        sessions[workspaceRoot]?.takeIf { it.isAlive() } ?: run {
            val session = HeadlessSession(workspaceRoot)
            session.start(mountManager.activeBindMounts())
            sessions[workspaceRoot] = session
            session
        }

    /** 工作区删除时终止其常驻会话，避免 proot 持续持有已删除的 rootfs */
    fun killSession(workspaceRoot: String) {
        sessions.remove(workspaceRoot)?.stop()
    }

    /** App 启动时清理孤儿任务：上次进程被杀后残留的 running 任务标记为 failed */
    suspend fun cleanupOrphanTasks() = withContext(Dispatchers.IO) {
        val workspacesDir = File(context.filesDir, "workspaces")
        val bgRoots = workspacesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { File(it, "files/$BG_DIR") }
            ?.filter { it.isDirectory }
            .orEmpty()
        var cleaned = 0
        bgRoots.forEach { bgDir ->
            bgDir.listFiles()?.forEach { taskDir ->
                if (taskDir.isDirectory && !File(taskDir, "exit_code").exists()) {
                    // 进程已随上次 App 进程死亡，标记 failed
                    File(taskDir, "exit_code").writeText("143")
                    cleaned++
                }
            }
        }
        if (cleaned > 0) {
            Log.i(TAG, "cleanupOrphanTasks: marked $cleaned orphan tasks as failed")
        }
    }

    // ---------- 内部 ----------

    private fun listTasksInternal(workspaceRoot: String): List<WorkspaceBgTaskInfo> {
        val bgDir = File(workspaceManager.filesDir(workspaceRoot), BG_DIR)
        if (!bgDir.isDirectory) return emptyList()
        return bgDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> runCatching { readTaskInfo(dir) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    private fun readTaskInfo(dir: File): WorkspaceBgTaskInfo {
        val meta = File(dir, "meta.json").takeIf { it.exists() }?.let { f ->
            runCatching {
                Json.decodeFromString(WorkspaceBgTaskMeta.serializer(), f.readText())
            }.getOrNull()
        }
        val exitCodeFile = File(dir, "exit_code")
        val exitCode = exitCodeFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        val status = when {
            exitCode == null -> BgTaskStatus.RUNNING
            exitCode == 0 -> BgTaskStatus.DONE
            else -> BgTaskStatus.FAILED
        }
        return WorkspaceBgTaskInfo(
            taskId = dir.name,
            conversationId = meta?.conversationId,
            command = meta?.command.orEmpty(),
            startedAt = meta?.startedAt ?: 0L,
            status = status,
            exitCode = exitCode,
            pid = File(dir, "pid").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull(),
            notified = File(dir, "notified").exists(),
            stdoutSizeBytes = File(dir, "stdout.log").length(),
        )
    }

    private fun taskDir(workspaceRoot: String, taskId: String): File =
        File(workspaceManager.filesDir(workspaceRoot), "$BG_DIR/$taskId")

    private fun taskAbsPath(workspaceRoot: String, taskId: String, name: String): String =
        "/workspace/$BG_DIR/$taskId/$name"

    // ---------- Headless 会话 ----------

    /**
     * 常驻 headless proot bash。无 UI，stdin 管道提交命令，stdout 丢弃。
     * 必须严格持有 stdin 管道：一旦 EOF，bash 退出 → proot 退出（--kill-on-exit）→ 任务全灭。
     */
    private inner class HeadlessSession(private val root: String) {
        private var process: Process? = null
        private var writer: OutputStreamWriter? = null

        fun isAlive(): Boolean = process?.isAlive == true

        fun start(extraBindMounts: List<WorkspaceBindMount>) {
            val linuxDir = workspaceManager.linuxDir(root)
            val filesDir = workspaceManager.filesDir(root)
            val tempDir = workspaceManager.tempDir(root)
            val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir)
            val proot = File(nativeLibraryDir, PROOT_EXEC)
            val loader = File(nativeLibraryDir, PROOT_LOADER)
            require(proot.isFile) { "proot executable not found: ${proot.absolutePath}" }
            require(File(linuxDir, "bin/sh").isFile) { "Rootfs is not installed" }

            val command = mutableListOf(
                proot.absolutePath,
                "--root-id",
                "--link2symlink",
                "--kill-on-exit",
                "-r", linuxDir.absolutePath,
                "-w", "/workspace",
                "-b", "${filesDir.absolutePath}:/workspace",
            )
            // 内置挂载（与 ProotShellRunner 一致）+ 动态挂载（启动时快照）
            builtinBindMounts().forEach { mount ->
                if (mount.source.exists()) {
                    command += "-b"
                    command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
                }
            }
            extraBindMounts.forEach { mount ->
                if (mount.source.exists()) {
                    command += "-b"
                    command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
                }
            }
            WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
                if (File(path).exists()) {
                    command += "-b"
                    command += path
                }
            }
            command += listOf(
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                "USER=root",
                "SHELL=/bin/bash",
                "/bin/bash",
            )

            val env = arrayOf(
                "PROOT_LOADER=${loader.absolutePath}",
                "PROOT_TMP_DIR=${tempDir.absolutePath}",
                "TMPDIR=${tempDir.absolutePath}",
            )

            val pb = ProcessBuilder(command)
                .redirectErrorStream(false)
                .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            pb.environment().putAll(env.map { it.split("=", limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } } })

            val p = pb.start()
            process = p
            writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
            Log.i(TAG, "HeadlessSession started for workspace $root")
        }

        fun submit(command: String) {
            val w = writer ?: error("Session not started")
            synchronized(this) {
                w.write(command)
                w.flush()
            }
        }

        fun stop() {
            runCatching { process?.destroy() }
            runCatching { process?.waitFor() }
            process = null
            writer = null
            Log.i(TAG, "HeadlessSession stopped for workspace $root")
        }
    }

    private fun builtinBindMounts(): List<WorkspaceBindMount> {
        // 内置挂载与 RepositoryModule 保持一致（skills/upload/tool_outputs/agent）
        val f = File(context.filesDir, "skills").apply { mkdirs() }
        val u = File(context.filesDir, "upload").apply { mkdirs() }
        val t = File(context.filesDir, "tool_outputs").apply { mkdirs() }
        val a = File(context.filesDir, "agent").apply { mkdirs() }
        return listOf(
            WorkspaceBindMount(source = f, target = "/skills"),
            WorkspaceBindMount(source = u, target = "/upload"),
            WorkspaceBindMount(source = t, target = "/tool_outputs"),
            WorkspaceBindMount(source = a, target = "/agent"),
        )
    }

    private val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun String.shellQuote(): String = "'" + replace("'", "'\"'\"'") + "'"
}
