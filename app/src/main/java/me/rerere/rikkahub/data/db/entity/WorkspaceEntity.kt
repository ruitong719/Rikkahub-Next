package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceShellStatus

/**
 * 免审批区出厂默认（仅影响新建工作区）。
 *
 * 注意：这里**不含 /workspace**——/workspace 有独立的目录级审批规则
 * （本层免审批、首层子目录需审批），见 WorkspaceWritePolicy。
 */
val DEFAULT_WRITABLE_ROOTS: List<String> = listOf("/tmp", "/skills", "/upload", "/tool_outputs", "/root")
const val DEFAULT_WRITABLE_ROOTS_JSON: String = "[\"/tmp\",\"/skills\",\"/upload\",\"/tool_outputs\",\"/root\"]"

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
    // [已废弃] 逐工具审批的用户覆盖项 (toolName -> needsApproval)。
    // 审批已改为目录级（WorkspaceWritePolicy + ToolGrants），此列仅为兼容旧库保留，不再读写。
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    // 工具提示词的用户覆盖项 (toolName -> prompt)，未覆盖的工具沿用默认提示词（WorkspaceToolPrompts.kt）
    @ColumnInfo("tool_prompts")
    val toolPrompts: String? = null,
    // <workspace> 引导提示词的分段用户覆盖 (segmentKey -> text)，缺失/空白段沿用默认（WorkspacePromptSegments.kt）
    @ColumnInfo("prompt_overrides")
    val promptOverrides: String? = null,
    // 免审批区（rootfs 绝对路径前缀 JSON 数组）：区内的 write/edit（及 bash 写路径）免审批。
    // 空数组 = 无免审批区；解析失败回退 DEFAULT_WRITABLE_ROOTS
    @ColumnInfo("writable_roots", defaultValue = "[\"/workspace\",\"/tmp\"]")
    val writableRoots: String = DEFAULT_WRITABLE_ROOTS_JSON,
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
) {
    /**
     * 免审批区列表。旧数据里的 /workspace 会被剥离——/workspace 现在走目录级审批，
     * 不允许通过免审批区整体放行。
     */
    fun writableRootsList(): List<String> = runCatching {
        JsonInstant.decodeFromString<List<String>>(writableRoots)
    }.getOrElse { DEFAULT_WRITABLE_ROOTS }
        .filterNot { it.trimEnd('/') == "/workspace" }

    fun toolPromptOverrides(): Map<String, String> = runCatching {
        JsonInstant.decodeFromString<Map<String, String>>(toolPrompts ?: "{}")
    }.getOrDefault(emptyMap())

    fun promptSegmentOverrides(): Map<String, String> = runCatching {
        JsonInstant.decodeFromString<Map<String, String>>(promptOverrides ?: "{}")
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
