package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
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

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "read" to false,
    "write" to false,
    "edit" to false,
    "bash" to true,
    "workspace_export_to_phone" to true,
    "workspace_bg_start" to true,
    "workspace_bg_status" to false,
    "workspace_bg_output" to false,
    "workspace_bg_kill" to true,
    "workspace_bg_list" to false,
    "workspace_create_backup" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    conversationId: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val workspace = workspaceRepository.getById(workspaceId)
    val approvalOverrides = workspace?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)
    val writableRoots = workspace?.writableRootsList() ?: DEFAULT_WRITABLE_ROOTS

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository, writableRoots),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository, writableRoots),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd, conversationId, writableRoots),
        createWorkspaceExportTool(workspaceId, ::needsApproval, workspaceRepository),
    ) + createWorkspaceBgTools(workspaceId, ::needsApproval, workspaceRepository, conversationId) +
        listOf(createWorkspaceBackupTool(workspaceId, ::needsApproval, workspaceRepository))
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
        Read a file or directory from the workspace Rootfs. Paths must be absolute inside Rootfs.
        Usage:
        - The path parameter must be an absolute path inside Rootfs; use /workspace for the workspace files area.
        - By default, up to $DEFAULT_READ_LIMIT_LINES lines are returned from the start of the file.
        - Use offset (1-indexed line number) with limit to page through large files.
        - Output lines are prefixed with their line number like `12: content`; never include that prefix in old_text when editing.
        - Reading a directory lists its entries instead (subdirectories end with `/`).
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
        if (b == ZERO_BYTE) return true
        if (b < 9.toByte() || (b in 14..31)) nonPrintable++
    }
    return nonPrintable.toDouble() / sample.size > BINARY_NON_PRINTABLE_RATIO
}

private const val BINARY_NON_PRINTABLE_RATIO = 0.3
private val ZERO_BYTE: Byte = 0

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
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    writableRoots: List<String>,
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
    needsApproval = { needsApproval("write") || it.forcedPathApproval("path", writableRoots) },
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
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    writableRoots: List<String>,
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
    needsApproval = { needsApproval("edit") || it.forcedPathApproval("path", writableRoots) },
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

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    conversationId: String? = null,
    writableRoots: List<String>,
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
    needsApproval = { needsApproval("bash") || it.commandTouchesOutsideRoots(writableRoots) },
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

// 写入安全区（按工作区配置，见 WorkspaceEntity.writableRoots）：
// 区外的 write/edit/bash 调用强制审批（即使工具级审批已被用户关闭）
private fun kotlinx.serialization.json.JsonElement.forcedPathApproval(
    name: String,
    writableRoots: List<String>,
): Boolean = runCatching {
    !BashPathScanner.isInsideRoots(jsonObject.absolutePath(name), writableRoots)
}.getOrDefault(true)

private fun kotlinx.serialization.json.JsonElement.commandTouchesOutsideRoots(
    writableRoots: List<String>,
): Boolean = runCatching {
    BashPathScanner.touchesOutsideRoots(jsonObject.string("command").orEmpty(), writableRoots)
}.getOrDefault(true)

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
