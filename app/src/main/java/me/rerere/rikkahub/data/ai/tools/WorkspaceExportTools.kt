package me.rerere.rikkahub.data.ai.tools

import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.WorkspacePhoneExporter
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.java.KoinJavaComponent.getKoin

/**
 * workspace_export_to_phone: 把工作区 rootfs 中的文件/文件夹导出到用户指定的手机目录。
 *
 * 目标目录由用户在工作区设置中通过系统文件夹选择器（SAF）指定，URI 持久化在
 * WorkspaceEntity.exportTargetUri。工具只能写到该授权目录内（target_dir 为相对子路径）。
 */
fun createWorkspaceExportTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
): Tool = Tool(
    name = "workspace_export_to_phone",
    description = buildString {
        append("Export a file or folder from the workspace Rootfs to the phone directory the user configured. ")
        append("source must be an absolute path inside Rootfs (e.g. /workspace/notes.md, /root/data, /skills/xxx). ")
        append("target_dir is an optional relative subdirectory under the configured phone export root. ")
        append("Existing files are skipped unless overwrite=true. ")
        append("If no export directory is configured, ask the user to pick one in the workspace settings first.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Absolute path inside Rootfs of the file or folder to export. Use /workspace for the workspace files area."
                    )
                })
                put("target_dir", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Relative subdirectory under the configured phone export root. Defaults to the root."
                    )
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite existing files in the target. Defaults to false (skip).")
                })
            },
            required = listOf("source"),
        )
    },
    needsApproval = { needsApproval("workspace_export_to_phone") },
    execute = {
        val params = it.jsonObject
        val source = params.exportSourcePath()
        val targetDir = params.string("target_dir").orEmpty()
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")
        val treeUri = workspace.exportTargetUri?.takeIf { it.isNotBlank() }
            ?: error(
                "No phone export directory configured. " +
                    "The user must open workspace settings and pick an export directory first."
            )

        val exporter = getKoin().get<WorkspacePhoneExporter>()
        val result = try {
            exporter.export(
                workspaceRoot = workspace.root,
                sourcePath = source,
                treeUri = Uri.parse(treeUri),
                targetDir = targetDir,
                overwrite = overwrite,
            )
        } catch (e: SecurityException) {
            error(
                "Phone export directory permission was revoked. " +
                    "Please re-select the export directory in workspace settings."
            )
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("source", result.sourcePath)
                    put("targetDir", result.targetDir)
                    put("filesExported", result.filesExported)
                    put("dirsCreated", result.dirsCreated)
                    put("totalBytes", result.totalBytes)
                    put("overwritten", result.overwritten)
                    put("skippedExisting", result.skippedExisting)
                    put("skippedOther", result.skippedOther)
                    put("errors", result.errors.joinToString("; "))
                }.toString()
            )
        )
    },
)

private fun JsonObject.exportSourcePath(): String {
    val path = this["source"]?.jsonPrimitive?.contentOrNull?.replace('\\', '/')?.trim()
        ?: error("source is required")
    require(path.startsWith("/")) { "source must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "source contains invalid character" }
    return path
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull
