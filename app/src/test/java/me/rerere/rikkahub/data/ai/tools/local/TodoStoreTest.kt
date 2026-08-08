package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `create persists and returns 12-hex id`() = runBlocking {
        val (store, dir) = newStore()
        val id = Uuid.random()
        val todo = store.create(id, "买菜", "去超市")

        assertTrue(todo.id.matches(Regex("[0-9a-f]{12}")))
        assertEquals("买菜", todo.title)
        assertEquals("去超市", todo.description)
        assertFalse(todo.completed)
        assertNull(todo.completedAt)

        // 新实例也能从文件读到（持久化）
        val reloaded = TodoStore(dir)
        val list = reloaded.todos(id).value
        assertEquals(1, list.size)
        assertEquals("买菜", list[0].title)
        dir.deleteRecursively()
    }

    @Test
    fun `update edits title and description`() = runBlocking {
        val (store, dir) = newStore()
        val id = Uuid.random()
        val todo = store.create(id, "旧标题", "旧描述")

        val updated = store.update(id, todo.id, "新标题", "新描述")
        assertNotNull(updated)
        assertEquals("新标题", updated!!.title)
        assertEquals("新描述", updated.description)

        // 只改 title，description 保持
        val updated2 = store.update(id, todo.id, "标题2", null)
        assertEquals("标题2", updated2!!.title)
        assertEquals("新描述", updated2.description)
        dir.deleteRecursively()
    }

    @Test
    fun `update missing id returns null`() = runBlocking {
        val (store, dir) = newStore()
        val id = Uuid.random()
        store.create(id, "任务", "")
        assertNull(store.update(id, "000000000000", "x", null))
        dir.deleteRecursively()
    }

    @Test
    fun `complete marks done and can be undone`() = runBlocking {
        val (store, dir) = newStore()
        val id = Uuid.random()
        val todo = store.create(id, "任务", "")

        val done = store.setCompleted(id, todo.id, true)
        assertTrue(done!!.completed)
        assertNotNull(done.completedAt)

        val undone = store.setCompleted(id, todo.id, false)
        assertFalse(undone!!.completed)
        assertNull(undone.completedAt)
        dir.deleteRecursively()
    }

    @Test
    fun `per-conversation isolation`() = runBlocking {
        val (store, dir) = newStore()
        val a = Uuid.random()
        val b = Uuid.random()
        store.create(a, "对话A的任务", "")
        store.create(b, "对话B的任务", "")

        assertEquals(1, store.todos(a).value.size)
        assertEquals(1, store.todos(b).value.size)
        assertEquals("对话A的任务", store.todos(a).value[0].title)
        dir.deleteRecursively()
    }
}
