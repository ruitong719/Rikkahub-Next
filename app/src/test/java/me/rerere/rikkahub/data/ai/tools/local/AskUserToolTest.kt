package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserToolTest {

    // ---------- parseAskUserQuestions ----------

    @Test
    fun `parses new format with header, structured options, multiple and custom`() {
        val json = """
            {"questions":[{
                "id":"q1",
                "question":"Which architecture?",
                "header":"Arch",
                "options":[
                    {"label":"MVVM","description":"Standard"},
                    {"label":"MVI","description":"Unidirectional"}
                ],
                "multiple":true,
                "custom":false
            }]}
        """.trimIndent()

        val questions = parseAskUserQuestions(Json.parseToJsonElement(json))
        assertEquals(1, questions.size)
        val q = questions[0]
        assertEquals("q1", q.id)
        assertEquals("Which architecture?", q.question)
        assertEquals("Arch", q.header)
        assertEquals(2, q.options.size)
        assertEquals("MVVM", q.options[0].label)
        assertEquals("Standard", q.options[0].description)
        assertTrue(q.multiple)
        assertFalse(q.custom)
    }

    @Test
    fun `defaults are single-select with custom input enabled`() {
        val json = """{"questions":[{"id":"q1","question":"OK?"}]}"""

        val q = parseAskUserQuestions(Json.parseToJsonElement(json)).single()
        assertFalse(q.multiple)
        assertTrue(q.custom)
        assertTrue(q.options.isEmpty())
        assertEquals("", q.header)
    }

    @Test
    fun `maps legacy string options and selection_type`() {
        val json = """
            {"questions":[
                {"id":"a","question":"Text?","options":["x","y"],"selection_type":"text"},
                {"id":"b","question":"Single?","options":["x","y"],"selection_type":"single"},
                {"id":"c","question":"Multi?","options":["x","y"],"selection_type":"multi"}
            ]}
        """.trimIndent()

        val questions = parseAskUserQuestions(Json.parseToJsonElement(json))
        assertEquals(listOf("x", "y"), questions[0].options.map { it.label })
        assertFalse(questions[0].multiple)
        assertTrue(questions[0].custom) // text -> custom 开

        assertFalse(questions[1].multiple)
        assertFalse(questions[1].custom) // single -> 仅选项

        assertTrue(questions[2].multiple)
        assertFalse(questions[2].custom) // multi -> 多选仅选项
    }

    @Test
    fun `invalid input falls back to empty list`() {
        assertEquals(emptyList<AskUserQuestion>(), parseAskUserQuestions(Json.parseToJsonElement("{}")))
        assertEquals(emptyList<AskUserQuestion>(), parseAskUserQuestions(Json.parseToJsonElement("not json")))
        assertEquals(emptyList<AskUserQuestion>(), parseAskUserQuestions(Json.parseToJsonElement("[]")))
    }

    // ---------- formatAskUserAnswer ----------

    private val arguments = """
        {"questions":[
            {"id":"q1","question":"Color?"},
            {"id":"q2","question":"Language?","options":[{"label":"Kotlin","description":""},{"label":"Rust","description":""}],"multiple":true},
            {"id":"q3","question":"Skipped?"}
        ]}
    """.trimIndent()

    @Test
    fun `formats string answer and unanswered question`() {
        val answer = buildJsonObject {
            put("answers", buildJsonObject {
                put("q1", JsonPrimitive("blue"))
            })
        }.toString()

        val formatted = formatAskUserAnswer(arguments, answer)
        assertTrue(formatted.startsWith("User has answered your questions:"))
        assertTrue(formatted.contains("\"Color?\"=\"blue\""))
        assertTrue(formatted.contains("\"Language?\"=\"Unanswered\""))
        assertTrue(formatted.contains("\"Skipped?\"=\"Unanswered\""))
        assertTrue(formatted.contains("Continue with the user's answers in mind."))
    }

    @Test
    fun `joins multiple answers with comma`() {
        val answer = buildJsonObject {
            put("answers", buildJsonObject {
                put("q1", JsonPrimitive(""))
                put("q2", buildJsonArray { add(JsonPrimitive("Kotlin")); add(JsonPrimitive("Rust")) })
            })
        }.toString()

        val formatted = formatAskUserAnswer(arguments, answer)
        assertTrue(formatted.contains("\"Language?\"=\"Kotlin, Rust\""))
    }

    @Test
    fun `falls back to raw answer on parse failure`() {
        assertEquals("garbage", formatAskUserAnswer(arguments, "garbage"))
        assertEquals("{}", formatAskUserAnswer("", "{}"))
    }
}