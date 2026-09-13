package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** subagent 一次运行的总体状态 */
enum class SubAgentRunStatus {
    RUNNING,
    SUCCESS,
    ERROR,
    TIMEOUT,
    /** 被用户主动停止 */
    CANCELLED,
}

/** subagent 执行过程中的一个展示条目（按发生顺序） */
sealed interface SubAgentRunStep {
    /** 模型推理（thinking）过程 */
    data class Thinking(val text: String) : SubAgentRunStep

    /** 工具调用之间的中间叙述输出 */
    data class IntermediateText(val text: String) : SubAgentRunStep

    /**
     * 一次工具调用：仅展示是否调用成功，不展示输出内容。
     * [executed] 表示已执行（有输出）；[succeeded] 表示执行未抛异常。
     */
    data class ToolCall(
        val toolName: String,
        val input: String,
        val executed: Boolean,
        val succeeded: Boolean,
    ) : SubAgentRunStep
}

/**
 * subagent 一次运行的完整轨迹（内存态，进程内可见）。
 * 以 runId 为键：并行发派的多个实例（含多个 General）互不覆盖。
 */
data class SubAgentRunState(
    val runId: Uuid,
    val subAgentId: Uuid,
    /** 展示名：General 并行实例为调用时的 label，其余为定义名 */
    val displayName: String = "",
    val conversationId: Uuid? = null,
    val status: SubAgentRunStatus,
    val task: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val steps: List<SubAgentRunStep> = emptyList(),
    val result: String = "",
    val message: String = "",
)

/**
 * subagent 运行轨迹内存注册表（Koin single，进程内共享）。
 *
 * SubAgentRunner 在每次运行期间上报 start / 分块（工具调用）/ finish，
 * SubAgentsPage 与 SubAgentTracePage 观察 [runs] 展示执行轨迹；
 * 同时维护每对话活跃计数，作为并发上限闸门。
 */
class SubAgentRunMonitor {
    private val _runs = MutableStateFlow<Map<Uuid, SubAgentRunState>>(emptyMap())
    val runs: StateFlow<Map<Uuid, SubAgentRunState>> = _runs.asStateFlow()

    private val activeByConversation = ConcurrentHashMap<Uuid, AtomicInteger>()

    /** runId -> 该次运行的协程 Job，供「停止全部」外部取消 */
    private val jobs = ConcurrentHashMap<Uuid, Job>()

    /** 登记一次运行的 Job（由 SubAgentRunner 在启动时调用） */
    fun registerJob(runId: Uuid, job: Job) {
        jobs[runId] = job
    }

    /** 运行结束（正常/失败/超时/被取消）时移除登记 */
    fun unregisterJob(runId: Uuid) {
        jobs.remove(runId)
    }

    /**
     * 停止某对话中所有正在运行的 subagent（取消对应 Job，触发其返回「已停止」结果）。
     * 只影响该对话、且仅 RUNNING 的实例；已完成/已中断的不会被触碰。
     */
    fun cancelRunning(conversationId: Uuid?) {
        _runs.value.values
            .filter { it.status == SubAgentRunStatus.RUNNING && it.conversationId == conversationId }
            .forEach { run -> jobs[run.runId]?.cancel() }
    }

    /** 尝试占用一个该对话的并发名额；达到上限返回 false（调用方直接拒绝本次发派） */
    fun tryAcquire(conversationId: Uuid?, limit: Int): Boolean {
        if (conversationId == null) return true
        val counter = activeByConversation.computeIfAbsent(conversationId) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= limit) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    /** 释放名额；必须在 start 成功后与 [SubAgentRunner.run] 的 finally 中配对调用 */
    fun release(conversationId: Uuid?) {
        if (conversationId == null) return
        activeByConversation[conversationId]?.decrementAndGet()
    }

    fun start(
        runId: Uuid,
        subAgentId: Uuid,
        displayName: String,
        task: String,
        conversationId: Uuid?,
    ) {
        _runs.update {
            it + (runId to SubAgentRunState(
                runId = runId,
                subAgentId = subAgentId,
                displayName = displayName,
                conversationId = conversationId,
                status = SubAgentRunStatus.RUNNING,
                task = task,
            ))
        }
    }

    fun updateSteps(runId: Uuid, steps: List<SubAgentRunStep>) {
        _runs.update { current ->
            current[runId]?.let { state ->
                if (state.status != SubAgentRunStatus.RUNNING) return@update current
                current + (runId to state.copy(steps = steps))
            } ?: current
        }
    }

    fun finish(
        runId: Uuid,
        status: SubAgentRunStatus,
        result: String = "",
        message: String = "",
    ) {
        _runs.update { current ->
            current[runId]?.let { state ->
                current + (runId to state.copy(
                    status = status,
                    finishedAt = System.currentTimeMillis(),
                    result = result,
                    message = message,
                ))
            } ?: current
        }
    }
}
