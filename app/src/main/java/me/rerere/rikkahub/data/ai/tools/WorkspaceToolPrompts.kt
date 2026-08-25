package me.rerere.rikkahub.data.ai.tools

/**
 * workspace 系列工具的注入提示词默认值（进入模型 system 上下文）。
 * 用户可在 工作区详情 -> 工具审批 中对每个工具覆盖（WorkspaceEntity.toolPrompts），
 * 覆盖优先；未覆盖的工具沿用这里的默认值。
 * 注意：这里的文本是 `<workspace>` 提示词块中 "Available tools" 列表的内容，
 * 与各工具的 Tool.description（函数定义，模型经 function calling schema 看到）相互独立。
 *
 * 命名对齐 opencode：read/write/edit/bash 为裸名，App 特有基础设施保留 workspace_ 前缀。
 */
val WORKSPACE_TOOL_NAMES = listOf(
    "read",
    "write",
    "edit",
    "bash",
    "workspace_export_to_phone",
    "workspace_bg_start",
    "workspace_bg_status",
    "workspace_bg_output",
    "workspace_bg_kill",
    "workspace_bg_list",
    "workspace_create_backup",
)

val DEFAULT_WORKSPACE_TOOL_PROMPTS: Map<String, String> = mapOf(
    "read" to
        "Read a file or directory from the workspace files area (absolute paths inside Rootfs). " +
        "Returns line-numbered content with offset/limit paging; directories are listed instead.",
    "write" to
        "Write a UTF-8 text file into the workspace files area. Prefer edit for existing files.",
    "edit" to
        "Make precise string replacements in an existing file (old_text/new_text; whitespace-tolerant fallbacks).",
    "bash" to
        "Run a shell command inside the workspace PRoot Linux environment (files area mounted at /workspace). " +
        "For terminal operations only; use read/write/edit for file contents.",
    "workspace_export_to_phone" to
        "Export a file or folder from the workspace to the phone directory the user configured (SAF).",
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
