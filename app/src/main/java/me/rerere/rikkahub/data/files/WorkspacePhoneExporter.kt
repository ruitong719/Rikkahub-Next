package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceManager
import java.io.File
import java.nio.file.Files

/**
 * 把工作区 rootfs 中的文件/文件夹导出到手机 SAF 目录（用户授权的导出根目录）。
 *
 * 安全约束：
 * - 只能写到用户通过 [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree]
 *   授权的树目录内（URI 持久化在 WorkspaceEntity.exportTargetUri）
 * - 目标相对路径拒绝绝对路径、`.`、`..`（防穿越）
 * - 跳过 `.l2s.` 隐藏文件与符号链接（与 WorkspaceFileSystem 行为一致）
 * - 单次导出总量上限 [MAX_TOTAL_EXPORT_BYTES]，逐文件流式拷贝不整载入内存
 */
class WorkspacePhoneExporter(
    private val context: Context,
    private val workspaceManager: WorkspaceManager,
) {
    data class ExportResult(
        val sourcePath: String,
        val targetDir: String,
        val filesExported: Int = 0,
        val dirsCreated: Int = 0,
        val totalBytes: Long = 0L,
        val overwritten: Int = 0,
        val skippedExisting: Int = 0,
        val skippedOther: Int = 0,
        val errors: List<String> = emptyList(),
    )

    /**
     * @param workspaceRoot 工作区 root（WorkspaceEntity.root）
     * @param sourcePath rootfs 内绝对路径（/workspace/xxx、/root/xxx、/skills/xxx 均可）
     * @param treeUri 用户授权的 SAF 树目录 URI
     * @param targetDir 导出根目录下的相对子路径，空表示根目录
     * @param overwrite 目标已存在时是否覆盖（false = 跳过）
     */
    suspend fun export(
        workspaceRoot: String,
        sourcePath: String,
        treeUri: Uri,
        targetDir: String = "",
        overwrite: Boolean = false,
    ): ExportResult = withContext(Dispatchers.IO) {
        val source = resolveSource(workspaceRoot, sourcePath)

        val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: error(
                "Phone export directory is not accessible. " +
                    "The permission may have been revoked in system settings - " +
                    "please re-select the export directory in workspace settings."
            )

        val segments = sanitizeTargetDir(targetDir)
        val baseDoc = resolveTargetDir(treeDoc, segments)

        val state = ExportState()
        if (source.isDirectory) {
            exportDirectory(source, baseDoc, overwrite, state)
        } else {
            exportFile(source, baseDoc, overwrite, state)
        }
        state.toResult(sourcePath, targetDir)
    }

    private fun resolveSource(workspaceRoot: String, sourcePath: String): File {
        val location = workspaceManager.resolveRootfsPath(workspaceRoot, sourcePath)
        val rootCanonical = location.rootDir.canonicalFile
        val source = File(location.rootDir, location.relativePath).canonicalFile
        require(
            source.path == rootCanonical.path || source.path.startsWith(rootCanonical.path + File.separator)
        ) { "Source path escapes workspace root: $sourcePath" }
        require(source.exists()) { "Source does not exist: $sourcePath" }
        return source
    }

    private fun resolveTargetDir(treeDoc: DocumentFile, segments: List<String>): DocumentFile {
        var doc = treeDoc
        for (segment in segments) {
            doc = doc.findFile(segment)?.takeIf { it.isDirectory }
                ?: doc.createDirectory(segment)
                ?: error("Failed to create target directory: $segment")
        }
        return doc
    }

    private suspend fun exportDirectory(
        source: File,
        targetDoc: DocumentFile,
        overwrite: Boolean,
        state: ExportState,
    ) {
        source.listFiles().orEmpty().forEach { child ->
            ensureActive()
            if (child.name.startsWith(".l2s.")) return@forEach
            if (Files.isSymbolicLink(child.toPath())) {
                state.skippedOther++
                return@forEach
            }
            runCatching {
                if (child.isDirectory) {
                    val existingDir = targetDoc.findFile(child.name)?.takeIf { it.isDirectory }
                    val dirDoc = existingDir ?: targetDoc.createDirectory(child.name)
                        ?: error("Failed to create directory: ${child.name}")
                    if (existingDir == null) state.dirsCreated++
                    exportDirectory(child, dirDoc, overwrite, state)
                } else {
                    exportFile(child, targetDoc, overwrite, state)
                }
            }.onFailure { e ->
                state.errors += "${child.relativePath()}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private suspend fun exportFile(
        source: File,
        targetDoc: DocumentFile,
        overwrite: Boolean,
        state: ExportState,
    ) {
        if (source.length() > MAX_SINGLE_FILE_BYTES) {
            state.errors += "${source.name}: exceeds ${MAX_SINGLE_FILE_BYTES / 1024 / 1024}MB single file limit"
            return
        }
        val existing = targetDoc.findFile(source.name)
        if (existing != null && !overwrite) {
            state.skippedExisting++
            return
        }
        val fileDoc = existing?.takeIf { it.isFile }
            ?: targetDoc.createFile(mimeTypeFor(source.name), source.name)
            ?: error("Failed to create file: ${source.name}")

        source.inputStream().use { input ->
            val output = context.contentResolver.openOutputStream(fileDoc.uri)
            if (output != null) {
                output.use { out ->
                    val written = input.copyTo(out, bufferSize = COPY_BUFFER_BYTES)
                    state.totalBytes += written
                }
            } else {
                // 部分 DocumentsProvider 不支持截断打开，兜底删旧建新
                require(existing != null) { "Failed to open output stream for ${source.name}" }
                fileDoc.delete()
                val recreated = targetDoc.createFile(mimeTypeFor(source.name), source.name)
                    ?: error("Failed to recreate file: ${source.name}")
                context.contentResolver.openOutputStream(recreated.uri)?.use { out ->
                    val written = source.inputStream().use { it.copyTo(out, bufferSize = COPY_BUFFER_BYTES) }
                    state.totalBytes += written
                } ?: error("Failed to open output stream for ${source.name}")
            }
        }

        if (state.totalBytes > MAX_TOTAL_EXPORT_BYTES) {
            error(
                "Export exceeds ${MAX_TOTAL_EXPORT_BYTES / 1024 / 1024}MB total limit; " +
                    "export smaller directories or files."
            )
        }
        state.filesExported++
        if (existing != null) state.overwritten++
    }

    private suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
    }

    private fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun File.relativePath(): String = path.substringAfterLast('/').substringAfterLast('\\')

    private class ExportState {
        var filesExported: Int = 0
        var dirsCreated: Int = 0
        var totalBytes: Long = 0L
        var overwritten: Int = 0
        var skippedExisting: Int = 0
        var skippedOther: Int = 0
        val errors = mutableListOf<String>()

        fun toResult(sourcePath: String, targetDir: String) = ExportResult(
            sourcePath = sourcePath,
            targetDir = targetDir,
            filesExported = filesExported,
            dirsCreated = dirsCreated,
            totalBytes = totalBytes,
            overwritten = overwritten,
            skippedExisting = skippedExisting,
            skippedOther = skippedOther,
            errors = errors,
        )
    }

    companion object {
        private const val MAX_TOTAL_EXPORT_BYTES = 1L * 1024 * 1024 * 1024 // 1GB
        private const val MAX_SINGLE_FILE_BYTES = 512L * 1024 * 1024 // 512MB
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * 清洗导出目标相对路径：返回逐段目录名。
 * 拒绝绝对路径、`.`、`..` 与空段。
 */
internal fun sanitizeTargetDir(raw: String): List<String> {
    val normalized = raw.replace('\\', '/').trim().trim('/')
    if (normalized.isBlank()) return emptyList()
    val segments = normalized.split('/').filter { it.isNotBlank() }
    require(segments.none { it == "." || it == ".." }) {
        "target_dir must not contain '.' or '..'"
    }
    return segments
}
