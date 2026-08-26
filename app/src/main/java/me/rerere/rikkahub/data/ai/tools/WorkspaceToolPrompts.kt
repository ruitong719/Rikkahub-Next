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
    "bgt_start",
    "bgt",
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
    "bgt_start" to
        "Start a long-running command as a persistent background task in the workspace (returns a bg_id immediately).",
    "bgt" to
        "Query or manage background tasks: action=status/output/kill/list. " +
        "status and output need bg_id; list takes no arguments.",
    "workspace_create_backup" to
        "Create a full app backup (settings, database and files) as /workspace/backup.zip.",
)
