package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.GoalVerdictKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GoalModeTest {
    // ---- parseGoalVerdict ----

    @Test
    fun `parseGoalVerdict reads the first line`() {
        assertEquals(GoalVerdictKind.ACHIEVED, parseGoalVerdict("OUTCOME: ACHIEVED\nall tests pass"))
        assertEquals(GoalVerdictKind.IMPOSSIBLE, parseGoalVerdict("OUTCOME: IMPOSSIBLE\nblocked"))
        assertEquals(GoalVerdictKind.NOT_MET, parseGoalVerdict("OUTCOME: NOT_MET\nmissing tests"))
        assertEquals(GoalVerdictKind.NOT_MET, parseGoalVerdict("OUTCOME: NOT MET\nmissing tests"))
    }

    @Test
    fun `parseGoalVerdict is case insensitive and defaults to not met`() {
        assertEquals(GoalVerdictKind.ACHIEVED, parseGoalVerdict("outcome: achieved"))
        assertEquals(GoalVerdictKind.NOT_MET, parseGoalVerdict("I could not determine anything"))
        assertEquals(GoalVerdictKind.NOT_MET, parseGoalVerdict(""))
    }

    @Test
    fun `parseGoalVerdict does not read negations as achieved`() {
        assertEquals(GoalVerdictKind.NOT_MET, parseGoalVerdict("The goal has not been achieved yet."))
        assertEquals(GoalVerdictKind.NOT_MET, parseGoalVerdict("Goal state: not achieved"))
        assertEquals(GoalVerdictKind.NOT_MET, parseGoalVerdict("The task is incomplete"))
        assertEquals(GoalVerdictKind.NOT_MET, parseGoalVerdict("This is not impossible, keep going"))
        assertEquals(GoalVerdictKind.ACHIEVED, parseGoalVerdict("It was achieved"))
    }

    @Test
    fun `parseGoalVerdict finds an outcome line below the first line`() {
        assertEquals(
            GoalVerdictKind.NOT_MET,
            parseGoalVerdict("## Assessment\nOUTCOME: NOT_MET\nmissing tests"),
        )
        assertEquals(GoalVerdictKind.ACHIEVED, parseGoalVerdict("Summary\nOUTCOME: ACHIEVED"))
    }

    // ---- hasUnrecoverableGoalCause ----

    @Test
    fun `auth and quota errors are unrecoverable`() {
        assertTrue(RuntimeException("HTTP 401 Unauthorized").hasUnrecoverableGoalCause())
        assertTrue(RuntimeException("403 Forbidden").hasUnrecoverableGoalCause())
        assertTrue(RuntimeException("insufficient_quota").hasUnrecoverableGoalCause())
        assertTrue(RuntimeException("maximum context length exceeded").hasUnrecoverableGoalCause())
    }

    @Test
    fun `network errors are never unrecoverable`() {
        assertFalse(IOException("connection reset by peer").hasUnrecoverableGoalCause())
        assertFalse(IOException("timeout").hasUnrecoverableGoalCause())
        // 文本恰好含关键词，但本质是网络抖动，应走退避重试
        assertFalse(IOException("insufficient quota (proxy)").hasUnrecoverableGoalCause())
    }

    @Test
    fun `status code matching is word bounded`() {
        assertFalse(RuntimeException("generated 1401 tokens").hasUnrecoverableGoalCause())
        assertFalse(RuntimeException("request id 5403").hasUnrecoverableGoalCause())
        assertTrue(RuntimeException("HTTP 403").hasUnrecoverableGoalCause())
    }

    @Test
    fun `the whole cause chain is scanned`() {
        val wrapped = RuntimeException("request failed", RuntimeException("invalid api key"))
        assertTrue(wrapped.hasUnrecoverableGoalCause())

        val networkCause = IOException("connection reset")
        val wrappedNetwork = RuntimeException("request failed", networkCause)
        assertFalse(wrappedNetwork.hasUnrecoverableGoalCause())
    }
}
