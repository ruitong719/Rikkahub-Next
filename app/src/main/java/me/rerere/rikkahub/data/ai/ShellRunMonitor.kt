package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * GenerationHandler 执行工具时注入的协程上下文元素, 把 toolCallId 带给工具实现侧。
 *
 * 目前只有 bash 直播使用; 未来其他需要"知道自己是哪次调用"的工具可复用。
 * 工具侧用 coroutineContext[ShellRunKey]?.id 读取, 取不到时自行回退(如 conversationId)。
 */
class ShellRunKey(
    val id: String,
) : AbstractCoroutineContextElement(ShellRunKey) {
    companion object Key : CoroutineContext.Key<ShellRunKey>

    override fun toString(): String = "ShellRunKey($id)"
}

/** 一次正在(或刚刚结束)执行的 bash 直播状态, 内存态, 进程内可见 */
data class ShellRunState(
    val toolCallId: String,
    val command: String,
    val cwd: String?,
    val startedAt: Long,
    val running: Boolean = true,
    // 尾部窗口内的输出, 只保留最近一段供 UI 展示
    val stdoutTail: String = "",
    val stderrTail: String = "",
)

/**
 * bash 执行直播注册表(Koin single, 进程内共享)。
 *
 * 数据流: ProotShellRunner 输出收集线程 --chunk--> [append] --> ShellToolUI 在工具
 * loading 期间按 toolCallId 订阅 [runs], 实时显示尾部输出(弹窗滚动 + 折叠态单行)。
 *
 * 这是纯展示旁路: 不改变最终 tool output(完整结果仍走消息管线), 不持久化,
 * App 被杀后直播丢失(与 SubAgentRunMonitor 同一决策)。
 *
 * 线程模型: append 在收集线程上调用, StateFlow.update 原子且 conflated,
 * 高频输出时中间态自动合并(UI 每帧最多消费一个值), 无需额外节流。
 */
class ShellRunMonitor {
    private val _runs = MutableStateFlow<Map<String, ShellRunState>>(emptyMap())
    val runs: StateFlow<Map<String, ShellRunState>> = _runs.asStateFlow()

    fun start(toolCallId: String, command: String, cwd: String?) {
        _runs.update { current ->
            val next = current + (toolCallId to ShellRunState(
                toolCallId = toolCallId,
                command = command,
                cwd = cwd,
                startedAt = System.currentTimeMillis(),
            ))
            if (next.size <= MAX_ENTRIES) {
                next
            } else {
                // 淘汰最旧的已结束条目, 防止长会话中无限累积; 全部运行中则放行
                val evict = next.values.asSequence()
                    .filterNot { it.running }
                    .sortedBy { it.startedAt }
                    .take(next.size - MAX_ENTRIES)
                    .mapTo(mutableSetOf()) { it.toolCallId }
                if (evict.isEmpty()) next else next - evict
            }
        }
    }

    fun append(toolCallId: String, isStderr: Boolean, chunk: String) {
        if (chunk.isEmpty()) return
        _runs.update { current ->
            val run = current[toolCallId] ?: return@update current
            current + (toolCallId to if (isStderr) {
                run.copy(stderrTail = appendTail(run.stderrTail, chunk))
            } else {
                run.copy(stdoutTail = appendTail(run.stdoutTail, chunk))
            })
        }
    }

    /** 标记结束但保留 tail: 消息发射有延迟, UI 在间隙内仍可显示最后内容 */
    fun finish(toolCallId: String) {
        _runs.update { current ->
            val run = current[toolCallId] ?: return@update current
            current + (toolCallId to run.copy(running = false))
        }
    }

    companion object {
        // 尾部窗口上限(chars), 防 cat 大文件类命令刷爆内存
        private const val TAIL_MAX_CHARS = 16_000
        private const val MAX_ENTRIES = 16

        /**
         * 追加并维持尾部窗口; 截断时丢弃头部到最近一个换行为止, 避免 UI 出现半行
         */
        private fun appendTail(tail: String, chunk: String): String {
            val combined = tail + chunk
            if (combined.length <= TAIL_MAX_CHARS) return combined
            val newline = combined.indexOf('\n', combined.length - TAIL_MAX_CHARS)
            return if (newline == -1) "" else combined.substring(newline + 1)
        }
    }
}
