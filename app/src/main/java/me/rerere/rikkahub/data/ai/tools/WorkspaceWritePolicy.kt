package me.rerere.rikkahub.data.ai.tools

/**
 * 目录级写审批策略（替代原「逐工具开关」）。
 *
 * 规则（仅 write/edit 以及 bash 定向检测出的写路径参与）：
 * 1. 免审批区（工作区配置，默认 /tmp、/skills、/upload、/tool_outputs、/root）内 → 免审批
 * 2. 直接写在 /workspace 本层（父目录就是 /workspace）→ 免审批
 * 3. /workspace/<首层>/... → 需审批，授权单元 = /workspace/<首层>（含子目录）
 * 4. /mnt/storage/... → 需审批，授权单元 = /mnt/storage（一次审批解锁整棵子树）
 * 5. 其它路径 → 需审批，授权单元 = 父目录
 *
 * 授权本身是会话级的，由 [me.rerere.rikkahub.service.ToolGrants] 持有；
 * 本对象只负责「是否要审批」与「授权单元是谁」，不持有状态。
 */
object WorkspaceWritePolicy {
    const val WORKSPACE_ROOT = "/workspace"
    const val PHONE_MOUNT = "/mnt/storage"

    /** 免审批区内的路径（/workspace 本层单独处理，不通过免审批区名单放行） */
    fun isFree(path: String, freeZones: List<String>): Boolean {
        val normalized = normalize(path)
        if (freeZones.any { normalize(it) != WORKSPACE_ROOT && isInside(normalized, it) }) return true
        if (normalized == WORKSPACE_ROOT) return true
        // 直接写在 /workspace 本层（父目录即根）免审批；子目录走审批
        return parentDir(normalized) == WORKSPACE_ROOT
    }

    /** 需要审批时返回授权单元，免审批返回 null */
    fun approvalUnit(path: String, freeZones: List<String>): String? =
        if (isFree(path, freeZones)) null else unitFor(path)

    fun needsApproval(path: String, freeZones: List<String>): Boolean =
        !isFree(path, freeZones)

    /**
     * 与是否免审批无关的授权单元：/workspace 首层目录、手机存储挂载根、或父目录。
     * 用户点「本次会话内全部同意」时用它登记。
     */
    fun unitFor(path: String): String {
        val normalized = normalize(path)
        if (isInside(normalized, WORKSPACE_ROOT)) {
            val rest = normalized.removePrefix(WORKSPACE_ROOT).trimStart('/')
            val first = rest.substringBefore('/')
            return if (first.isEmpty()) WORKSPACE_ROOT else "$WORKSPACE_ROOT/$first"
        }
        if (isInside(normalized, PHONE_MOUNT)) return PHONE_MOUNT
        return parentDir(normalized)
    }

    fun isInside(path: String, root: String): Boolean {
        val p = normalize(path)
        val r = normalize(root)
        return p == r || p.startsWith("$r/")
    }

    fun parentDir(path: String): String =
        normalize(path).substringBeforeLast('/', "/").ifBlank { "/" }

    fun normalize(path: String): String =
        path.trim().ifBlank { "/" }.let { if (it.length > 1) it.trimEnd('/') else it }
}
