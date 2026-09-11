package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.ai.ShellRunKey
import me.rerere.rikkahub.data.ai.ShellRunMonitor
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.DEFAULT_WRITABLE_ROOTS
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

/**
 * 组装工作区工具。
 *
 * 审批已改为**目录级**（[WorkspaceWritePolicy]）：不再有逐工具开关，只有 write/edit
 * 以及 bash 定向检测出的写路径会弹审批；read/glob/grep/git/bgt/backup 一律放行。
 * 免审批区来自工作区配置 [WorkspaceRepository]，默认 [DEFAULT_WRITABLE_ROOTS]。
 */
suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    conversationId: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val workspace = workspaceRepository.getById(workspaceId)
    val freeZones = workspace?.writableRootsList() ?: DEFAULT_WRITABLE_ROOTS

    val writeApproval: (JsonElement) -> Boolean = { args ->
        runCatching {
            WorkspaceWritePolicy.needsApproval(args.jsonObject.absolutePath("path"), freeZones)
        }.getOrDefault(true)
    }
    val bashApproval: (JsonElement) -> Boolean = { args ->
        val command = args.jsonObject.string("command").orEmpty()
        BashPathScanner.extractWritePaths(command).any { WorkspaceWritePolicy.needsApproval(it, freeZones) }
    }
    val allow: (String) -> Boolean = { false }

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, allow, workspaceRepository),
        createWriteFileTool(workspaceId, writeApproval, workspaceRepository),
        createEditFileTool(workspaceId, writeApproval, workspaceRepository),
        createShellTool(workspaceId, bashApproval, workspaceRepository, shellCwd, conversationId),
        createGlobTool(workspaceId, allow, workspaceRepository),
        createGrepTool(workspaceId, allow, workspaceRepository),
        createGitTool(workspaceId, allow, workspaceRepository),
    ) + createWorkspaceBgTools(workspaceId, allow, workspaceRepository, conversationId) +
        listOf(createWorkspaceBackupTool(workspaceId, allow, workspaceRepository))
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private const val DEFAULT_READ_LIMIT_LINES = 2000
private const val MAX_READ_LIMIT_LINES = 2000
private const val MAX_READ_LINE_LENGTH = 2000

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "read",
    description = """
        Read a file from the workspace Rootfs. Paths must be absolute inside Rootfs.
        Usage:
        - The path parameter must be an absolute path inside Rootfs; use /workspace for the workspace files area.
        - By default, up to $DEFAULT_READ_LIMIT_LINES lines are returned from the start of the file.
        - Use offset (1-indexed line number) with limit to page through large files.
        - Output lines are prefixed with their line number like `12: content`; never include that prefix in old_text when editing.
        - Image files are returned as image attachments; binary files are rejected.
        - Avoid tiny repeated slices (30-line chunks). If you need more context, read a larger window.
        - Call this tool in parallel when you know there are multiple files you want to read.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Line number to start reading from (1-indexed). Defaults to 1.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of lines to read. Defaults to $DEFAULT_READ_LIMIT_LINES.")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("read") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val offset = params.string("offset")?.toIntOrNull() ?: 1
        val limit = params.string("limit")?.toIntOrNull() ?: DEFAULT_READ_LIMIT_LINES
        require(offset >= 1) { "offset must be >= 1 (1-indexed)" }
        require(limit in 1..MAX_READ_LIMIT_LINES) {
            "limit must be between 1 and $MAX_READ_LIMIT_LINES"
        }

        when (val kind = workspaceRepository.probeRootfsKind(workspaceId, path)) {
            is RootfsKind.Missing -> error(missingFileMessage(workspaceId, workspaceRepository, path))
            is RootfsKind.Directory -> UIMessagePart.Text(
                buildJsonObject {
                    put("path", path)
                    put("type", "directory")
                    put("entries", kind.entriesJson())
                    put("offset", offset)
                    put("totalEntries", kind.totalEntries)
                    put("truncated", kind.truncated)
                }.toString()
            )
            is RootfsKind.File -> {
                if (path.isImagePath()) {
                    workspaceRepository.readImageInRootfs(workspaceId, path).first()
                } else {
                    val bytes = workspaceRepository.readRootfsBuffer(workspaceId, path).toByteArray()
                    require(!isBinaryContent(bytes)) {
                        "Cannot read binary file: $path. Use bash with tools like strings/hexdump if you really need it."
                    }
                    UIMessagePart.Text(readTextPage(path, bytes.decodeToString(), offset, limit))
                }
            }
        }.let { part -> listOf(part) }
    },
)

private const val ROOTFS_KIND_PROBE_MAX_ENTRIES = 4000

/** 探测 rootfs 路径类型; 目录时顺带返回排序后的条目列表(type\\tname 行) */
private sealed interface RootfsKind {
    data object Missing : RootfsKind
    data object File : RootfsKind
    data class Directory(val lines: List<String>, val truncated: Boolean) : RootfsKind

    val totalEntries: Int get() = if (this is Directory) lines.size else 0

    fun entriesJson(): JsonArray = buildJsonArray {
        if (this@RootfsKind !is Directory) return@buildJsonArray
        lines.forEach { line ->
            val type = line.substringBefore('\t', "")
            val name = line.substringAfter('\t', "")
            if (name.isEmpty()) return@forEach
            add(JsonPrimitive(if (type == "d") "$name/" else name))
        }
    }
}

private suspend fun WorkspaceRepository.probeRootfsKind(
    workspaceId: String,
    path: String,
): RootfsKind {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Probe path",
        command = """
            p=$pathArg
            if [ ! -e "${'$'}p" ] && [ ! -L "${'$'}p" ]; then printf 'missing\n'; exit 0; fi
            if [ -d "${'$'}p" ]; then
              printf 'dir\n'
              find "${'$'}p" -mindepth 1 -maxdepth 1 -printf '%y\t%f\n' 2>/dev/null | LC_ALL=C sort | head -n $ROOTFS_KIND_PROBE_MAX_ENTRIES
            else
              printf 'file\n'
            fi
        """.trimIndent(),
    )
    val output = result.stdout
    return when {
        output.startsWith("missing") -> RootfsKind.Missing
        output.startsWith("file") -> RootfsKind.File
        else -> {
            val lines = output.lines().drop(1).filter { it.isNotBlank() }
            RootfsKind.Directory(
                lines = lines,
                truncated = lines.size >= ROOTFS_KIND_PROBE_MAX_ENTRIES,
            )
        }
    }
}

/** 文件不存在时的报错文案: 附上同目录下名字相近的条目建议(opencode 式 did-you-mean), 减少一轮试错 */
private suspend fun missingFileMessage(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    path: String,
): String {
    val parent = path.trimEnd('/').substringBeforeLast('/', "/")
    val base = path.substringAfterLast('/')
    val suggestions = runCatching {
        when (val parentKind = workspaceRepository.probeRootfsKind(workspaceId, parent)) {
            is RootfsKind.Directory -> parentKind.lines
                .map { it.substringAfter('\t', "") }
                .filter { entry ->
                    entry.lowercase().contains(base.lowercase()) || base.lowercase().contains(entry.lowercase())
                }
                .take(3)

            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    val parentDir = parent.removePrefix("/workspace").ifBlank { "/" }
    return if (suggestions.isNotEmpty()) {
        "File not found: $path (workspace: $parentDir)\n\nDid you mean one of these?\n" +
            suggestions.joinToString("\n") { "$parent/$it" }
    } else {
        "File not found: $path (workspace: $parentDir)"
    }
}

/**
 * 二进制嗅探: 首个 NUL 字节, 或不可打印字符占比 >30% 即判定二进制。
 * 判定前不整串解码, 避免 mojibake 污染模型上下文。
 */
private fun isBinaryContent(bytes: ByteArray): Boolean {
    val sample = if (bytes.size > 8 * 1024) bytes.copyOf(8 * 1024) else bytes
    if (sample.isEmpty()) return false
    var nonPrintable = 0
    for (b in sample) {
        // Byte 有符号：必须转无符号再比较，否则 >=0x80 的字节为负数，
        // 会被当成控制字符计入——UTF-8 中文文档因此被整体误判为二进制
        val u = b.toInt() and 0xFF
        if (u == 0) return true
        if (u < 9 || u in 14..31) nonPrintable++
    }
    return nonPrintable.toDouble() / sample.size > BINARY_NON_PRINTABLE_RATIO
}

private const val BINARY_NON_PRINTABLE_RATIO = 0.3

/**
 * 文本分页: 返回带行号前缀 `N: content` 的窗口, 截断时附续读提示(opencode 同款交互)。
 */
private fun readTextPage(path: String, text: String, offset: Int, limit: Int): String {
    val allLines = text.split('\n').let { lines ->
        // 结尾换行产生的空尾行不计入总行数
        if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    }
    val totalLines = allLines.size
    if (offset > totalLines) {
        error("Offset $offset is out of range for this file ($totalLines lines)")
    }

    var lineTruncated = false
    val window = allLines.drop(offset - 1).take(limit).mapIndexed { index, line ->
        val number = offset + index
        if (line.length > MAX_READ_LINE_LENGTH) {
            lineTruncated = true
            "$number: " + line.take(MAX_READ_LINE_LENGTH) + "... (line truncated to $MAX_READ_LINE_LENGTH chars)"
        } else {
            "$number: $line"
        }
    }

    val lastLine = offset + window.size - 1
    val truncated = lineTruncated || offset - 1 + window.size < totalLines
    val content = buildString {
        append(window.joinToString("\n"))
        if (offset - 1 + window.size < totalLines) {
            append("\n\n(Showing lines $offset-$lastLine of $totalLines. Use offset=${lastLine + 1} to continue.)")
        } else {
            append("\n\n(End of file - total $totalLines lines)")
        }
    }

    return buildJsonObject {
        put("path", path)
        put("type", "file")
        put("totalLines", totalLines)
        put("offset", offset)
        put("endLine", lastLine)
        put("content", content)
        put("truncated", truncated)
        if (truncated) put("nextOffset", lastLine + 1)
    }.toString()
}

private fun createWriteFileTool(
    workspaceId: String,
    approval: (JsonElement) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "write",
    description = """
        Write a UTF-8 text file into the workspace Rootfs. Paths must be absolute inside Rootfs; /workspace is the files area.
        Usage:
        - This tool overwrites the existing file at the path unless overwrite=false (then it skips instead).
        - If a file already exists, ALWAYS prefer edit with old_text/new_text; read it with read before doing a full-file replacement.
        - NEVER proactively create documentation files or README files. Only create them when explicitly requested by the user.
        - Only use emojis if the user explicitly requests it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = approval,
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    approval: (JsonElement) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "edit",
    description = """
        Edit a UTF-8 text file in the workspace Rootfs by performing exact string replacement. Paths must be absolute inside Rootfs.
        Usage:
        - You must read the file at least once before editing. Copy old_text verbatim from the read output, WITHOUT the `N: ` line-number prefixes.
        - The edit fails if old_text is not found, or if it matches multiple locations (set replace_all=true for every occurrence).
        - When a match is ambiguous, include more surrounding lines in old_text to make it unique.
        - If no exact match is found, whitespace-tolerant fallbacks run automatically: trimmed lines, block anchors, normalized whitespace runs, and escaped \\n / \\t literals.
        - Only use emojis if the user explicitly requests it.
        - ALWAYS prefer editing existing files. NEVER create new files unless explicitly required.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = approval,
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createGlobTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "glob",
    description = """
        Find files by glob pattern inside the workspace files area (mounted at /workspace). Read-only.
        Usage:
        - Patterns use Java NIO glob syntax, e.g. `**/*.kt`, `app/src/**/*.ts`, `*.md`.
        - Omit path to search the whole workspace; pass a subdirectory (relative or /workspace/...) to narrow it.
        - This only searches the workspace files area, NOT the Linux rootfs system directories.
        - Prefer this over bash find/ls for locating files.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "The glob pattern to match files against (e.g. \"**/*.kt\")")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Directory to search in, relative to the workspace files area or prefixed with /workspace. Defaults to the workspace root."
                    )
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("glob") },
    execute = {
        val params = it.jsonObject
        val pattern = params.string("pattern")?.trim().orEmpty()
        require(pattern.isNotEmpty()) { "pattern is required" }
        val path = params.string("path")?.toWorkspaceRelativePath("path") ?: ""
        val entries = workspaceRepository.glob(workspaceId, pattern, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("pattern", pattern)
                    put("count", entries.size)
                    put(
                        "matches",
                        buildJsonArray {
                            entries.forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("path", "/workspace/" + entry.path)
                                        put("type", if (entry.isDirectory) "directory" else "file")
                                        if (!entry.isDirectory) put("sizeBytes", entry.sizeBytes)
                                    }
                                )
                            }
                        }
                    )
                }.toString()
            )
        )
    },
)

