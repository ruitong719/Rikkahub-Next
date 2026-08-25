package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.uuid.Uuid

/**
 * Todo 条目（对齐 opencode todowrite）：无 id、顺序即列表位置，
 * 模型每次提交完整列表做全量替换。
 */
@Serializable
enum class TodoStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("cancelled")
    CANCELLED,
}

@Serializable
enum class TodoPriority {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW,
}

@Serializable
data class TodoItem(
    val content: String,
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: TodoPriority = TodoPriority.MEDIUM,
)

/**
 * 对话内 todo 数据源：每个 conversation 一个 JSON 文件（<baseDir>/<conversationId>.json）。
 * LLM 工具（todowrite 全量替换）与聊天 UI 共享同一数据源，
 * 工具写入后 StateFlow 自动更新，输入框角标实时反映未完成任务数。
 * baseDir 由 Koin 注入 filesDir/todo（Android），测试可传临时目录（纯 JVM）。
 *
 * 兼容：旧版布尔模型（id/title/description/completed）文件在首次加载时自动转换为新模型并回写。
 */
class TodoStore(private val baseDir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<Uuid, MutableStateFlow<List<TodoItem>>>()
    private val cacheLock = Any()
    private val mutex = Mutex()

    /** 旧版条目（2026-08-26 前）：布尔完成态 + 独立 id/title/description */
    @Serializable
    private data class LegacyTodoItem(
        val id: String,
        val title: String,
        val description: String = "",
        @SerialName("created_at")
        val createdAt: String? = null,
        val completed: Boolean = false,
        @SerialName("completed_at")
        val completedAt: String? = null,
    )

    fun todos(conversationId: Uuid): StateFlow<List<TodoItem>> = todosMutable(conversationId)

    private fun todosMutable(conversationId: Uuid): MutableStateFlow<List<TodoItem>> = synchronized(cacheLock) {
        cache.getOrPut(conversationId) {
            MutableStateFlow(load(conversationId))
        }
    }

    /** 模型经 todowrite 提交的完整列表，整体替换当前对话的 todo */
    suspend fun replaceAll(conversationId: Uuid, items: List<TodoItem>) {
        updateList(conversationId) { items }
    }

    /** 清空当前对话的整个 todo 列表（删除当前 todolist） */
    suspend fun clear(conversationId: Uuid) {
        updateList(conversationId) { emptyList() }
    }

    private suspend fun updateList(
        conversationId: Uuid,
        transform: (List<TodoItem>) -> List<TodoItem>,
    ) = mutex.withLock {
        val flow = todosMutable(conversationId)
        val newList = transform(flow.value)
        save(conversationId, newList)
        flow.value = newList
    }

    private fun file(conversationId: Uuid): File {
        if (!baseDir.exists()) baseDir.mkdirs()
        return File(baseDir, "$conversationId.json")
    }

    private fun load(conversationId: Uuid): List<TodoItem> {
        val f = file(conversationId)
        if (!f.exists()) return emptyList()
        val text = f.readText()

        runCatching { json.decodeFromString<List<TodoItem>>(text) }.getOrNull()?.let { return it }

        // 旧版布尔模型：title(+description) 合并为 content，completed 映射为状态；立即回写升级格式
        val legacy = runCatching { json.decodeFromString<List<LegacyTodoItem>>(text) }.getOrNull() ?: return emptyList()
        val migrated = legacy.map { old ->
            TodoItem(
                content = if (old.description.isBlank()) old.title else "${old.title}\n${old.description}",
                status = if (old.completed) TodoStatus.COMPLETED else TodoStatus.PENDING,
            )
        }
        save(conversationId, migrated)
        return migrated
    }

    /** 原子写：先写临时文件再 rename，避免并发/中断产生半截 JSON */
    private fun save(conversationId: Uuid, items: List<TodoItem>) {
        val f = file(conversationId)
        val content = json.encodeToString(items)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(f)) {
            f.writeText(content)
            tmp.delete()
        }
    }
}
