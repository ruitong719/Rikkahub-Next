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

    fun isInsideRoots(path: String, roots: List<String>): Boolean {
        val normalized = path.trimEnd('/').ifBlank { "/" }
        return roots.any { root ->
            val r = root.trimEnd('/').ifBlank { "/" }
            normalized == r || normalized.startsWith("$r/")
        }
    }
}
