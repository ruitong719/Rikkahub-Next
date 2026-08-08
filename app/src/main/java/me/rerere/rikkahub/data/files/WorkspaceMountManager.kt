package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.workspace.WorkspaceBindMount
import java.io.File
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
        val filesSynced: Int = 0,
        val dirsCreated: Int = 0,
        val totalBytes: Long = 0L,
        val skipped: Int = 0,
        val errors: List<String> = emptyList(),
    )

    fun mountsFlow(): Flow<List<WorkspaceMountConfig>> =
        settingsStore.settingsFlow.map { it.workspaceMounts }

    suspend fun listMounts(): List<WorkspaceMountConfig> =
        settingsStore.settingsFlow.value.workspaceMounts

    suspend fun cacheDir(config: WorkspaceMountConfig): File =
        File(context.filesDir, "mnt/${config.id}")

    /** 当前生效的 bind mounts（仅缓存目录已存在的挂载点） */
    suspend fun activeBindMounts(): List<WorkspaceBindMount> =
        listMounts().mapNotNull { config ->
            val dir = File(context.filesDir, "mnt/${config.id}")
            if (dir.isDirectory) {
                WorkspaceBindMount(source = dir, target = "/mnt/${config.name}")
            } else {
                null
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
                    copySafToLocal(child, target, stats)
                } else {
                    val size = child.length()
                    val mtime = child.lastModified()
                    if (target.exists() && target.length() == size && target.lastModified() == mtime) {
                        stats.skipped++
                    } else {
                        val input = child.openInputStream(context)
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
                    val dirDoc = parentDoc.findFile(name)?.takeIf { it.isDirectory }
                        ?: parentDoc.createDirectory(name)
                        ?: error("Failed to create directory: $name")
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
                    val out = fileDoc.openOutputStream(context)
                    if (out != null) {
                        out.use { o ->
                            child.inputStream().use { i -> i.copyTo(o, bufferSize = COPY_BUFFER_BYTES) }
                        }
                    } else {
                        // 部分 DocumentsProvider 不支持截断打开，兜底删旧建新
                        fileDoc.delete()
                        val recreated = parentDoc.createFile(mimeTypeFor(name), name)
                            ?: error("Failed to recreate file: $name")
                        recreated.openOutputStream(context)?.use { o ->
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
    }
}
