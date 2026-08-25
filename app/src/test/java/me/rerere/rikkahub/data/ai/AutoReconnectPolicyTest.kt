package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class AutoReconnectPolicyTest {
    @Test
    fun `bare io exception is retryable`() {
        assertTrue(IOException("Connection reset").hasRetryableNetworkCause())
    }

    @Test
    fun `socket timeout is retryable`() {
        assertTrue(SocketTimeoutException("timeout").hasRetryableNetworkCause())
    }

    @Test
    fun `wrapped io exception is retryable`() {
        val e = IllegalStateException("request failed", IOException("reset by peer"))
        assertTrue(e.hasRetryableNetworkCause())
    }

    @Test
    fun `api error without io cause is not retryable`() {
        assertFalse(IllegalStateException("HTTP 401 Unauthorized").hasRetryableNetworkCause())
    }
}
