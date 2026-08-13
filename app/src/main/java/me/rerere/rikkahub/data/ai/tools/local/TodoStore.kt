package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

@Serializable
data class TodoItem(
    val id: String,
    val title: String,
    val description: String = "",
    @SerialName("created_at")
    val createdAt: String,
    val completed: Boolean = false,
    @SerialName("completed_at")
    val completedAt: String? = null,
)

/**
 * 对话内 todo 数据源：每个 conversation 一个 JSON 文件（<baseDir>/<conversationId>.json）。
 * LLM 工具（todo_create/update/complete）与聊天 UI 共享同一数据源，
 * 工具写入后 StateFlow 自动更新，输入框角标实时反映未完成任务数。
 * baseDir 由 Koin 注入 filesDir/todo（Android），测试可传临时目录（纯 JVM）。
 */
class TodoStore(private val baseDir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<Uuid, MutableStateFlow<List<TodoItem>>>()
    private val cacheLock = Any()
    private val mutex = Mutex()

    fun todos(conversationId: Uuid): StateFlow<List<TodoItem>> = todosMutable(conversationId)

    private fun todosMutable(conversationId: Uuid): MutableStateFlow<List<TodoItem>> = synchronized(cacheLock) {
        cache.getOrPut(conversationId) {
            MutableStateFlow(load(conversationId))
        }
    }

    suspend fun create(conversationId: Uuid, title: String, description: String): TodoItem {
        val item = TodoItem(
            id = generateId(),
            title = title,
            description = description,
            createdAt = nowIso(),
        )
        updateList(conversationId) { it + item }
        return item
    }

    suspend fun update(
        conversationId: Uuid,
        id: String,
        title: String?,
        description: String?,
    ): TodoItem? {
        var updated: TodoItem? = null
        updateList(conversationId) { list ->
            list.map { item ->
                if (item.id == id) {
                    item.copy(
                        title = title ?: item.title,
                        description = description ?: item.description,
                    ).also { updated = it }
                } else {
                    item
                }
            }
        }
        return updated
    }

    suspend fun setCompleted(conversationId: Uuid, id: String, completed: Boolean): TodoItem? {
        var updated: TodoItem? = null
        val now = nowIso()
        updateList(conversationId) { list ->
            list.map { item ->
                if (item.id == id) {
                    item.copy(
                        completed = completed,
                        completedAt = if (completed) now else null,
                    ).also { updated = it }
                } else {
                    item
                }
            }
        }
        return updated
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
        return runCatching { json.decodeFromString<List<TodoItem>>(f.readText()) }
            .getOrDefault(emptyList())
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

    private fun generateId(): String {
        val chars = "0123456789abcdef"
        return buildString(12) { repeat(12) { append(chars.random()) } }
    }

    private fun nowIso(): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atZone(ZoneId.systemDefault()))
}
