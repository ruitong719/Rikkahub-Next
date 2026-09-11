package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

/**
 * AGENTS.md 指令注入转换器（主体对齐上游 #134）。
 *
 * 指令来源（优先级从高到低）：
 * 1. 工作区 rootfs 内的 AGENTS.md：`/root/.agents/AGENTS.md`、`/workspace/AGENTS.md`、
 *    会话当前工作目录/AGENTS.md（与上游一致，仅 shell 就绪时读取）
 * 2. 设置里的全局 agent.md 文本（`settings.globalAgentMd`，rootfs 读不到时兜底）
 *
 * 追加到第一条 system 消息末尾；若没有 system 消息则插入一条（与 WorkspaceReminderTransformer 同模式）。
 * 注：subagent 复用主 Agent 的 systemPrompt 合成，但独立走 SubAgentRunner 的生成循环，
 * 不经过本转换器，因此该指令仅对主对话生效。
 */
class AgentMdTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val agentMd = resolveAgentMd(ctx) ?: return messages

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$agentMd")
            }
        } else {
            listOf(UIMessage.system(prompt = agentMd)) + messages
        }
    }

    /** rootfs 内 AGENTS.md 优先；读不到时回退设置里的全局文本 */
    private suspend fun resolveAgentMd(ctx: TransformerContext): String? {
        readWorkspaceAgentsMd(ctx)?.let { return it }
        return ctx.settings.globalAgentMd.trim().ifBlank { null }
    }

    private suspend fun readWorkspaceAgentsMd(ctx: TransformerContext): String? {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return null
        val workspace = workspaceRepository.getById(workspaceId) ?: return null
        // 与 WorkspaceReminderTransformer 保持一致: 仅在 shell 就绪时读取 rootfs 文件
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return null

        // ProotShellRunner 将 HOME 固定为 /root；相对 PWD 按 /workspace 解析。
        val workingDirectory = Paths.get("/workspace")
            .resolve(ctx.workspaceCwd?.takeIf { it.isNotBlank() } ?: ".")
            .normalize()
        val paths = linkedSetOf(
            "/root/.agents/AGENTS.md",
            "/workspace/AGENTS.md",
            workingDirectory.resolve("AGENTS.md").toString(),
        )
        val instructions = paths.mapNotNull { path ->
            try {
                val size = workspaceRepository.rootfsFileSize(workspaceId, path)
                require(size <= MAX_AGENTS_BYTES) { "AGENTS.md exceeds $MAX_AGENTS_BYTES bytes" }
                val content = ByteArrayOutputStream().use { output ->
                    workspaceRepository.exportRootfsFile(workspaceId, path, output)
                    output.toString(Charsets.UTF_8.name())
                }
                content.takeIf { it.isNotBlank() }?.let { path to it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Skipping AGENTS.md: $path", e)
                null
            }
        }
        if (instructions.isEmpty()) return null
        return buildString {
            appendLine("<workspace_instructions>")
            appendLine("Follow the AGENTS.md instructions below.")
            instructions.forEach { (path, content) ->
                appendLine()
                appendLine("AGENTS.md source: $path")
                appendLine(content)
            }
            append("</workspace_instructions>")
        }
    }

    private companion object {
        const val TAG = "AgentMdTransformer"
        const val MAX_AGENTS_BYTES = 64L * 1024
    }
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
