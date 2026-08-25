package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceShellStatus

/** 写入安全区出厂默认：工作区文件区 + 临时目录 */
val DEFAULT_WRITABLE_ROOTS: List<String> = listOf("/workspace", "/tmp")
const val DEFAULT_WRITABLE_ROOTS_JSON: String = "[\"/workspace\",\"/tmp\"]"

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    // 导出到手机的 SAF 树目录 URI（用户在工作区设置中选择），null 表示未配置
    @ColumnInfo("export_target_uri")
    val exportTargetUri: String? = null,
    // 工具提示词的用户覆盖项 (toolName -> prompt)，未覆盖的工具沿用默认提示词（WorkspaceToolPrompts.kt）
    @ColumnInfo("tool_prompts")
    val toolPrompts: String? = null,
    // 写入安全区（rootfs 绝对路径前缀 JSON 数组）：区外的 write/edit/bash 调用强制审批。
    // 空数组 = 全部强制审批（fail-safe）；解析失败回退 DEFAULT_WRITABLE_ROOTS
    @ColumnInfo("writable_roots", defaultValue = "[\"/workspace\",\"/tmp\"]")
    val writableRoots: String = DEFAULT_WRITABLE_ROOTS_JSON,
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    fun writableRootsList(): List<String> = runCatching {
        JsonInstant.decodeFromString<List<String>>(writableRoots)
    }.getOrElse { DEFAULT_WRITABLE_ROOTS }

    fun toolPromptOverrides(): Map<String, String> = runCatching {
        JsonInstant.decodeFromString<Map<String, String>>(toolPrompts ?: "{}")
    }.getOrDefault(emptyMap())

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
