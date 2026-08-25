package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.ai.tools.BashPathScanner

/**
 * 会话级工具授权表（「本次会话内全部同意」）。
 *
 * 两种授权粒度：
 * - 目录子树：对带路径概念的工具（read/write/edit/export/bash），授予目标文件父目录的完全访问；
 *   之后同会话内落在该前缀下的调用不再弹审批
 * - 整工具：无路径概念的审批型工具（calendar_create、workspace_bg_start 等）按工具名整会话放行
 *
 * 内存态，挂在 ConversationSession 上随进程/会话回收而失效——即「本次会话」语义。
 */
class ToolGrants {
    private val dirs = LinkedHashSet<String>()
    private val tools = LinkedHashSet<String>()

    @Synchronized
    fun grantDir(prefix: String) {
        dirs += normalizeDir(prefix)
    }

    @Synchronized
    fun grantTool(toolName: String) {
        tools += toolName
    }

    /** 该工具调用是否已被已有授权覆盖；paths 为空时仅整工具授权生效 */
    @Synchronized
    fun covers(toolName: String, paths: List<String>): Boolean {
        if (toolName in tools) return true
        if (paths.isEmpty()) return false
        return paths.all { path ->
            dirs.any { dir -> path == dir || path.startsWith("$dir/") }
        }
    }

    companion object {
        fun normalizeDir(dir: String): String = dir.trimEnd('/').ifBlank { "/" }

        /**
         * 计算一次工具调用的相关路径（rootfs 绝对路径）。
         * read/write/edit 取 path；export 取 source；bash 用启发式扫描器提取命令中的路径。
         */
        fun relevantPaths(toolName: String, args: JsonElement): List<String> {
            val obj = args as? JsonObject ?: return emptyList()
            fun str(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val key = when (toolName) {
                "read", "write", "edit" -> "path"
                "workspace_export_to_phone" -> "source"
                "bash" -> return BashPathScanner.extractPaths(str("command") ?: "")
                else -> return emptyList()
            }
            val path = str(key) ?: return emptyList()
            return listOf(path)
        }

        fun parentDir(path: String): String =
            path.substringBeforeLast('/', "/").ifBlank { "/" }
    }
}
