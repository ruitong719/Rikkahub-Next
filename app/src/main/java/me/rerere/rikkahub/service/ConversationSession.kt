package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

/** 生成中排队的用户消息；[answer] 记录入队时的发送意图，flush 时决定是否触发生成 */
data class QueuedUserMessage(
    val message: UIMessage,
    val answer: Boolean,
)

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating

    // 自动拉起限流：连续触发次数防模型开新后台任务自我续跑，连续失败次数防无效重试；用户主动操作时重置
    @Volatile
    var autoResumeStreak: Int = 0
        private set

    // 「本次会话内全部同意」的工具授权（目录子树/整工具），进程或会话回收即失效
    val toolGrants = ToolGrants()

    @Volatile
    var autoResumeFailures: Int = 0
        private set

    fun onAutoResumeTriggered() {
        autoResumeStreak++
    }

    // Goal 模式评审后自动续跑限流：连续评审未完成次数，达到上限停止自动续跑等用户介入
    @Volatile
    var goalResumeStreak: Int = 0
        private set

    fun onGoalResumeTriggered() {
        goalResumeStreak++
    }

    fun resetGoalResumeStreak() {
        goalResumeStreak = 0
    }

    // 评审重试次数：网络类失败时退避重试的上限计数，评审成功执行后清零
    @Volatile
    var goalEvalRetryCount: Int = 0
        private set

    fun onGoalEvalRetry() {
        goalEvalRetryCount++
    }

    fun resetGoalEvalRetryCount() {
        goalEvalRetryCount = 0
    }

    fun onAutoResumeAttemptFailed() {
        autoResumeFailures++
    }

    fun onAutoResumeConsumed() {
        autoResumeFailures = 0
    }

    fun resetAutoResumeGuards() {
        autoResumeStreak = 0
        autoResumeFailures = 0
    }

    /** 生成中用户补充消息队列（LLM 输出期间入队，生成循环间隙插入对话或结束后兜底发送） */
    private val _queuedMessages = MutableStateFlow<List<QueuedUserMessage>>(emptyList())
    val queuedMessages: StateFlow<List<QueuedUserMessage>> = _queuedMessages.asStateFlow()
    val queuedCount: Int get() = _queuedMessages.value.size

    /** 是否仍有页面引用（聊天页打开中），自动拉起只处理活跃会话 */
    fun hasReferences(): Boolean = refCount.get() > 0

    fun enqueue(message: UIMessage, answer: Boolean = true) {
        _queuedMessages.update { it + QueuedUserMessage(message, answer) }
    }

    /** 取出并清空队列（原子），供生成循环/结束时插入对话 */
    fun drainQueue(): List<QueuedUserMessage> {
        var drained: List<QueuedUserMessage> = emptyList()
        _queuedMessages.update { current ->
            drained = current
            emptyList()
        }
        return drained
    }

    /**
     * 把消息按原序放回队首。供生成循环插入失败（如被打断）时回滚，
     * 避免排队消息既不在队列也不在会话中；回滚场景按普通发送处理（answer=true）。
     */
    fun requeueFront(messages: List<UIMessage>) {
        if (messages.isEmpty()) return
        _queuedMessages.update { messages.map { QueuedUserMessage(it, answer = true) } + it }
    }

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 生成槽位锁：登记/预留互斥，消除\"检查空闲 + 登记\"与并发发起者之间的竞态窗口
    private val slotLock = Any()

    // 关联任务集合：生成主任务 + 审批排队任务（连续审批不互相取消，仅登记；停止时统一取消）
    private val activeJobs = mutableSetOf<Job>()

    /**
     * 登记任务。默认取消前一个（沿用旧语义）；
     * 审批等排队任务用 [cancelPrevious] = false 串行化，互不打断。
     */
    fun setJob(job: Job?, cancelPrevious: Boolean = true) = synchronized(slotLock) {
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(slotLock) {
                activeJobs.remove(job)
                // 排队任务被取消时同步取消其前驱（连续审批：避免前驱完成又启动下一个审批）
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // 仅当当前登记的仍是该 job 时才清空：
                // flush 场景旧 job 完成时可能已登记新 job，不能顶掉它
                if (_generationJob.value === job) {
                    _generationJob.value = null
                }
                if (refCount.get() <= 0) {
                    scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    /**
     * 预留式开始生成：原子确认当前无活跃任务后才调用 [start] 创建并登记任务，
     * 已有活跃任务时返回 null 且不调用 [start]。
     * 占位 Job 保证预留到真实登记之间 isGenerating 为真，挡住并发的用户发送/其他拉起。
     */
    fun beginGenerationIfIdle(start: () -> Job): Job? = synchronized(slotLock) {
        if (_generationJob.value?.isActive == true) return null
        val placeholder = Job()
        _generationJob.value = placeholder
        try {
            val job = start()
            attachGenerationJob(job)
            job
        } finally {
            placeholder.cancel()
        }
    }

    private fun attachGenerationJob(job: Job?) {
        _generationJob.value = job
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion {
            synchronized(slotLock) {
                activeJobs.remove(job)
                // 仅当当前登记的仍是该 job 时才清空：
                // flush 场景旧 job 完成时可能已登记新 job，不能顶掉它
                if (_generationJob.value === job) {
                    _generationJob.value = null
                }
                if (refCount.get() <= 0) {
                    scheduleIdleCheck()
                }
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    fun cancelJobs(): List<Job> = synchronized(slotLock) {
        activeJobs.toList().also { jobs ->
            // 先取消等待者（审批排队），避免前驱完成时又启动下一个审批
            jobs.asReversed().forEach { it.cancel() }
        }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        cancelJobs()
        _generationJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

/** Serialize approval saves without cancelling earlier decisions; stopping cancels the whole chain. */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        withContext(NonCancellable) { previous?.join() }
        throw e
    }
}
