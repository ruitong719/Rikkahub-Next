package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
 * - bgt_start: 启动长任务，立即返回任务 id，不阻塞
 * - bgt: 查询/管理入口，action=status|output|kill|list
 *   （status 查状态、output 读输出支持 tail、kill 终止、list 列出全部）
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
            name = "bgt_start",
            description = "Start a long-running command as a persistent background task in the workspace. " +
                "Returns immediately with a bg_id; the command keeps running in a persistent proot session. " +
                "Use bgt(action=\"status\"/\"output\") to check progress, bgt(action=\"kill\") to stop it. " +
                "The task is bound to this conversation: when it finishes you will be notified automatically. " +
                "Max ${WorkspaceBgManager.MAX_CONCURRENT_TASKS} concurrent tasks per workspace. " +
                "Only suitable for non-interactive commands (no TTY): without a TTY many programs " +
                "(python, node, grep pipelines...) block-buffer stdout, so mid-run reads may lag or be empty; " +
                "use unbuffered modes like `stdbuf -oL` or `python -u` when you need incremental logs. " +
                "Optional output_file: absolute path inside the workspace where the task stdout/stderr " +
                "should be written (defaults to .l2s.bg/<taskId>/stdout.log). When set, bgt(action=\"status\") " +
                "returns the last 5 lines of that file as outputTail.",
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
                        put("output_file", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional absolute path to write the task's stdout/stderr (e.g. /workspace/build.log). " +
                                "Defaults to the task's internal stdout.log under .l2s.bg/<taskId>/.")
                        })
                    },
                    required = listOf("command"),
                )
            },
            needsApproval = { needsApproval("bgt_start") },
            execute = {
                val params = it.jsonObject
                val command = params.string("command") ?: error("command is required")
                val cwd = params.string("cwd")
                val outputFile = params.string("output_file")
                val bgManager = getKoin().get<WorkspaceBgManager>()
                val taskId = bgManager.startTask(
                    workspaceRoot = rootOf(),
                    command = command,
                    cwd = cwd,
                    conversationId = conversationId,
                    outputFile = outputFile,
                )
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("bg_id", taskId)
                            put(
                                "message",
                                "Background task started. It runs detached and you will be notified automatically " +
                                    "when it finishes. DO NOT sleep, poll with bgt(action=\"status\"), or idle-wait for it - " +
                                    "work on unrelated tasks or end your response; read output via bgt(action=\"output\") when notified."
                            )
                        }.toString()
                    )
                )
            },
        ),
        Tool(
            name = "bgt",
            description = "Query or manage background tasks started with bgt_start.\n" +
                "Actions:\n" +
                "- status: running/done/failed, exit code, pid, elapsed time (requires bg_id); if the task was started with output_file, also returns the last 5 lines of it as outputTail\n" +
                "- output: read task output, supports tail_lines / max_bytes (requires bg_id); " +
                        "result carries the task status so you can tell finished from still-running\n" +
                "- kill: terminate a running task with SIGTERM (requires bg_id)\n" +
                "- list: list all tasks of the current workspace (no bg_id needed)",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("description", "One of: status, output, kill, list")
                        })
                        put("bg_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Task id returned by bgt_start. Required by status/output/kill; omit for list.")
                        })
                        put("tail_lines", buildJsonObject {
                            put("type", "integer")
                            put("description", "action=output only: return only the last N lines. Defaults to all (capped at 2MB).")
                        })
                        put("max_bytes", buildJsonObject {
                            put("type", "integer")
                            put("description", "action=output only: maximum bytes to read from the end of the output. Defaults to 2MB.")
                        })
                        put("output_file", buildJsonObject {
                            put("type", "string")
                            put("description", "action=status only: absolute path whose last 5 lines should be returned as outputTail. " +
                                "Defaults to the output_file the task was started with.")
                        })
                    },
                    required = listOf("action"),
                )
            },
            needsApproval = { needsApproval("bgt") },
            execute = {
                val params = it.jsonObject
                val bgManager = getKoin().get<WorkspaceBgManager>()
                when (params.string("action")) {
                    "status" -> {
                        val bgId = params.string("bg_id") ?: error("bg_id is required for action=status")
                        val info = bgManager.taskInfo(rootOf(), bgId)
                        val outputFile = params.string("output_file") ?: info.outputFile
                        val outputTail = bgManager.outputFileTail(outputFile, lines = 5)
                        buildJsonObject {
                            put("bg_id", info.taskId)
                            put("status", info.status.name.lowercase())
                            put("exitCode", info.exitCode ?: -1)
                            put("pid", info.pid ?: 0L)
                            put("durationSeconds", (System.currentTimeMillis() - info.startedAt) / 1000)
                            put("command", info.command)
                            put("stdoutBytes", info.stdoutSizeBytes)
                            put("outputFile", outputFile ?: "")
                            outputTail?.let { put("outputTail", it) }
                        }
                    }

                    "output" -> {
                        val bgId = params.string("bg_id") ?: error("bg_id is required for action=output")
                        val tailLines = params.string("tail_lines")?.toIntOrNull()
                        val maxBytes = params.string("max_bytes")?.toIntOrNull()
                        val text = bgManager.output(rootOf(), bgId, tailLines, maxBytes)
                        // 非 TTY 下子程序块缓冲会让中途读取滞后甚至为空，
                        // 附带状态让模型能区分「还在跑、稍后再读」与「已结束、这就是全部输出」
                        val taskStatus = runCatching { bgManager.taskInfo(rootOf(), bgId) }
                            .getOrNull()?.status?.name?.lowercase() ?: "unknown"
                        buildJsonObject {
                            put("bg_id", bgId)
                            put("status", taskStatus)
                            put("output", text)
                            put("truncated", text.length >= (maxBytes ?: WorkspaceBgManager.MAX_OUTPUT_READ_BYTES))
                        }
                    }

                    "kill" -> {
                        val bgId = params.string("bg_id") ?: error("bg_id is required for action=kill")
                        bgManager.killTask(rootOf(), bgId)
                        buildJsonObject {
                            put("bg_id", bgId)
                            put("message", "Kill signal sent")
                        }
                    }

                    "list" -> {
                        val tasks = bgManager.listTasks(rootOf())
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
                        }
                    }

                    else -> error("action must be one of: status, output, kill, list")
                }.let { json ->
                    listOf(UIMessagePart.Text(json.toString()))
                }
            },
        ),
    )
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull
