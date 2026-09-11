package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.BashPathScanner
import me.rerere.rikkahub.data.ai.tools.WorkspaceWritePolicy

/**
 * 会话级目录授权表（「本次会话内全部同意」）。
 *
 * 授权粒度是**目录**：一次审批授予 [WorkspaceWritePolicy.unitFor] 算出的授权单元
 * （/workspace 首层目录、/mnt/storage 挂载根、或普通路径的父目录），
 * 之后同会话内落在该前缀下的 write/edit（以及 bash 检测出的写路径）不再弹审批。
 *
 * 内存态，挂在 ConversationSession 上随进程/会话回收而失效——即「本次会话」语义。
 */
class ToolGrants {
    private val dirs = LinkedHashSet<String>()

    @Synchronized
    fun grantDir(prefix: String) {
        dirs += normalizeDir(prefix)
    }

    /** 所有路径都已被某个已授权目录覆盖时返回 true；paths 为空表示该调用不受目录审批约束 */
    @Synchronized
    fun covers(paths: List<String>): Boolean {
        if (paths.isEmpty()) return false
        return paths.all { path -> dirs.any { WorkspaceWritePolicy.isInside(path, it) } }
    }

    companion object {
        fun normalizeDir(dir: String): String = dir.trimEnd('/').ifBlank { "/" }

        /**
         * 计算一次工具调用的相关写入路径（rootfs 绝对路径）。
         * 只有 write/edit 与 bash（定向检测）参与目录审批，其余工具返回空。
         */
        fun relevantPaths(toolName: String, args: JsonElement): List<String> {
            val obj = args as? JsonObject ?: return emptyList()
            fun str(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return when (toolName) {
                "write", "edit" -> str("path")?.let { listOf(it) } ?: emptyList()
                "bash" -> BashPathScanner.extractWritePaths(str("command") ?: "")
                else -> emptyList()
            }
        }
    }
}
