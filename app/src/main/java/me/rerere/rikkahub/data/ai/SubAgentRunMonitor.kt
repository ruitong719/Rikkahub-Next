package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/** subagent 一次运行的总体状态 */
enum class SubAgentRunStatus {
    RUNNING,
    SUCCESS,
    ERROR,
    TIMEOUT,
}

/** subagent 执行轨迹中的一步：一次工具调用（简略展示） */
data class SubAgentRunStep(
    val toolName: String,
    val inputPreview: String = "",
    val outputPreview: String = "",
)

/** subagent 一次运行的完整轨迹（内存态，进程内可见） */
data class SubAgentRunState(
    val subAgentId: Uuid,
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
 * SubAgentsPage 与 SubAgentTracePage 观察 [runs] 展示执行轨迹。
 */
class SubAgentRunMonitor {
    private val _runs = MutableStateFlow<Map<Uuid, SubAgentRunState>>(emptyMap())
    val runs: StateFlow<Map<Uuid, SubAgentRunState>> = _runs.asStateFlow()

    fun start(subAgentId: Uuid, task: String) {
        _runs.update {
            it + (subAgentId to SubAgentRunState(
                subAgentId = subAgentId,
                status = SubAgentRunStatus.RUNNING,
                task = task,
            ))
        }
    }

    fun updateSteps(subAgentId: Uuid, steps: List<SubAgentRunStep>) {
        _runs.update { current ->
            current[subAgentId]?.let { state ->
                if (state.status != SubAgentRunStatus.RUNNING) return@update current
                current + (subAgentId to state.copy(steps = steps))
            } ?: current
        }
    }

    fun finish(
        subAgentId: Uuid,
        status: SubAgentRunStatus,
        result: String = "",
        message: String = "",
    ) {
        _runs.update { current ->
            current[subAgentId]?.let { state ->
                current + (subAgentId to state.copy(
                    status = status,
                    finishedAt = System.currentTimeMillis(),
                    result = result,
                    message = message,
                ))
            } ?: current
        }
    }
}
