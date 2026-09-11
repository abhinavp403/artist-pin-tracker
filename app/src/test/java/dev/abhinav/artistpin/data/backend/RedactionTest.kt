package dev.abhinav.artistpin.data.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the message this guards against is copied from a real supabase-kt exception: the
 * whole request rides along, bearer token and all, and it had reached logcat, the outbox, and a
 * snackbar before being redacted in every one of those places.
 */
class RedactionTest {

    private val token = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLTEifQ.c2lnbmF0dXJl"

    private val supabaseStyleMessage = """
        The object exceeded the maximum allowed size
        URL: https://abcdefghijklmnopqrst.supabase.co/storage/v1/object/event-media/u/e/m.mp4
        Headers: [Authorization=[Bearer $token], apikey=[sb_publishable_abc123], X-Client-Info=[supabase-kt/3.1.4]]
        Http Method: POST
    """.trimIndent()

    @Test
    fun `the request headers never survive`() {
        val redacted = RuntimeException(supabaseStyleMessage).redactedMessage()

        assertFalse("token leaked: $redacted", redacted.contains(token))
        assertFalse(redacted.contains("Headers:"))
        assertFalse(redacted.contains("sb_publishable_abc123"))
    }

    @Test
    fun `the part that explains the failure is kept`() {
        // Redaction that also threw away the reason would make every failure undiagnosable.
        val redacted = RuntimeException(supabaseStyleMessage).redactedMessage()

        assertTrue(redacted.contains("exceeded the maximum allowed size"))
    }

    @Test
    fun `a bearer token outside a headers block is still masked`() {
        val redacted = RuntimeException("rejected: Bearer $token was not accepted").redactedMessage()

        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `a message-less exception reports its type rather than nothing`() {
        assertEquals("IllegalStateException", IllegalStateException().redactedMessage())
    }
}