private fun createGrepTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "grep",
    description = """
        Search file contents inside the workspace files area (mounted at /workspace) by regex or literal string. Read-only.
        Usage:
        - By default the query is a literal string with case-insensitive matching; pass regex=true for a regular expression.
        - Use include to restrict to a file glob (e.g. "*.kt", "**/*.ts").
        - Omit path to search the whole workspace; pass a subdirectory (relative or /workspace/...) to narrow it.
        - This only searches the workspace files area, NOT the Linux rootfs system directories.
        - Prefer this over bash grep/rg for searching code.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "The string or regex to search for in file contents")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Directory to search in, relative to the workspace files area or prefixed with /workspace. Defaults to the workspace root."
                    )
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Treat pattern as a regular expression. Defaults to false (literal).")
                })
                put("ignore_case", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Case-insensitive matching. Defaults to true.")
                })
                put("include", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file glob to include in the search (e.g. \"*.kt\").")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("grep") },
    execute = {
        val params = it.jsonObject
        val pattern = params.string("pattern")?.trim().orEmpty()
        require(pattern.isNotEmpty()) { "pattern is required" }
        val path = params.string("path")?.toWorkspaceRelativePath("path") ?: ""
        val regex = params.bool("regex") ?: false
        val ignoreCase = params.bool("ignore_case") ?: true
        val include = params.string("include")?.trim()?.takeIf { it.isNotEmpty() }
        val matches = workspaceRepository.grep(workspaceId, pattern, path, regex, ignoreCase, include)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("pattern", pattern)
                    put("count", matches.size)
                    put(
                        "matches",
                        buildJsonArray {
                            matches.forEach { match ->
                                add(
                                    buildJsonObject {
                                        put("path", "/workspace/" + match.path)
                                        put("line", match.line)
                                        put(
                                            "text",
                                            if (match.text.length > GREP_MAX_LINE_CHARS) {
                                                match.text.take(GREP_MAX_LINE_CHARS) + "..."
                                            } else match.text
                                        )
                                    }
                                )
                            }
                        }
                    )
                }.toString()
            )
        )
    },
)

private const val GREP_MAX_LINE_CHARS = 500

/** git 只读子命令白名单（路线 A：rootfs 内 git 二进制，固定 argv，不拼接 shell） */
private val GIT_READONLY_ACTIONS = listOf(
    "status",
    "diff",
    "log",
    "show",
    "branch",
    "ls-files",
    "blame",
    "rev-parse",
)

private fun createGitTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "git",
    description = """
        Inspect the git repository inside the workspace using read-only git commands. Paths are inside the workspace files area (/workspace).
        Usage:
        - action selects a read-only subcommand: status, diff, log, show, branch, ls-files, blame, rev-parse.
        - target is an optional path or revision argument (for diff/log/ls-files it is a pathspec; for show/blame/rev-parse it is a revision or file). Must not start with '-'.
        - This never commits, checks out, resets, stages, or pushes; it only observes.
        - Requires git to be installed in the workspace rootfs.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Read-only git action. One of: ${GIT_READONLY_ACTIONS.joinToString(", ")}."
                    )
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Directory inside the workspace files area to run git in, relative or prefixed with /workspace. Defaults to /workspace."
                    )
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional path or revision argument. Must not start with '-'.")
                })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { needsApproval("git") },
    execute = {
        val params = it.jsonObject
        val action = params.string("action")?.trim()?.lowercase().orEmpty()
        require(action in GIT_READONLY_ACTIONS) {
            "unsupported action: $action. Allowed: ${GIT_READONLY_ACTIONS.joinToString(", ")}"
        }
        val dir = params.string("path")?.toRootfsWorkspacePath("path") ?: "/workspace"
        val target = params.string("target")?.trim()?.takeIf { it.isNotEmpty() }
        if (target != null) {
            require(!target.startsWith("-")) { "target must not start with '-'" }
            require(!target.contains('\u0000')) { "target contains invalid character" }
        }
        val argv = gitArgv(action, target)
        val command = "git -C ${dir.shellQuote()} " + argv.joinToString(" ") { it.shellQuote() }
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = command,
            timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        )
        val notRepo = result.stderr.contains("not a git repository", ignoreCase = true)
        val gitMissing = result.exitCode == 127 ||
            result.stderr.contains("git: command not found") ||
            result.stderr.contains("git: not found")
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("action", action)
                    put("path", dir)
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    if (result.stderr.isNotBlank()) put("stderr", result.stderr)
                    if (result.truncated) put("truncated", true)
                    when {
                        gitMissing -> put(
                            "hint",
                            "git is not installed in the workspace rootfs. Install it via bash (e.g. `apt-get update && apt-get install -y git`)."
                        )
                        notRepo -> put(
                            "hint",
                            "This directory is not a git repository. Initialize or clone one inside the workspace first."
                        )
                    }
                }.toString()
            )
        )
    },
)

