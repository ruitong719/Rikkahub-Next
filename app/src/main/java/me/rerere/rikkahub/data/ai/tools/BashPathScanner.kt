package me.rerere.rikkahub.data.ai.tools

/**
 * bash 命令的路径启发式扫描（借鉴 opencode shell.ts 的 tree-sitter 方案，降级为正则实现）：
 * 从命令串中提取绝对路径候选 + `../` 上跳序列，用于判断是否触及写入安全区之外。
 *
 * 这是审批 UX 的 best-effort 启发式而非安全边界（真正的边界是 PRoot 沙箱本身）；
 * 误报（如 read-only 命令引用区外文件）只会多弹一次审批。
 */
object BashPathScanner {
    // 内核伪设备不参与落盘，忽略以免 2>/dev/null 之类的常见写法逼着每条命令都弹窗
    private val IGNORED_PREFIXES = listOf("/dev", "/proc", "/sys")

    // 绝对路径 token：允许常见文件名字符；前面不能是单词字符（排除 URL 的 host 段等）
    private val ABS_PATH_REGEX = Regex("""(?<![\w+@.-])(/[A-Za-z0-9._+@:@-]+(?:/[A-Za-z0-9._+@:-]+)*/)""")
    private val RELATIVE_CLIMB_REGEX = Regex("""(^|[\s'";&|(=])(\.\./)+""")

    // 明确写操作的指示：重定向、常见写命令、sed -i、写入型 git 子命令
    private val REDIRECT_TARGET_REGEX = Regex("""\d?>>?\s*("[^"]*"|'[^']*'|[^\s;&|()<>]+)""")
    private val WRITE_INDICATOR_REGEX = Regex(
        """(^|[\s;&|(])(tee|truncate|mkdir|touch|rm|mv|cp|ln|install|dd)\s|sed\s+[^;&|]*\s-i|git\s+(checkout|reset|clean|stash|restore|rm|mv|apply)\b"""
    )

    /** 提取命令中的 rootfs 绝对路径（归一化去重）；`../` 上跳以 "/" 表示「越出工作区」 */
    fun extractPaths(command: String): List<String> {
        val paths = ABS_PATH_REGEX.findAll(command)
            .map { it.value.trimEnd('/', ':', ',', ')', '\'', '"') }
            .filter { it.length > 1 }
            .filterNot { p -> IGNORED_PREFIXES.any { p == it || p.startsWith("$it/") } }
            .toMutableSet()
        if (RELATIVE_CLIMB_REGEX.containsMatchIn(command)) {
            paths += "/.."
        }
        return paths.toList()
    }

    /** 命令是否触及安全区之外的路径 */
    fun touchesOutsideRoots(command: String, writableRoots: List<String>): Boolean =
        extractPaths(command).any { !isInsideRoots(it, writableRoots) }

    /**
     * 定向提取「明确的写操作」目标路径（绝对路径），用于 bash 的目录级审批。
     *
     * best-effort：只认绝对路径，识别不了相对路径、变量、`cd` 后的隐式目标、脚本内部写操作；
     * 定位是「防君子不防小人」，不是安全边界。返回空 = 不拦。
     * 优先用重定向目标，避免把 read-only 的源路径也算进去造成误报。
     */
    fun extractWritePaths(command: String): List<String> {
        val redirects = REDIRECT_TARGET_REGEX.findAll(command)
            .map { unquote(it.groupValues[1]) }
            .filter { it.startsWith("/") }
            .filterNot { p -> IGNORED_PREFIXES.any { p == it || p.startsWith("$it/") } }
            .toList()
        if (redirects.isNotEmpty()) return redirects.distinct()

        if (!WRITE_INDICATOR_REGEX.containsMatchIn(command)) return emptyList()
        return extractPaths(command).filter { it != "/.." }
    }

    private fun unquote(token: String): String =
        token.removeSurrounding("\"").removeSurrounding("'").trim()

    fun isInsideRoots(path: String, roots: List<String>): Boolean {
        val normalized = path.trimEnd('/').ifBlank { "/" }
        return roots.any { root ->
            val r = root.trimEnd('/').ifBlank { "/" }
            normalized == r || normalized.startsWith("$r/")
        }
    }
}
