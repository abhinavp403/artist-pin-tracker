package dev.abhinav.artistpin.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendConfigTest {

    private fun config(
        url: String = "https://abcdefghijklmnopqrst.supabase.co",
        key: String = "sb_publishable_xxx",
        clientId: String = "123-abc.apps.googleusercontent.com",
    ) = BackendConfig(url, key, clientId)

    @Test
    fun `a fully configured build is complete`() {
        assertTrue(config().isComplete)
        assertTrue(config().missingKeys.isEmpty())
    }

    @Test
    fun `each missing value is named by its local dot properties key`() {
        // The whole value of this check is the name — "something went wrong" costs an evening,
        // "add SUPABASE_URL" costs thirty seconds.
        assertEquals(listOf("SUPABASE_URL"), config(url = "").missingKeys)
        assertEquals(listOf("SUPABASE_ANON_KEY"), config(key = "").missingKeys)
        assertEquals(listOf("GOOGLE_WEB_CLIENT_ID"), config(clientId = "").missingKeys)
    }

    @Test
    fun `every missing value is reported at once, not one per rebuild`() {
        val missing = BackendConfig("", "", "").missingKeys
        assertEquals(
            listOf("SUPABASE_URL", "SUPABASE_ANON_KEY", "GOOGLE_WEB_CLIENT_ID"),
            missing,
        )
        assertFalse(BackendConfig("", "", "").isComplete)
    }

    @Test
    fun `whitespace is not a value`() {
        // A key left as "SUPABASE_URL= " reads as present to a null check but cannot work.
        assertFalse(config(url = "   ").isComplete)
    }

    @Test
    fun `the rest endpoint copied from the API docs is called out`() {
        // Supabase's docs page shows the full REST URL, so this is the natural thing to paste —
        // and supabase-kt appends the same path itself, so every query 404s. Inside backendFlow
        // that surfaces as an empty map, which looks like data loss rather than a typo.
        val warning = BackendConfig.urlWarningFor("https://abc.supabase.co/rest/v1/")
        assertTrue(warning!!.contains("/rest/v1"))

        assertNull(BackendConfig.urlWarningFor("https://abc.supabase.co"))
        assertNull(BackendConfig.urlWarningFor(""))
    }
}
