package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.DEFAULT_WORKSPACE_TOOL_PROMPTS
import me.rerere.rikkahub.data.ai.tools.WORKSPACE_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.WorkspacePromptSegment
import me.rerere.rikkahub.data.ai.tools.resolveWorkspacePromptSegment
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.WorkspaceMountManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境、/mnt 挂载点与 workspace 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
    private val mountManager: WorkspaceMountManager? = null,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatService.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        val prompt = buildWorkspacePrompt(workspace, ctx.workspaceCwd, mountManager)

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
    mountManager: WorkspaceMountManager? = null,
): String {
    val overrides = workspace.promptSegmentOverrides()
    fun seg(key: String) = resolveWorkspacePromptSegment(key, overrides, workspace.name)
    return buildString {
        appendLine("<workspace>")
        appendLine(seg(WorkspacePromptSegment.IDENTITY))
        appendLine(seg(WorkspacePromptSegment.FILES_AREA))
        appendLine("- Available tools:")
        // 动态生成：用户覆盖优先，未覆盖的用默认表；保证列表与工具集同步（不再硬编码 4 个）
        val prompts = workspace.toolPromptOverrides() + DEFAULT_WORKSPACE_TOOL_PROMPTS
        WORKSPACE_TOOL_NAMES.forEach { name ->
            prompts[name]?.let { prompt ->
                appendLine("  - `$name`: $prompt")
            }
        }
        appendLine(seg(WorkspacePromptSegment.USAGE_HINT))
        appendLine(seg(WorkspacePromptSegment.SKILLS))
        appendLine(seg(WorkspacePromptSegment.UPLOAD))
        appendLine(seg(WorkspacePromptSegment.AGENT))
        if (!cwd.isNullOrBlank()) {
            appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
        }
        appendMountSection(mountManager, overrides, workspace.name)
        append("</workspace>")
    }
}

/**
 * /mnt 挂载点说明（可覆盖分段）：手机存储根目录直连挂载到 /mnt/storage，
 * 仅在全部文件访问权限已授予（挂载实际生效）时拼接，让模型了解实时读写语义。
 */
private fun StringBuilder.appendMountSection(mountManager: WorkspaceMountManager?, overrides: Map<String, String>, workspaceName: String) {
    if (mountManager?.isStorageAccessGranted() != true) return
    appendLine(resolveWorkspacePromptSegment(WorkspacePromptSegment.MOUNT, overrides, workspaceName))
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
