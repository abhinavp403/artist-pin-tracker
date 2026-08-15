package dev.abhinav.artistpin.core.auth

/**
 * Whether this build carries the values sign-in needs.
 *
 * Checked before anything constructs the Supabase client, because that is the point of no return:
 * every screen sits behind a session gate, so a build missing these cannot show the map, and
 * whatever supabase-kt does with a blank URL — throw on construction, or fail later at sign-in —
 * neither is a message anyone can act on. Naming the missing key turns an evening in logcat into a
 * thirty-second edit of `local.properties`.
 *
 * Deliberately not a Koin binding: the whole point is to answer this question *before* touching the
 * graph that would build the client.
 */
data class BackendConfig(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val googleWebClientId: String,
) {
    /** Keys with no usable value, named exactly as they appear in `local.properties`. */
    val missingKeys: List<String> = buildList {
        if (supabaseUrl.isBlank()) add("SUPABASE_URL")
        if (supabaseAnonKey.isBlank()) add("SUPABASE_ANON_KEY")
        if (googleWebClientId.isBlank()) add("GOOGLE_WEB_CLIENT_ID")
    }

    val isComplete: Boolean get() = missingKeys.isEmpty()

    companion object {
        /**
         * The one mistake worth catching beyond emptiness. Supabase's API docs page shows the full
         * REST endpoint, so `…supabase.co/rest/v1/` is the natural thing to copy — but supabase-kt
         * wants the bare project URL and appends that path itself, so the doubled path 404s every
         * query. Inside `backendFlow` that surfaces as an empty map rather than an error, which
         * makes it look like data loss.
         */
        fun urlWarningFor(url: String): String? = when {
            url.isBlank() -> null
            url.trimEnd('/').endsWith("/rest/v1") ->
                "SUPABASE_URL should be just https://<project-ref>.supabase.co — drop the /rest/v1/"
            else -> null
        }
    }
}
