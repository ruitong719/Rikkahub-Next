package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.WorkspaceBgManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.java.KoinJavaComponent.getKoin

/**
 * 持久化后台任务工具组：
 * - workspace_bg_start: 启动长任务，立即返回任务 id，不阻塞
 * - workspace_bg_status: 查询状态/耗时/退出码
 * - workspace_bg_output: 读取输出（支持 tail）
 * - workspace_bg_kill: 终止任务
 * - workspace_bg_list: 列出本工作区的任务
 *
 * 任务完成后，绑定对话的下一次生成会自动收到完成提醒（BackgroundTaskReminderTransformer）。
 */
fun createWorkspaceBgTools(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    conversationId: String? = null,
): List<Tool> {
    suspend fun rootOf(): String {
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")
        return workspace.root
    }

    return listOf(
        Tool(
            name = "workspace_bg_start",
            description = "Start a long-running command as a persistent background task in the workspace. " +
                "Returns immediately with a bg_id; the command keeps running in a persistent proot session. " +
                "Use workspace_bg_status / workspace_bg_output to check progress, workspace_bg_kill to stop it. " +
                "The task is bound to this conversation: when it finishes you will be notified automatically. " +
                "Max ${WorkspaceBgManager.MAX_CONCURRENT_TASKS} concurrent tasks per workspace. " +
                "Only suitable for non-interactive commands (no TTY).",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Shell command to run in the background")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "Working directory inside the workspace (absolute path). Defaults to /workspace.")
                        })
                    },
                    required = listOf("command"),
                )
            },
            needsApproval = { needsApproval("workspace_bg_start") },
            execute = {
                val params = it.jsonObject
                val command = params.string("command") ?: error("command is required")
                val cwd = params.string("cwd")
                val bgManager = getKoin().get<WorkspaceBgManager>()
                val taskId = bgManager.startTask(
                    workspaceRoot = rootOf(),
                    command = command,
                    cwd = cwd,
                    conversationId = conversationId,
                )
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("bg_id", taskId)
                            put("message", "Background task started")
                        }.toString()
                    )
                )
            },
        ),
        Tool(
            name = "workspace_bg_status",
            description = "Query the status of a background task: running/done/failed, exit code, pid, elapsed time.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("bg_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Task id returned by workspace_bg_start")
                        })
                    },
                    required = listOf("bg_id"),
                )
            },
            needsApproval = { needsApproval("workspace_bg_status") },
            execute = {
                val bgId = it.jsonObject.string("bg_id") ?: error("bg_id is required")
                val bgManager = getKoin().get<WorkspaceBgManager>()
                val info = bgManager.taskInfo(rootOf(), bgId)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("bg_id", info.taskId)
                            put("status", info.status.name.lowercase())
                            put("exitCode", info.exitCode ?: -1)
                            put("pid", info.pid ?: 0L)
                            put("durationSeconds", (System.currentTimeMillis() - info.startedAt) / 1000)
                            put("command", info.command)
                            put("stdoutBytes", info.stdoutSizeBytes)
                        }.toString()
                    )
                )
            },
        ),
        Tool(
            name = "workspace_bg_output",
            description = "Read the output of a background task. Use tail_lines to get only the last N lines.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("bg_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Task id returned by workspace_bg_start")
                        })
                        put("tail_lines", buildJsonObject {
                            put("type", "integer")
                            put("description", "Only return the last N lines. Defaults to all (capped at 2MB).")
                        })
                        put("max_bytes", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum bytes to read from the end of the output. Defaults to 2MB.")
                        })
                    },
                    required = listOf("bg_id"),
                )
            },
            needsApproval = { needsApproval("workspace_bg_output") },
            execute = {
                val params = it.jsonObject
                val bgId = params.string("bg_id") ?: error("bg_id is required")
                val tailLines = params.string("tail_lines")?.toIntOrNull()
                val maxBytes = params.string("max_bytes")?.toIntOrNull()
                val bgManager = getKoin().get<WorkspaceBgManager>()
                val text = bgManager.output(rootOf(), bgId, tailLines, maxBytes)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("bg_id", bgId)
                            put("output", text)
                            put("truncated", text.length >= (maxBytes ?: WorkspaceBgManager.MAX_OUTPUT_READ_BYTES))
                        }.toString()
                    )
                )
            },
        ),
        Tool(
            name = "workspace_bg_kill",
            description = "Terminate a running background task (sends SIGTERM to the task process).",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("bg_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Task id returned by workspace_bg_start")
                        })
                    },
                    required = listOf("bg_id"),
                )
            },
            needsApproval = { needsApproval("workspace_bg_kill") },
            execute = {
                val bgId = it.jsonObject.string("bg_id") ?: error("bg_id is required")
                val bgManager = getKoin().get<WorkspaceBgManager>()
                bgManager.killTask(rootOf(), bgId)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("bg_id", bgId)
                            put("message", "Kill signal sent")
                        }.toString()
                    )
                )
            },
        ),
        Tool(
            name = "workspace_bg_list",
            description = "List all background tasks of the current workspace with their status.",
            parameters = { null },
            needsApproval = { needsApproval("workspace_bg_list") },
            execute = {
                val bgManager = getKoin().get<WorkspaceBgManager>()
                val tasks = bgManager.listTasks(rootOf())
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("tasks", buildJsonObject {
                                tasks.forEach { info ->
                                    put(info.taskId, buildJsonObject {
                                        put("status", info.status.name.lowercase())
                                        put("exitCode", info.exitCode ?: -1)
                                        put("startedAt", info.startedAt)
                                        put("durationSeconds", (System.currentTimeMillis() - info.startedAt) / 1000)
                                        put("command", info.command.take(200))
                                    })
                                }
                            })
                            put("count", tasks.size)
                        }.toString()
                    )
                )
            },
        ),
    )
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull
