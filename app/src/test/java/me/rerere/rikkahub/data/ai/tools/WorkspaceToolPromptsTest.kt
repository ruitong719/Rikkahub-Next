package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolPromptsTest {
    @Test
    fun `default prompts cover every workspace tool name`() {
        assertEquals(WORKSPACE_TOOL_NAMES.toSet(), DEFAULT_WORKSPACE_TOOL_PROMPTS.keys)
    }

    @Test
    fun `every default prompt is non-blank`() {
        assertTrue(
            DEFAULT_WORKSPACE_TOOL_PROMPTS.values.all { it.isNotBlank() }
        )
    }
}
