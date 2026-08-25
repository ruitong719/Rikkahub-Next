package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.SubAgent
import me.rerere.rikkahub.data.model.SubAgentToolCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- allowlist（精确白名单） ----

    @Test
    fun `READ category matches only its exact tools`() {
        assertTrue(matchesToolAllowlist("workspace_read_file", setOf(SubAgentToolCategory.READ)))
        assertTrue(matchesToolAllowlist("get_time_info", setOf(SubAgentToolCategory.READ)))
        assertFalse(matchesToolAllowlist("workspace_shell", setOf(SubAgentToolCategory.READ)))
        assertFalse(matchesToolAllowlist("search_web", setOf(SubAgentToolCategory.READ)))
        assertFalse(matchesToolAllowlist("mcp__server__tool", setOf(SubAgentToolCategory.READ)))
    }

    @Test
    fun `WRITE category matches file writes and todo tools`() {
        assertTrue(matchesToolAllowlist("workspace_write_file", setOf(SubAgentToolCategory.WRITE)))
        assertTrue(matchesToolAllowlist("workspace_edit_file", setOf(SubAgentToolCategory.WRITE)))
        assertTrue(matchesToolAllowlist("todo_create", setOf(SubAgentToolCategory.WRITE)))
        assertTrue(matchesToolAllowlist("todo_complete", setOf(SubAgentToolCategory.WRITE)))
        assertFalse(matchesToolAllowlist("workspace_read_file", setOf(SubAgentToolCategory.WRITE)))
        assertFalse(matchesToolAllowlist("eval_javascript", setOf(SubAgentToolCategory.WRITE)))
    }

    @Test
    fun `SHELL category matches only workspace_shell`() {
        assertTrue(matchesToolAllowlist("workspace_shell", setOf(SubAgentToolCategory.SHELL)))
        assertFalse(matchesToolAllowlist("workspace_bg_start", setOf(SubAgentToolCategory.SHELL)))
    }

    @Test
    fun `categories combine`() {
        val all = setOf(SubAgentToolCategory.READ, SubAgentToolCategory.WRITE, SubAgentToolCategory.SHELL)
        assertTrue(matchesToolAllowlist("workspace_read_file", all))
        assertTrue(matchesToolAllowlist("workspace_edit_file", all))
        assertTrue(matchesToolAllowlist("workspace_shell", all))
        // 白名单之外的工具任何类别都不放行
        assertFalse(matchesToolAllowlist("workspace_export_to_phone", all))
        assertFalse(matchesToolAllowlist("clipboard_tool", all))
        assertFalse(matchesToolAllowlist("text_to_speech", all))
    }

    @Test
    fun `ask_user is always excluded`() {
        assertFalse(matchesToolAllowlist("ask_user", setOf(SubAgentToolCategory.READ, SubAgentToolCategory.WRITE)))
    }

    @Test
    fun `empty allowlist allows nothing`() {
        assertFalse(matchesToolAllowlist("workspace_read_file", emptySet()))
    }

    // ---- 序列化迁移：旧字符串标签 -> 新枚举；删除的 modelId 字段被忽略 ----

    @Test
    fun `legacy allowlist labels migrate to categories`() {
        val json = """
            {
              "id": "00000000-0000-0000-0000-000000000001",
              "name": "Old Agent",
              "toolAllowlist": ["workspace_read", "search", "mcp", "local"],
              "modelId": "00000000-0000-0000-0000-000000000002"
            }
        """.trimIndent()
        val subAgent = Json { ignoreUnknownKeys = true }.decodeFromString<SubAgent>(json)
        assertEquals(setOf(SubAgentToolCategory.READ), subAgent.toolAllowlist)
    }

    @Test
    fun `legacy write and shell labels migrate`() {
        val json = """
            {
              "id": "00000000-0000-0000-0000-000000000001",
              "name": "Old Agent",
              "toolAllowlist": ["WORKSPACE_WRITE", "WORKSPACE_OTHER", "WORKSPACE_SHELL"]
            }
        """.trimIndent()
        val subAgent = Json { ignoreUnknownKeys = true }.decodeFromString<SubAgent>(json)
        assertEquals(
            setOf(SubAgentToolCategory.WRITE, SubAgentToolCategory.SHELL),
            subAgent.toolAllowlist,
        )
    }

    @Test
    fun `new enum names round trip`() {
        val original = SubAgent(
            toolAllowlist = setOf(SubAgentToolCategory.READ, SubAgentToolCategory.SHELL),
        )
        val json = Json { encodeDefaults = true }.encodeToString(SubAgent.serializer(), original)
        assertTrue(json.contains("\"READ\""))
        val decoded = Json.decodeFromString(SubAgent.serializer(), json)
        assertEquals(original.toolAllowlist, decoded.toolAllowlist)
    }

    @Test
    fun `unknown labels are dropped not fatal`() {
        val json = """
            {"id": "00000000-0000-0000-0000-000000000001", "name": "X", "toolAllowlist": ["future_thing"]}
        """.trimIndent()
        val subAgent = Json { ignoreUnknownKeys = true }.decodeFromString<SubAgent>(json)
        assertTrue(subAgent.toolAllowlist.isEmpty())
    }

    // ---- General 内置定义 ----

    @Test
    fun `general default has empty allowlist and fixed id`() {
        val general = me.rerere.rikkahub.data.model.defaultGeneralSubagent()
        assertTrue(me.rerere.rikkahub.data.model.isGeneralSubagent(general.id))
        assertNull(general.systemPrompt.ifBlank { null })
        assertTrue(general.toolAllowlist.isEmpty())
        assertTrue(general.requiresApproval)
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
