package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentLogicTest {
    // ---- slugify ----

    @Test
    fun `slugify lowercases and replaces non-ascii`() {
        assertEquals("code_reviewer", slugify("Code Reviewer"))
        assertEquals("data_analyst_1", slugify("Data Analyst 1"))
        assertEquals("agent", slugify("中文名"))
        assertEquals("agent", slugify(""))
        assertEquals("agent", slugify("!!!"))
        assertEquals("a_b", slugify("A  B"))
    }

    @Test
    fun `uniqueToolName appends short id on collision`() {
        val id = Uuid.parse("00000000-0000-0000-0000-00000000abcd")
        assertEquals("researcher", uniqueToolName("researcher", setOf("code_reviewer"), id))
        assertEquals("researcher_abcd", uniqueToolName("researcher", setOf("researcher"), id))
        assertEquals("researcher_abcd1", uniqueToolName("researcher", setOf("researcher", "researcher_abcd"), id))
    }

    // ---- allowlist ----

    @Test
    fun `allowlist matches exact tool names`() {
        assertTrue(matchesToolAllowlist("workspace_read_file", setOf("workspace_read_file")))
        assertTrue(matchesToolAllowlist("search_web", setOf("search_web")))
        assertFalse(matchesToolAllowlist("workspace_shell", setOf("workspace_read_file")))
    }

    @Test
    fun `allowlist matches category labels`() {
        assertTrue(matchesToolAllowlist("workspace_read_file", setOf("workspace_read")))
        assertTrue(matchesToolAllowlist("workspace_edit_file", setOf("workspace_write")))
        assertTrue(matchesToolAllowlist("workspace_bg_start", setOf("workspace_other")))
        assertTrue(matchesToolAllowlist("workspace_create_backup", setOf("workspace_other")))
        assertTrue(matchesToolAllowlist("mcp__server__tool", setOf("mcp")))
        assertTrue(matchesToolAllowlist("todo_create", setOf("local")))
        assertTrue(matchesToolAllowlist("get_time_info", setOf("local")))
        assertFalse(matchesToolAllowlist("workspace_shell", setOf("workspace_read")))
        assertFalse(matchesToolAllowlist("search_web", setOf("workspace_read")))
    }

    @Test
    fun `ask_user is always excluded`() {
        assertFalse(matchesToolAllowlist("ask_user", setOf("ask_user")))
        assertFalse(matchesToolAllowlist("ask_user", setOf("local")))
    }

    @Test
    fun `empty allowlist allows nothing`() {
        assertFalse(matchesToolAllowlist("search_web", emptySet()))
        assertFalse(matchesToolAllowlist("workspace_read_file", emptySet()))
    }

    // ---- stripReasoning ----

    @Test
    fun `stripReasoning removes reasoning parts and think tags`() {
        val messages = listOf(
            UIMessage.user("hello"),
            UIMessage.assistant(
                "answer <think>internal reasoning</think> visible"
            ).copy(
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "hidden thought"),
                    UIMessagePart.Text("answer <think>internal reasoning</think> visible"),
                )
            ),
        )

        val stripped = stripReasoning(messages)
        assertEquals(2, stripped.size)
        val parts = stripped[1].parts
        assertEquals(1, parts.size)
        assertTrue(parts[0] is UIMessagePart.Text)
        assertEquals("answer  visible", (parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `stripReasoning keeps normal text and other parts`() {
        val messages = listOf(
            UIMessage.assistant("plain answer").copy(
                parts = listOf(
                    UIMessagePart.Text("plain answer"),
                )
            )
        )
        val stripped = stripReasoning(messages)
        assertEquals("plain answer", ((stripped[0].parts[0] as UIMessagePart.Text).text))
    }

    // ---- result json ----

    @Test
    fun `buildSubAgentResultJson includes usage when present`() {
        val json = buildSubAgentResultJson(
            status = "success",
            result = "done",
            steps = 3,
            usage = TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30),
        )
        assertTrue(json.contains("\"status\":\"success\""))
        assertTrue(json.contains("\"result\":\"done\""))
        assertTrue(json.contains("\"steps\":3"))
        assertTrue(json.contains("\"totalTokens\":30"))
    }

    @Test
    fun `buildSubAgentResultJson omits usage when null`() {
        val json = buildSubAgentResultJson(status = "timeout", result = "took too long", steps = 0, usage = null)
        assertFalse(json.contains("usage"))
    }
}
