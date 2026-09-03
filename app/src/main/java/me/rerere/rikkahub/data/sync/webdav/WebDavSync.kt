package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.DatabaseBackupManager
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.sync.BackupPolicy
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.data.sync.PendingRestore
import me.rerere.rikkahub.data.sync.BackupScope
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

/** 备份 zip 中属于附件（filesDir 下）的顶层文件夹，映射到 PendingRestore 的 files/ 区 */
private val ATTACHMENT_FOLDERS = setOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, "workspaces")

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

    suspend fun prepareBackupFile(
        config: WebDavConfig,
        includeSettings: Boolean = true,
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            if (includeSettings) {
                addVirtualFileToZip(
                    zipOut = zipOut,
                    name = "settings.json",
                    content = json.encodeToString(settingsStore.settingsFlow.value)
                )
            }

            // Backup database files: 一致性快照（先 wal_checkpoint 合并，避免 WAL 撕裂快照丢失最新会话）
            if (BackupPolicy.hasScope(config.items, BackupScope.DATABASE)) {
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
            if (BackupPolicy.hasScope(config.items, BackupScope.ATTACHMENTS)) {
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

                // 工作区文件: /workspace 文件区 + /root 仅 .bashrc（rootfs 其余内容体积过大，不备份）
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
                        // rootfs 主目录仅备份 .bashrc：其余（.nvm/.cache/已装软件树等）体积过大，
                        // 全量递归会让备份卡死在未 close 的 zip 上。.bashrc 含用户自定义环境变量/别名，体积小且关键。
                        val bashrc = File(wsDir, "linux/root/.bashrc")
                        if (bashrc.exists() && bashrc.isFile) {
                            addFileToZip(
                                zipOut,
                                bashrc,
                                "workspaces/${wsDir.name}/linux/root/.bashrc",
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

    /**
     * 恢复备份：解压到 PendingRestore 暂存目录，校验数据库与设置后 publish。
     * 实际安装发生在下次启动（RikkaHubApp 在 Koin 初始化前 applyPendingRestore），
     * 安装失败自动回滚、原数据保留；用户在此期间重启即完成恢复。
     */
    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting staged restore from ${backupFile.absolutePath}")

        val pending = PendingRestore.forContext(context)
        val staging = pending.createStagingDirectory()
        try {
            val payload = File(staging, "payload")
            var restoredEntries = 0

            ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    entry?.let { zipEntry ->
                        Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")
                        when {
                            zipEntry.name == "settings.json" -> {
                                val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                // 按迁移结果持久化一次（含生成的 ID），保证重启/重试一致性
                                PendingRestore.writeDurably(
                                    File(staging, "settings.json"),
                                    json.encodeToString(json.decodeFromString<Settings>(migratedJson)),
                                )
                                restoredEntries++
                            }

                            // 数据库快照：新格式 rikka_hub.db（+ 可选 wal 兜底），兼容旧名 rikka_hub_snapshot.db
                            zipEntry.name == "rikka_hub.db" || zipEntry.name == "rikka_hub_snapshot.db" -> {
                                if (BackupPolicy.hasScope(config.items, BackupScope.DATABASE)) {
                                    val target = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                                    require(!target.exists()) { "Duplicate backup entry: ${zipEntry.name}" }
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { zipIn.copyTo(it) }
                                    restoredEntries++
                                }
                            }

                            zipEntry.name == "rikka_hub-wal" || zipEntry.name == "rikka_hub_snapshot-wal" -> {
                                if (BackupPolicy.hasScope(config.items, BackupScope.DATABASE)) {
                                    val wal = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}-wal")
                                    require(!wal.exists()) { "Duplicate backup entry: ${zipEntry.name}" }
                                    wal.parentFile?.mkdirs()
                                    FileOutputStream(wal).use { zipIn.copyTo(it) }
                                }
                            }

                            zipEntry.name.endsWith("-shm") -> {
                                // shm 是共享内存索引，由 SQLite 重建，从不恢复
                                Log.i(TAG, "restoreFromBackupFile: Skipping shm entry ${zipEntry.name}")
                            }

                            isAttachmentEntry(zipEntry.name) -> {
                                if (BackupPolicy.hasScope(config.items, BackupScope.ATTACHMENTS)) {
                                    stageAttachmentEntry(payload, zipIn, zipEntry.name)
                                    restoredEntries++
                                }
                            }

                            else -> Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                        }
                        zipIn.closeEntry()
                    }
                }
            }

            // 数据库校验：checkpoint + integrity_check + Room 迁移到最新 schema；失败即整体放弃
            val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
            if (stagedDatabase.exists()) {
                DatabaseBackup.normalize(context, stagedDatabase)
                val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                try {
                    DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                } finally {
                    room.close()
                }
                DatabaseBackup.removeSidecars(stagedDatabase)
            }

            require(restoredEntries > 0) { "No selected data found in the backup" }

            pending.publish(staging)
            Log.i(TAG, "restoreFromBackupFile: Restore staged, will apply on next launch")
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun isAttachmentEntry(name: String): Boolean {
        val folder = name.substringBefore('/')
        return folder in ATTACHMENT_FOLDERS && '/' in name
    }

    /** 附件条目按文件夹映射到 PendingRestore 的 files/ 区，目标目录为 filesDir/<folder>/... */
    private fun stageAttachmentEntry(payload: File, zipIn: ZipInputStream, entryName: String) {
        val folder = entryName.substringBefore('/')
        val relative = entryName.substringAfter('/')
        // workspaces 条目保持原有结构校验：workspaces/<wsId>/files/... 或 workspaces/<wsId>/linux/root/...
        if (folder == "workspaces") {
            val segments = relative.split('/')
            val wsId = segments.firstOrNull()
            if (wsId == null || !wsId.matches(WORKSPACE_ID_REGEX) || segments.size < 3 ||
                !(segments[1] == "files" || (segments[1] == "linux" && segments.size >= 4 && segments[2] == "root"))
            ) {
                Log.w(TAG, "restoreFromBackupFile: Rejected invalid workspace entry $entryName")
                return
            }
        }
        val target = PendingRestore.resolveInside(File(payload, "files"), entryName)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { zipIn.copyTo(it) }
        Log.i(TAG, "restoreFromBackupFile: Staged $entryName (${target.length()} bytes)")
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
