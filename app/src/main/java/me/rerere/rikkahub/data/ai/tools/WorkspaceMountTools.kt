package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SyncDirection
import me.rerere.rikkahub.data.files.WorkspaceMountManager
import org.koin.java.KoinJavaComponent.getKoin

/**
 * workspace_mount_list: 列出全局挂载点（/mnt/<name> -> 手机 SAF 目录）。
 * workspace_mount_sync: 手动同步指定挂载点（pull = 手机 -> 工作区；push = 工作区 -> 手机）。
 */
fun createWorkspaceMountTools(
    needsApproval: (String) -> Boolean,
): List<Tool> = listOf(
    Tool(
        name = "workspace_mount_list",
        description = "List the phone directories mounted into the workspace as /mnt/<name>. " +
            "Mounts are configured by the user in workspace settings.",
        parameters = { null },
        needsApproval = { needsApproval("workspace_mount_list") },
        execute = {
            val mountManager = getKoin().get<WorkspaceMountManager>()
            val mounts = mountManager.listMounts()
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("mounts", buildJsonObject {
                            mounts.forEach { m ->
                                put(m.name, buildJsonObject {
                                    put("id", m.id)
                                    put("lastSyncAt", m.lastSyncAt ?: 0L)
                                })
                            }
                        })
                        put("count", mounts.size)
                    }.toString()
                )
            )
        },
    ),
    Tool(
        name = "workspace_mount_sync",
        description = "Manually sync a mounted phone directory. " +
            "direction=pull copies from the phone directory into the workspace mount point " +
            "(NOTE: this overwrites workspace-side changes), " +
            "direction=push copies workspace changes back to the phone directory (never deletes phone files). " +
            "Pass the mount name or id.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("mount", buildJsonObject {
                        put("type", "string")
                        put("description", "Mount name (e.g. photos) or id of the mount point")
                    })
                    put("direction", buildJsonObject {
                        put("type", "string")
                        put("description", "Sync direction: pull (phone -> workspace) or push (workspace -> phone)")
                    })
                },
                required = listOf("mount", "direction"),
            )
        },
        needsApproval = { needsApproval("workspace_mount_sync") },
        execute = {
            val params = it.jsonObject
            val mountRef = params.string("mount") ?: error("mount is required")
            val direction = when (params.string("direction")?.lowercase()) {
                "pull" -> SyncDirection.PULL
                "push" -> SyncDirection.PUSH
                else -> error("direction must be 'pull' or 'push'")
            }

            val mountManager = getKoin().get<WorkspaceMountManager>()
            val config = mountManager.listMounts()
                .find { it.id == mountRef || it.name == mountRef }
                ?: error("Mount not found: $mountRef (use workspace_mount_list to see mounts)")

            val stats = mountManager.syncMount(config.id, direction)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("mount", config.name)
                        put("direction", direction.name.lowercase())
                        put("filesSynced", stats.filesSynced)
                        put("dirsCreated", stats.dirsCreated)
                        put("totalBytes", stats.totalBytes)
                        put("skipped", stats.skipped)
                        put("errors", stats.errors.joinToString("; "))
                    }.toString()
                )
            )
        },
    ),
)

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull
