package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.BgTaskStatus
import me.rerere.rikkahub.data.files.WorkspaceBgManager
import me.rerere.rikkahub.data.files.WorkspaceBgTaskInfo
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * 后台任务完成提醒注入转换器（参考 TimeReminderTransformer 的注入模式）。
 *
 * 在每次生成前扫描**本对话绑定**的后台任务：status 为 done/failed 且尚未提醒过的，
 * 注入一条 SYSTEM 角色 `<bg_reminder>` 消息，并标记已提醒（不重复注入）。
 *
 * 注入时机是"下次生成前"：任务在对话间隙完成时，用户发下一条消息即可收到提醒。
 */
class BackgroundTaskReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
    private val bgManager: WorkspaceBgManager,
    private val conversationId: String?,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        val finished = bgManager.listTasks(workspace.root)
            .filter { it.conversationId == conversationId }
            .filter { it.status == BgTaskStatus.DONE || it.status == BgTaskStatus.FAILED }
            .filter { !it.notified }
        if (finished.isEmpty()) return messages

        // 标记已提醒 + 截断过大输出（此时任务已完成，输出不会再增长）
        finished.forEach { info ->
            bgManager.markNotified(workspace.root, info.taskId)
            bgManager.truncateOutputIfLarge(workspace.root, info.taskId)
        }

        val reminderBody = finished.joinToString("\n") { buildReminder(it) }
        val reminderMessage = UIMessage.system("<bg_reminder>\n$reminderBody\n</bg_reminder>")
        return messages + reminderMessage
    }

    private fun buildReminder(info: WorkspaceBgTaskInfo): String {
        val status = if (info.status == BgTaskStatus.DONE) "completed" else "failed"
        val durationSeconds = (System.currentTimeMillis() - info.startedAt) / 1000
        return buildString {
            appendLine("- Background task ${info.taskId.take(8)} $status: exit code ${info.exitCode ?: -1}, " +
                "took ${durationSeconds}s")
            if (info.command.isNotBlank()) {
                appendLine("  command: ${info.command.take(200)}")
            }
            append("  read output with workspace_bg_output(bg_id=\"${info.taskId}\")")
        }
    }
}
