package me.rerere.rikkahub.data.ai.tools

/**
 * workspace 系列工具的注入提示词默认值（进入模型 system 上下文）。
 * 用户可在 工作区详情 -> 工具审批 中对每个工具覆盖（WorkspaceEntity.toolPrompts），
 * 覆盖优先；未覆盖的工具沿用这里的默认值。
 * 注意：这里的文本是 `<workspace>` 提示词块中 "Available tools" 列表的内容，
 * 与各工具的 Tool.description（函数定义，模型经 function calling schema 看到）相互独立。
 *
 * 命名对齐 opencode：read/write/edit/bash/create_backup 为裸名，后台任务系列为 bgt/bgt_start。
 */
val WORKSPACE_TOOL_NAMES = listOf(
    "read",
    "write",
    "edit",
    "bash",
    "glob",
    "grep",
    "git",
    "bgt_start",
    "bgt",
    "create_backup",
)

val DEFAULT_WORKSPACE_TOOL_PROMPTS: Map<String, String> = mapOf(
    "read" to
        "Read a file from the workspace files area (absolute paths inside Rootfs). " +
        "Returns line-numbered content with offset/limit paging; a directory path returns its entries. " +
        "Use glob/grep to find files or content instead of shelling out.",
    "write" to
        "Write a UTF-8 text file into the workspace files area. Prefer edit for existing files.",
    "edit" to
        "Make precise string replacements in an existing file (old_text/new_text; whitespace-tolerant fallbacks).",
    "bash" to
        "Run a shell command inside the workspace PRoot Linux environment (files area mounted at /workspace). " +
        "For terminal operations only; use read/write/edit for file contents and glob/grep for finding files or content.",
    "glob" to
        "Find files by glob pattern inside the workspace files area (read-only; e.g. \"**/*.kt\"). " +
        "Prefer this over bash find/ls. Does not search the Linux rootfs system directories.",
    "grep" to
        "Search file contents in the workspace files area by regex or literal string (read-only). " +
        "Supports include glob and case-insensitive matching; prefer this over bash grep/rg.",
    "git" to
        "Inspect the git repository inside the workspace with read-only commands " +
        "(status/diff/log/show/branch/ls-files/blame/rev-parse). Never commits or modifies files.",
    "bgt_start" to
        "Start a long-running command as a persistent background task in the workspace (returns a bg_id immediately).",
    "bgt" to
        "Query or manage background tasks: action=status/output/kill/list. " +
        "status and output need bg_id; list takes no arguments.",
    "create_backup" to
        "Create a full app backup (settings, database and files) as /workspace/backup.zip.",
)
