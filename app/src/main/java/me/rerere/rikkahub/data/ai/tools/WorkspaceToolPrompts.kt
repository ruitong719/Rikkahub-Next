package me.rerere.rikkahub.data.ai.tools

/**
 * workspace 系列工具的注入提示词默认值（进入模型 system 上下文）。
 * 用户可在 工作区详情 -> 工具审批 中对每个工具覆盖（WorkspaceEntity.toolPrompts），
 * 覆盖优先；未覆盖的工具沿用这里的默认值。
 * 注意：这里的文本是 `<workspace>` 提示词块中 "Available tools" 列表的内容，
 * 与各工具的 Tool.description（函数定义，模型经 function calling schema 看到）相互独立。
 */
val WORKSPACE_TOOL_NAMES = listOf(
    "workspace_read_file",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
    "workspace_export_to_phone",
    "workspace_mount_list",
    "workspace_mount_sync",
    "workspace_bg_start",
    "workspace_bg_status",
    "workspace_bg_output",
    "workspace_bg_kill",
    "workspace_bg_list",
    "workspace_create_backup",
)

val DEFAULT_WORKSPACE_TOOL_PROMPTS: Map<String, String> = mapOf(
    "workspace_read_file" to
        "Read a file from the workspace files area (absolute paths inside Rootfs, e.g. /workspace/notes.md).",
    "workspace_write_file" to
        "Write a UTF-8 text file into the workspace files area.",
    "workspace_edit_file" to
        "Make precise edits to an existing file (old_text/new_text; whitespace-tolerant matching).",
    "workspace_shell" to
        "Run a shell command in the workspace Rootfs (the files area is mounted at /workspace).",
    "workspace_export_to_phone" to
        "Export a file or folder from the workspace to the phone directory the user configured.",
    "workspace_mount_list" to
        "List phone directories mounted into the workspace at /mnt/<name>.",
    "workspace_mount_sync" to
        "Manually sync a mounted phone directory (pull from phone or push workspace changes to phone).",
    "workspace_bg_start" to
        "Start a long-running command as a persistent background task in the workspace.",
    "workspace_bg_status" to
        "Query the status of a background task (running/done/failed, exit code, pid).",
    "workspace_bg_output" to
        "Read the output of a background task (supports tail).",
    "workspace_bg_kill" to
        "Terminate a running background task.",
    "workspace_bg_list" to
        "List all background tasks of the current workspace with their status.",
    "workspace_create_backup" to
        "Create a full app backup (settings, database and files) as /workspace/backup.zip.",
)
