package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacePhoneExporterTest {
    @Test
    fun `sanitizeTargetDir normalizes segments`() {
        assertEquals(listOf("exports", "2026"), sanitizeTargetDir("exports/2026"))
        assertEquals(listOf("a"), sanitizeTargetDir("/a/"))
        assertEquals(listOf("a", "b"), sanitizeTargetDir("\\a\\b"))
        assertEquals(emptyList(), sanitizeTargetDir(""))
        assertEquals(emptyList(), sanitizeTargetDir("   "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sanitizeTargetDir rejects dotdot traversal`() {
        sanitizeTargetDir("a/../b")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sanitizeTargetDir rejects single dot`() {
        sanitizeTargetDir(".")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sanitizeTargetDir rejects dotdot at root`() {
        sanitizeTargetDir("..")
    }
}
