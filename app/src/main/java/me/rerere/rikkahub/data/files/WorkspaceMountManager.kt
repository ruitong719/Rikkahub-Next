package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.workspace.WorkspaceBindMount
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 全局共享的工作区挂载点配置（SAF 树目录 -> /mnt/<name>） */
@Serializable
data class WorkspaceMountConfig(
    val id: String,
    val name: String,
    val treeUri: String,
    val lastSyncAt: Long? = null,
)

enum class SyncDirection {
    PULL,
    PUSH,
}

/**
 * 手机 SAF 目录 -> 工作区 /mnt 挂载管理。
 *
 * PRoot 的 `-b` 只认宿主机真实路径，SAF content:// URI 无法直接挂载，因此把 SAF 树
 * "物化"到 app 私有缓存目录 `filesDir/mnt/<mountId>/`，再由 [activeBindMounts] 转成
 * bind mount 挂到 `/mnt/<name>`（动态挂载，每次 shell/文件工具执行时计算）。
 *
 * 同步模型（快照式双向，手动触发）：
 * - PULL: SAF -> 缓存（addMount 时全量，sync 时增量）
 * - PUSH: 缓存 -> SAF（增量；v1 不做删除同步，避免误删手机文件）
 * 增量判断：目标已存在且 size 与 mtime 都相同则跳过（防漏同步）。
 */
class WorkspaceMountManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    data class SyncStats(
        var filesSynced: Int = 0,
        var dirsCreated: Int = 0,
        var totalBytes: Long = 0L,
        var skipped: Int = 0,
        var errors: MutableList<String> = mutableListOf(),
    )

    fun mountsFlow(): Flow<List<WorkspaceMountConfig>> =
        settingsStore.settingsFlow.map { it.workspaceMounts }

    fun listMounts(): List<WorkspaceMountConfig> =
        settingsStore.settingsFlow.value.workspaceMounts

    suspend fun cacheDir(config: WorkspaceMountConfig): File =
        File(context.filesDir, "mnt/${config.id}")

    /** 当前生效的 bind mounts（缓存目录缺失时先创建，保证 /mnt/<name> 挂载点恒定存在） */
    fun activeBindMounts(): List<WorkspaceBindMount> =
        listMounts().mapNotNull { config ->
            val dir = File(context.filesDir, "mnt/${config.id}")
            if (dir.isDirectory || dir.mkdirs()) {
                WorkspaceBindMount(source = dir, target = "/mnt/${config.name}")
            } else {
                null
            }
        }

    /**
     * 挂载点自动同步循环（替代已删除的 workspace_mount_sync 工具）。
     *
     * 每个周期先 PUSH 再 PULL：先把工作区侧改动刷到手机（避免被随后的 pull 覆盖），
     * 再吸收手机侧新增。间隔实时读设置（0=暂停），单次失败只记日志不中断循环。
     */
    fun startAutoSyncLoop(scope: CoroutineScope) {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val intervalSeconds = settingsStore.settingsFlow.value.workspaceAutoSyncIntervalSeconds
                if (intervalSeconds > 0) {
                    syncAllMounts()
                    delay(intervalSeconds.coerceAtLeast(MIN_AUTO_SYNC_SECONDS).seconds)
                } else {
                    // 关闭时低频轮询设置, 保持改回非零值后无需重启即生效
                    delay(AUTO_SYNC_OFF_POLL_SECONDS.seconds)
                }
            }
        }
    }

    private var autoSyncJob: Job? = null

    suspend fun syncAllMounts() {
        listMounts().forEach { config ->
            runCatching { push(config) }.onFailure { e ->
                Log.w(TAG, "autoSync push failed for ${config.name}: ${e.message}")
            }
            runCatching { pull(config) }.onFailure { e ->
                Log.w(TAG, "autoSync pull failed for ${config.name}: ${e.message}")
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun addMount(name: String, treeUri: Uri): WorkspaceMountConfig {
        val trimmed = name.trim()
        require(trimmed.matches(MOUNT_NAME_REGEX)) {
            "Mount name must match [a-zA-Z0-9._-]+"
        }
        val mounts = listMounts()
        require(mounts.none { it.name == trimmed }) { "Mount name already exists: $trimmed" }

        val config = WorkspaceMountConfig(
            id = Uuid.random().toString(),
            name = trimmed,
            treeUri = treeUri.toString(),
        )
        // 挂载即拉取：SAF -> 缓存
        pull(config)
        settingsStore.update { it.copy(workspaceMounts = it.workspaceMounts + config) }
        return config
    }

    /** 卸载：写回缓存 -> SAF 后删除缓存与配置 */
    suspend fun removeMount(id: String): Boolean {
        val config = listMounts().find { it.id == id } ?: return false
        val cacheDir = File(context.filesDir, "mnt/${config.id}")
        if (cacheDir.isDirectory) {
            push(config)
            cacheDir.deleteRecursively()
        }
        settingsStore.update {
            it.copy(workspaceMounts = it.workspaceMounts.filterNot { m -> m.id == id })
        }
        return true
    }

    suspend fun syncMount(id: String, direction: SyncDirection): SyncStats {
        val config = listMounts().find { it.id == id } ?: error("Mount not found: $id")
        return when (direction) {
            SyncDirection.PULL -> pull(config)
            SyncDirection.PUSH -> push(config)
        }
    }

    /**
     * App 启动时自动物化所有挂载点。
     *
     * 挂载缓存目录（filesDir/mnt/<id>）随进程存活，重启后为空/不存在时
     * [activeBindMounts] 不会生成 /mnt/<name>，shell 与文件工具都访问不到。
     * 这里对每个配置 PULL 一次；单个失败（如 SAF 权限被撤销）仅记日志，不影响其他挂载点。
     */
    suspend fun pullAllAtStartup() {
        listMounts().forEach { config ->
            runCatching { pull(config) }.onFailure { e ->
                Log.w(TAG, "pullAllAtStartup: failed for mount ${config.name}: ${e.message}")
            }
        }
    }

    private suspend fun pull(config: WorkspaceMountConfig): SyncStats = withContext(Dispatchers.IO) {
        val treeDoc = DocumentFile.fromTreeUri(context, Uri.parse(config.treeUri))
            ?: error(
                "Mount directory is not accessible (permission may have been revoked). " +
                    "Remove and re-add the mount."
            )
        val target = File(context.filesDir, "mnt/${config.id}").apply { mkdirs() }
        val stats = SyncStats()
        copySafToLocal(treeDoc, target, stats)
        touchSyncTime(config)
        stats
    }

    private suspend fun push(config: WorkspaceMountConfig): SyncStats = withContext(Dispatchers.IO) {
        val treeDoc = DocumentFile.fromTreeUri(context, Uri.parse(config.treeUri))
            ?: error(
                "Mount directory is not accessible (permission may have been revoked). " +
                    "Remove and re-add the mount."
            )
        val source = File(context.filesDir, "mnt/${config.id}")
        require(source.isDirectory) { "Mount cache does not exist: ${config.name}" }
        val stats = SyncStats()
        copyLocalToSaf(source, treeDoc, stats)
        touchSyncTime(config)
        stats
    }

    private suspend fun touchSyncTime(config: WorkspaceMountConfig) {
        settingsStore.update { settings ->
            settings.copy(
                workspaceMounts = settings.workspaceMounts.map { m ->
                    if (m.id == config.id) m.copy(lastSyncAt = System.currentTimeMillis()) else m
                }
            )
        }
    }

    /** SAF -> 本地缓存（增量：size+mtime 相同跳过） */
    private suspend fun copySafToLocal(doc: DocumentFile, targetDir: File, stats: SyncStats) {
        currentCoroutineContext().ensureActive()
        doc.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (name.startsWith(".l2s.")) return@forEach
            runCatching {
                val target = File(targetDir, name)
                if (child.isDirectory) {
                    target.mkdirs()
                    stats.dirsCreated++
                    copySafToLocal(child, target, stats)
                } else {
                    val size = child.length()
                    val mtime = child.lastModified()
                    if (target.exists() && target.length() == size && target.lastModified() == mtime) {
                        stats.skipped++
                    } else {
val input = context.contentResolver.openInputStream(child.uri)
                        ?: error("Cannot open input stream: $name")
                        input.use { i ->
                            target.outputStream().use { o -> i.copyTo(o, bufferSize = COPY_BUFFER_BYTES) }
                        }
                        target.setLastModified(mtime)
                        stats.filesSynced++
                        stats.totalBytes += size
                    }
                }
            }.onFailure { e ->
                stats.errors += "${name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** 本地缓存 -> SAF（增量：size+mtime 相同跳过；v1 不删除 SAF 中多余的远端文件） */
    private suspend fun copyLocalToSaf(sourceDir: File, parentDoc: DocumentFile, stats: SyncStats) {
        currentCoroutineContext().ensureActive()
        sourceDir.listFiles().orEmpty().forEach { child ->
            val name = child.name
            if (name.startsWith(".l2s.")) return@forEach
            runCatching {
                if (child.isDirectory) {
                    val existingDir = parentDoc.findFile(name)?.takeIf { it.isDirectory }
                    val dirDoc = existingDir
                        ?: parentDoc.createDirectory(name)
                        ?: error("Failed to create directory: $name")
                    if (existingDir == null) stats.dirsCreated++
                    copyLocalToSaf(child, dirDoc, stats)
                } else {
                    val size = child.length()
                    val mtime = child.lastModified()
                    val existing = parentDoc.findFile(name)
                    if (existing != null && existing.length() == size && existing.lastModified() == mtime) {
                        stats.skipped++
                        return@runCatching
                    }
                    val fileDoc = existing?.takeIf { it.isFile }
                        ?: parentDoc.createFile(mimeTypeFor(name), name)
                        ?: error("Failed to create file: $name")
                    val out = context.contentResolver.openOutputStream(fileDoc.uri)
                    if (out != null) {
                        out.use { o ->
                            child.inputStream().use { i -> i.copyTo(o, bufferSize = COPY_BUFFER_BYTES) }
                        }
                    } else {
                        // 部分 DocumentsProvider 不支持截断打开，兜底删旧建新
                        fileDoc.delete()
                        val recreated = parentDoc.createFile(mimeTypeFor(name), name)
                            ?: error("Failed to recreate file: $name")
                        context.contentResolver.openOutputStream(recreated.uri)?.use { o ->
                            child.inputStream().use { i -> i.copyTo(o, bufferSize = COPY_BUFFER_BYTES) }
                        } ?: error("Failed to open output stream: $name")
                    }
                    stats.filesSynced++
                    stats.totalBytes += size
                }
            }.onFailure { e ->
                stats.errors += "${name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    companion object {
        private val MOUNT_NAME_REGEX = Regex("[a-zA-Z0-9._-]+")
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val TAG = "WorkspaceMountManager"

        /** 自动同步间隔下限(秒), 防止误配成高频空转 */
        private const val MIN_AUTO_SYNC_SECONDS = 15

        /** 自动同步关闭时轮询设置的间隔(秒) */
        private const val AUTO_SYNC_OFF_POLL_SECONDS = 30L
    }
}
