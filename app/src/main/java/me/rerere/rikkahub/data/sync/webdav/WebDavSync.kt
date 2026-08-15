package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.DatabaseBackupManager
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "WebDavSync"

/** 与 WorkspaceManager 的 root 名校验保持一致，恢复时防止路径注入 */
private val WORKSPACE_ID_REGEX = Regex("[A-Za-z0-9._-]+")

class WebDavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val databaseBackupManager: DatabaseBackupManager,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        // Upload the backup file
        client.put(
            path = file.name,
            file = file,
            contentType = "application/zip"
        ).getOrThrow()

        Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")

        // Clean up temp file
        file.delete()
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        val backupFile = File(context.cacheDir, item.displayName)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            restoreFromBackupFile(file, config)
            Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    suspend fun prepareBackupFile(config: WebDavConfig): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value)
            )

            // Backup database files: 一致性快照（先 wal_checkpoint 合并，避免 WAL 撕裂快照丢失最新会话）
            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                val snapshot = databaseBackupManager.createSnapshot(
                    File(context.cacheDir, "rikka_hub_snapshot.db")
                )
                // zip 条目固定为 rikka_hub.db：外部工具（如 rikkahub-to-csv skill 的
                // step1_extract.py）硬编码查找 zip 根下的 rikka_hub.db；restore 侧同时
                // 兼容 rikka_hub.db（新名）与 rikka_hub_snapshot.db（旧名）两种条目
                addFileToZip(zipOut, snapshot, "rikka_hub.db")
                val snapshotWal = File(snapshot.parentFile, "${snapshot.name}-wal")
                if (snapshotWal.exists() && snapshotWal.length() > 0) {
                    addFileToZip(zipOut, snapshotWal, "rikka_hub-wal")
                }
                snapshotWal.delete()
                snapshot.delete()
            }

            // Backup app files
            if (config.items.contains(WebDavConfig.BackupItem.FILES)) {
                val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                }

                val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = skillsFolder,
                        currentDir = skillsFolder,
                        entryPrefix = "${FileFolders.SKILLS}/"
                    )
                } else {
                    Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                }

                val agentFolder = File(context.filesDir, FileFolders.AGENT)
                if (agentFolder.exists() && agentFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up agent from ${agentFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = agentFolder,
                        currentDir = agentFolder,
                        entryPrefix = "${FileFolders.AGENT}/"
                    )
                } else {
                    Log.w(TAG, "prepareBackupFile: Agent folder does not exist or is not a directory")
                }

                val fontsFolder = File(context.filesDir, FileFolders.FONTS)
                if (fontsFolder.exists() && fontsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up fonts from ${fontsFolder.absolutePath}")
                    fontsFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.FONTS}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Fonts folder does not exist or is not a directory")
                }

                // 工作区文件: /workspace 文件区 + /root 用户主目录（不含整个 rootfs，体积可控）
                val workspacesDir = File(context.filesDir, "workspaces")
                if (workspacesDir.exists() && workspacesDir.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up workspaces from ${workspacesDir.absolutePath}")
                    workspacesDir.listFiles()?.forEach { wsDir ->
                        if (!wsDir.isDirectory) return@forEach
                        val filesArea = File(wsDir, "files")
                        if (filesArea.exists() && filesArea.isDirectory) {
                            addDirectoryToZip(
                                zipOut = zipOut,
                                rootDir = filesArea,
                                currentDir = filesArea,
                                entryPrefix = "workspaces/${wsDir.name}/files/"
                            )
                        }
                        val rootHome = File(wsDir, "linux/root")
                        if (rootHome.exists() && rootHome.isDirectory) {
                            addDirectoryToZip(
                                zipOut = zipOut,
                                rootDir = rootHome,
                                currentDir = rootHome,
                                entryPrefix = "workspaces/${wsDir.name}/linux/root/"
                            )
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Workspaces folder does not exist or is not a directory")
                }
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        // 数据库快照/旧格式 db 暂存到 cacheDir，zip 循环结束后统一恢复
        var pendingSnapshot: File? = null
        var pendingLegacyDb: File? = null
        var pendingLegacyWal: File? = null

        ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
            var entry: ZipEntry?
            while (zipIn.nextEntry.also { entry = it } != null) {
                entry?.let { zipEntry ->
                    Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                    when (zipEntry.name) {
                        "settings.json" -> {
                            val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                            Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                            try {
                                val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                val settings = json.decodeFromString<Settings>(migratedJson)
                                settingsStore.update(settings)
                                Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                throw Exception("Failed to restore settings: ${e.message}")
                            }
                        }

                        // 新格式：一致性快照（主库 + 可选 wal 兜底）
                        "rikka_hub_snapshot.db" -> {
                            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                val snapshot = File(context.cacheDir, "restore_snapshot.db")
                                FileOutputStream(snapshot).use { zipIn.copyTo(it) }
                                pendingSnapshot = snapshot
                            }
                        }

                        "rikka_hub_snapshot-wal" -> {
                            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                val snapshotWal = File(context.cacheDir, "restore_snapshot.db-wal")
                                FileOutputStream(snapshotWal).use { zipIn.copyTo(it) }
                            }
                        }

                        // 旧格式兼容：复制 db + wal（shm 不再恢复，由 SQLite 重建）
                        "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                            if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                when (zipEntry.name) {
                                    "rikka_hub.db" -> {
                                        val db = File(context.cacheDir, "restore_legacy.db")
                                        FileOutputStream(db).use { zipIn.copyTo(it) }
                                        pendingLegacyDb = db
                                    }

                                    "rikka_hub-wal" -> {
                                        val wal = File(context.cacheDir, "restore_legacy.db-wal")
                                        FileOutputStream(wal).use { zipIn.copyTo(it) }
                                        pendingLegacyWal = wal
                                    }

                                    else -> {
                                        // shm 是共享内存索引，不恢复
                                        Log.i(TAG, "restoreFromBackupFile: Skipping legacy shm entry")
                                    }
                                }
                            }
                        }

                        else -> {
                            if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.UPLOAD}/")
                            ) {
                                restoreSafeEntry(
                                    root = File(context.filesDir, FileFolders.UPLOAD),
                                    relative = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/"),
                                    entryName = zipEntry.name,
                                    zipIn = zipIn,
                                )
                            } else if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                            ) {
                                restoreSkillEntry(zipIn, zipEntry.name)
                            } else if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("${FileFolders.FONTS}/")
                            ) {
                                val fileName = zipEntry.name.substringAfter("${FileFolders.FONTS}/")
                                if (fileName.isNotEmpty() && !fileName.contains('/')) {
                                    restoreSafeEntry(
                                        root = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() },
                                        relative = fileName,
                                        entryName = zipEntry.name,
                                        zipIn = zipIn,
                                    )
                                }
                            } else if (config.items.contains(WebDavConfig.BackupItem.FILES) &&
                                zipEntry.name.startsWith("workspaces/")
                            ) {
                                restoreWorkspaceEntry(zipIn, zipEntry.name)
                            } else {
                                Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                            }
                        }
                    }

                    zipIn.closeEntry()
                }
            }
        }

        // 恢复数据库快照（新格式优先，旧格式兜底）
        if (config.items.contains(WebDavConfig.BackupItem.DATABASE)) {
            val snapshot = pendingSnapshot ?: pendingLegacyDb
            if (snapshot != null && snapshot.exists()) {
                databaseBackupManager.restore(snapshot, legacyWal = pendingLegacyWal)
                Log.i(TAG, "restoreFromBackupFile: Database restored, restart required")
            }
            File(context.cacheDir, "restore_snapshot.db").delete()
            File(context.cacheDir, "restore_snapshot.db-wal").delete()
            File(context.cacheDir, "restore_legacy.db").delete()
            File(context.cacheDir, "restore_legacy.db-wal").delete()
        }

        Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
    }

    /** 恢复普通文件，带 zip slip 路径穿越防护 */
    private fun restoreSafeEntry(
        root: File,
        relative: String,
        entryName: String,
        zipIn: ZipInputStream,
    ) {
        if (relative.isBlank()) return
        val target = safeResolve(root, relative) ?: run {
            Log.w(TAG, "restoreFromBackupFile: Rejected path traversal entry $entryName")
            return
        }
        target.parentFile?.mkdirs()
        try {
            FileOutputStream(target).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored $entryName (${target.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore file $entryName", e)
            throw Exception("Failed to restore file $entryName: ${e.message}")
        }
    }

    /** 恢复工作区文件：workspaces/<wsId>/files/... 或 workspaces/<wsId>/linux/root/... */
    private fun restoreWorkspaceEntry(zipIn: ZipInputStream, entryName: String) {
        val relative = entryName.removePrefix("workspaces/")
        val segments = relative.split('/')
        if (segments.size < 3) {
            Log.w(TAG, "restoreFromBackupFile: Invalid workspace entry $entryName")
            return
        }
        val wsId = segments[0]
        if (!wsId.matches(WORKSPACE_ID_REGEX)) {
            Log.w(TAG, "restoreFromBackupFile: Rejected invalid workspace id in $entryName")
            return
        }
        val wsRoot = File(File(context.filesDir, "workspaces"), wsId)
        val (base, rest) = when {
            segments[1] == "files" -> File(wsRoot, "files") to segments.drop(2).joinToString("/")
            segments[1] == "linux" && segments.size >= 4 && segments[2] == "root" ->
                File(File(wsRoot, "linux"), "root") to segments.drop(3).joinToString("/")

            else -> {
                Log.w(TAG, "restoreFromBackupFile: Rejected unsupported workspace entry $entryName")
                return
            }
        }
        if (rest.isBlank()) return
        restoreSafeEntry(root = base, relative = rest, entryName = entryName, zipIn = zipIn)
    }

    /** zip slip 防护：解析后的路径必须仍位于 root 内 */
    private fun safeResolve(root: File, relative: String): File? {
        val rootCanonical = root.canonicalFile
        val resolved = File(root, relative).canonicalFile
        return if (resolved == rootCanonical || resolved.path.startsWith(rootCanonical.path + File.separator)) {
            resolved
        } else {
            null
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
