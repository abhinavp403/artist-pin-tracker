package dev.abhinav.artistpin.data.backend

/**
 * A loggable, displayable one-liner with credentials stripped.
 *
 * supabase-kt's REST exceptions carry the whole request in their message — URL, headers, and the
 * session's `Authorization: Bearer …`. Anything that turns one of those into a log line, a stored
 * error, or a [dev.abhinav.artistpin.core.model.DataError] the UI might render has to go through
 * this first. Missing it in one place is enough: the token ends up in a screenshot.
 */
fun Throwable?.redactedMessage(): String {
    val raw = this?.message ?: return this?.javaClass?.simpleName ?: "unknown"
    return raw.substringBefore("Headers:")
        .replace(Regex("(?i)(bearer|apikey=?\\[?)\\s*[A-Za-z0-9._\\-]+"), "$1 <redacted>")
        .trim()
        .take(300)
}
