package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class TodoStoreTest {
    private fun newStore(): Pair<TodoStore, File> {
        val dir = File.createTempFile("todo-store-test", "").apply { delete() }
        return TodoStore(dir) to dir
    }

    @Test
    fun `replaceAll persists full list`() {
        runBlocking {
            val (store, dir) = newStore()
            val id = Uuid.random()
            store.replaceAll(
                id,
                listOf(
                    TodoItem("调研代码", TodoStatus.IN_PROGRESS, TodoPriority.HIGH),
                    TodoItem("写实现"),
                    TodoItem("废弃项", TodoStatus.CANCELLED),
                ),
            )

            // 新实例也能从文件读到（持久化），顺序即提交顺序
            val reloaded = TodoStore(dir)
            val list = reloaded.todos(id).value
            assertEquals(3, list.size)
            assertEquals("调研代码", list[0].content)
            assertEquals(TodoStatus.IN_PROGRESS, list[0].status)
            assertEquals(TodoPriority.HIGH, list[0].priority)
            assertEquals(TodoStatus.PENDING, list[1].status)
            assertEquals(TodoPriority.MEDIUM, list[1].priority)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `second replaceAll replaces entire list`() {
        runBlocking {
            val (store, dir) = newStore()
            val id = Uuid.random()
            store.replaceAll(id, listOf(TodoItem("a"), TodoItem("b")))
            store.replaceAll(id, listOf(TodoItem("c")))

            assertEquals(listOf("c"), store.todos(id).value.map { it.content })
            dir.deleteRecursively()
        }
    }

    @Test
    fun `per-conversation isolation`() {
        runBlocking {
            val (store, dir) = newStore()
            val a = Uuid.random()
            val b = Uuid.random()
            store.replaceAll(a, listOf(TodoItem("对话A的任务")))
            store.replaceAll(b, listOf(TodoItem("对话B的任务")))

            assertEquals(1, store.todos(a).value.size)
            assertEquals(1, store.todos(b).value.size)
            assertEquals("对话A的任务", store.todos(a).value[0].content)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clear empties the list`() {
        runBlocking {
            val (store, dir) = newStore()
            val id = Uuid.random()
            store.replaceAll(id, listOf(TodoItem("任务")))
            store.clear(id)
            assertTrue(store.todos(id).value.isEmpty())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `legacy boolean-format file is migrated on load`() {
        runBlocking {
            val (store, dir) = newStore()
            val id = Uuid.random()
            // 旧版（2026-08-26 前）布尔模型 JSON
            val legacyJson = """
                [
                  {"id":"aaaaaaaaaaaa","title":"旧任务一","description":"细节","created_at":"2026-01-01T00:00:00+08:00","completed":true,"completed_at":"2026-01-02T00:00:00+08:00"},
                  {"id":"bbbbbbbbbbbb","title":"旧任务二","description":"","created_at":"2026-01-01T00:00:00+08:00","completed":false,"completed_at":null}
                ]
            """.trimIndent()
            File(dir, "$id.json").writeText(legacyJson)

            val list = store.todos(id).value
            assertEquals(2, list.size)
            assertEquals("旧任务一\n细节", list[0].content)
            assertEquals(TodoStatus.COMPLETED, list[0].status)
            assertEquals("旧任务二", list[1].content)
            assertEquals(TodoStatus.PENDING, list[1].status)

            // 加载时已回写为新格式：重新解析不再走迁移路径
            val raw = File(dir, "$id.json").readText()
            assertTrue(raw.contains("\"content\""))
            assertTrue(!raw.contains("\"title\""))
            dir.deleteRecursively()
        }
    }

    @Test
    fun `new-format file round-trips without mutation`() {
        runBlocking {
            val (store, dir) = newStore()
            val id = Uuid.random()
            store.replaceAll(id, listOf(TodoItem("任务", TodoStatus.PENDING)))

            val reloaded = TodoStore(dir).todos(id).value
            assertEquals(listOf(TodoItem("任务", TodoStatus.PENDING)), reloaded)
            dir.deleteRecursively()
        }
    }
}
