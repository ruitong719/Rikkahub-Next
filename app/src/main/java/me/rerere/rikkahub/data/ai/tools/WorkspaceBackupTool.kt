package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.sync.BackupScope
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/**
 * create_backup: 复用"备份与恢复—导出到本地"的备份生成逻辑，
 * 在 /workspace 下生成 backup.zip，并返回文件路径供 LLM 处理。
 *
 * 注意：只导出 rikka_hub.db 数据库一致性快照（不含设置/上传文件等隐私数据）。
 * zip 内的数据库是 wal_checkpoint 后的一致性快照。
 */
fun createWorkspaceBackupTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
): Tool = Tool(
    name = "create_backup",
    description = buildString {
        append("Create a database-only backup (rikka_hub.db consistent snapshot) as /workspace/backup.zip inside the workspace. ")
        append("Returns the file path. ")
        append("Use bash (e.g. unzip -l) to inspect it; the database inside is a consistent snapshot.")
    },
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    needsApproval = { needsApproval("create_backup") },
    execute = {
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")

        val settingsStore = getKoin().get<SettingsStore>()
        val webDavSync = getKoin().get<WebDavSync>()
        val backupFile = webDavSync.prepareBackupFile(
            config = settingsStore.settingsFlow.value.webDavConfig.copy(
                items = listOf(BackupScope.DATABASE)
            ),
            // 只导出数据库文件，不含 settings.json
            includeSettings = false,
        )

        // 复制到工作区文件区 /workspace/backup.zip（固定文件名，每次覆盖）
        val workspaceManager = getKoin().get<WorkspaceManager>()
        val target = File(workspaceManager.filesDir(workspace.root), "backup.zip")
        backupFile.copyTo(target, overwrite = true)
        backupFile.delete()

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("path", "/workspace/backup.zip")
                    put("name", "backup.zip")
                    put("sizeBytes", target.length())
                    put("createdAt", System.currentTimeMillis())
                    put("message", "Backup file created. Use bash to inspect it.")
                }.toString()
            )
        )
    },
)
