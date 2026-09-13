package me.rerere.rikkahub.data.ai.prompts

/**
 * 权限模式（plan/build/yolo）的内置默认提示词，对齐 opencode 的 plan-mode/build-switch 措辞。
 *
 * 注入方式见 PermissionModePromptTransformer：每轮生成包 <system-reminder> 标签追加到
 * 最后一条用户消息之后。用户可在 设置-模型与服务-提示词 中自定义，字段值即生效内容。
 */
internal val DEFAULT_PLAN_MODE_PROMPT = """<system-reminder>
# Permission Mode: PLAN
You are currently in PLAN mode (read-only research):
- Mutating tools are disabled: you cannot modify files, run shell commands (`bash` is disabled), dispatch subagents, or export/backup data.
- Research with read-only tools only: `read` (files and directories), `glob` (find files by pattern), `grep` (search file contents), `git` (read-only status/diff/log/branch), plus search and conversation tools.
- When you have enough context, present a concise implementation plan (steps, files to change, risks). Do NOT attempt workarounds to bypass plan mode.
- The user will switch back to build mode when they want the plan executed.
This supersedes any other instructions you have received.
</system-reminder>"""

internal val DEFAULT_BUILD_MODE_PROMPT = """<system-reminder>
# Permission Mode: BUILD
You are in BUILD mode: you can read, modify files and run shell commands.
Tools follow their approval settings; when an operation needs user approval, wait for its result before continuing.
</system-reminder>"""

internal val DEFAULT_GOAL_MODE_PROMPT = """<system-reminder>
# Permission Mode: GOAL
You are working toward a goal the user gave with /goal. The goal text is in the conversation; treat it as a draft.

Working contract:
- FIRST, call `set_goal` exactly once with a concrete completion condition describing what the finished result must be. Until you do, all mutating tools (write/edit/bash/background tasks/backup) are rejected. Keep it short; if the user's request is vague, use your best interpretation.
- Then create a todo list with todowrite and execute it. You have full read/write/shell access and all tool approvals are skipped; work autonomously and do not ask for confirmation unless truly blocked.
- When you stop, a separate evaluator model reviews the conversation and records a verdict. If the goal is not met yet, you will receive a system-reminder; call `get_goal` to read the reason, then continue.
- Do NOT dispatch or simulate the evaluator yourself.
This supersedes any other instructions you have received.
</system-reminder>"""

internal val DEFAULT_YOLO_MODE_PROMPT = """<system-reminder>
# Permission Mode: YOLO
YOLO mode is active: all tool approvals are skipped. Execute directly without waiting for confirmation, but state briefly what you are about to do before destructive operations.
</system-reminder>"""