private fun gitArgv(action: String, target: String?): List<String> = when (action) {
    "status" -> listOf("status", "--porcelain=v1", "-b")
    "diff" -> buildList {
        add("diff")
        add("--no-color")
        if (target != null) {
            add("--")
            add(target)
        }
    }
    "log" -> buildList {
        add("log")
        add("--no-color")
        add("-n")
        add("50")
        if (target != null) {
            add("--")
            add(target)
        }
    }
    "show" -> buildList {
        add("show")
        add("--no-color")
        add("--stat")
        add("-n")
        add("1")
        if (target != null) add(target)
    }
    "branch" -> listOf("branch", "--no-color", "-a")
    "ls-files" -> buildList {
        add("ls-files")
        if (target != null) {
            add("--")
            add(target)
        }
    }
    "blame" -> {
        requireNotNull(target) { "target (file path) is required for blame" }
        listOf("blame", "--no-color", target)
    }
    "rev-parse" -> listOf("rev-parse", "--show-toplevel", "--abbrev-ref", "HEAD")
    else -> error("unsupported action: $action")
}

private fun createShellTool(
    workspaceId: String,
    approval: (JsonElement) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    conversationId: String? = null,
) = Tool(
    name = "bash",
    description = buildString {
        append("Run a shell command inside the workspace PRoot Linux environment (bash -c). The workspace files area is mounted at /workspace. ")
        append("IMPORTANT: This tool is for terminal operations like git, package managers, builds, and process checks. DO NOT use it to read, write, or edit files - use the dedicated read/write/edit tools instead, they are safer and their outputs are easier to act on. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready. ")
        append("Git discipline: only commit, amend, push, or create PRs when explicitly requested. Before committing, inspect 'git status', 'git diff', and 'git log --oneline -10'; stage only intended files and never commit secrets. Write a concise commit message that matches the repo style. Do not update git config, skip hooks, use interactive '-i', force-push, or create empty commits unless explicitly requested. If a commit fails or hooks reject it, fix the issue and create a new commit; do not amend the failed commit.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = approval,
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS

        // 实验性开关关闭时保持原有阻塞行为, 不注册直播
        val liveOutput = getKoin().get<SettingsStore>()
            .settingsFlow.value.displaySetting.enableShellLiveOutput
        // GenerationHandler 通过 ShellRunKey 注入 toolCallId; 缺失时回退 conversationId/workspaceId
        val runKey = coroutineContext[ShellRunKey]?.id ?: conversationId ?: workspaceId

        if (liveOutput) {
            getKoin().get<ShellRunMonitor>().start(runKey, command, cwd.takeIf { it.isNotBlank() })
        }
        try {
            val result = if (liveOutput) {
                val monitor = getKoin().get<ShellRunMonitor>()
                workspaceRepository.executeCommandStreaming(workspaceId, command, cwd, timeoutMillis) { isStderr, chunk ->
                    monitor.append(runKey, isStderr, chunk)
                }
            } else {
                workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("exitCode", result.exitCode)
                        put("stdout", result.stdout)
                        put("stderr", result.stderr)
                        put("timedOut", result.timedOut)
                        if (result.truncated) put("truncated", true)
                    }.toString()
                )
            )
        } finally {
            if (liveOutput) {
                getKoin().get<ShellRunMonitor>().finish(runKey)
            }
        }
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun kotlinx.serialization.json.JsonObject.bool(name: String): Boolean? =
    this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

/**
 * glob/grep 用：接受工作区相对路径或以 /workspace 开头的路径，归一为引擎 resolvePath 语义的相对路径。
 * 其它绝对路径（rootfs 系统目录）明确拒绝——这两个工具只覆盖 FILES 区。
 */
private fun String.toWorkspaceRelativePath(field: String): String {
    val raw = trim().replace('\\', '/')
    require(raw.isNotEmpty()) { "$field must not be empty" }
    require(!raw.contains('\u0000')) { "$field contains invalid character" }
    val stripped = when {
        raw == "/workspace" -> ""
        raw.startsWith("/workspace/") -> raw.removePrefix("/workspace")
        raw.startsWith("/") -> error("$field must be inside the workspace files area (/workspace/...): $raw")
        else -> raw
    }
    return stripped.trim('/')
}

/**
 * git 用：接受工作区相对路径或以 /workspace 开头的路径，归一为 /workspace 下的 rootfs 绝对路径。
 */
private fun String.toRootfsWorkspacePath(field: String): String {
    val raw = trim().replace('\\', '/')
    require(raw.isNotEmpty()) { "$field must not be empty" }
    require(!raw.contains('\u0000')) { "$field contains invalid character" }
    return when {
        raw == "/workspace" -> "/workspace"
        raw.startsWith("/workspace/") -> raw.trimEnd('/')
        raw.startsWith("/") -> error("$field must be inside the workspace files area (/workspace/...): $raw")
        else -> "/workspace/" + raw.trimStart('/').trimEnd('/')
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
