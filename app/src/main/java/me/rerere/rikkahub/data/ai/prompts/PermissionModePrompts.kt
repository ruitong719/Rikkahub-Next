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
- Mutating tools are disabled: you cannot modify files, run shell commands (`bash` is fully disabled), dispatch subagents, or export/backup data.
- Research with read-only tools (`read`, search, conversation tools) only.
- When you have enough context, present a concise implementation plan (steps, files to change, risks). Do NOT attempt workarounds to bypass plan mode.
- The user will switch back to build mode when they want the plan executed.
This supersedes any other instructions you have received.
</system-reminder>"""

internal val DEFAULT_BUILD_MODE_PROMPT = """<system-reminder>
# Permission Mode: BUILD
You are in BUILD mode: you can read, modify files and run shell commands.
Tools follow their approval settings; when an operation needs user approval, wait for its result before continuing.
</system-reminder>"""

internal val DEFAULT_YOLO_MODE_PROMPT = """<system-reminder>
# Permission Mode: YOLO
YOLO mode is active: all tool approvals are skipped. Execute directly without waiting for confirmation, but state briefly what you are about to do before destructive operations.
</system-reminder>"""
