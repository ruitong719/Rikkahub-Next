package me.rerere.rikkahub.utils

import me.rerere.rikkahub.data.model.CustomAIIcon
import me.rerere.rikkahub.data.model.IconSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomAIIconMatcherTest {

    private fun icon(pattern: String, exactMatch: Boolean = false) = CustomAIIcon(
        pattern = pattern,
        exactMatch = exactMatch,
        source = IconSource.Emoji("X"),
    )

    @Test
    fun `包含匹配不区分大小写`() {
        val icons = listOf(icon("mygpt"))
        assertEquals("mygpt", matchCustomAIIcon("MyGPT-Proxy", icons)?.pattern)
    }

    @Test
    fun `精确匹配优先于包含匹配`() {
        val icons = listOf(icon("gpt"), icon("gpt-4o", exactMatch = true))
        assertEquals("gpt-4o", matchCustomAIIcon("gpt-4o", icons)?.pattern)
    }

    @Test
    fun `精确条目不参与包含匹配`() {
        val icons = listOf(icon("gpt-4o", exactMatch = true))
        assertNull(matchCustomAIIcon("my-gpt-4o-turbo", icons))
    }

    @Test
    fun `多条命中取最长关键词`() {
        val icons = listOf(icon("gpt"), icon("gpt-4o"))
        assertEquals("gpt-4o", matchCustomAIIcon("openai/gpt-4o-mini", icons)?.pattern)
    }

    @Test
    fun `空列表与无命中返回null`() {
        assertNull(matchCustomAIIcon("anything", emptyList()))
        assertNull(matchCustomAIIcon("nothing-matches", listOf(icon("foo"))))
    }
}
