package me.bubblespark.tidilo.ui.screens.notifications

import androidx.annotation.StringRes
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.ImportTaskErrorMessages
import me.bubblespark.tidilo.parser.ArticleContentUnavailable

/** User-facing cause of an unresolved import or feed failure, derived from its stored diagnostic text. */
enum class FailureReason(@StringRes val explanationRes: Int) {
    SERVER_UNAVAILABLE(R.string.notification_failure_server_unavailable),
    RATE_LIMITED(R.string.notification_failure_rate_limited),
    ACCESS_DENIED(R.string.notification_failure_access_denied),
    NOT_FOUND(R.string.notification_failure_not_found),
    HTTP_OTHER(R.string.notification_failure_http_other),
    SECURE_CONNECTION(R.string.notification_failure_secure_connection),
    NETWORK(R.string.notification_failure_network),
    NO_CONTENT(R.string.notification_failure_no_content),
    SAVE_FAILED(R.string.notification_failure_save_failed),
    UNKNOWN(R.string.notification_failure_unknown)
}

private val HTTP_STATUS = Regex("""\bHTTP(?: error)? (\d{3})\b""", RegexOption.IGNORE_CASE)

/**
 * Classifies the raw diagnostic stored with a failure. Rules are ordered: host-bearing network messages are
 * matched before SSL keywords so host names such as `ssl.example.com` cannot change the result.
 */
fun classifyFailure(raw: String?): FailureReason {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return FailureReason.UNKNOWN
    // The repository prefixes local save failures; the wrapped message must not change the explanation.
    if (text.startsWith(ImportTaskErrorMessages.SAVE_FAILURE_PREFIX)) return FailureReason.SAVE_FAILED
    HTTP_STATUS.find(text)?.let { match ->
        return when (match.groupValues[1].toInt()) {
            500, 502, 503, 504 -> FailureReason.SERVER_UNAVAILABLE
            429 -> FailureReason.RATE_LIMITED
            401, 403 -> FailureReason.ACCESS_DENIED
            404, 410 -> FailureReason.NOT_FOUND
            else -> FailureReason.HTTP_OTHER
        }
    }
    val lower = text.lowercase()
    return when {
        "unable to resolve host" in lower || "failed to connect" in lower -> FailureReason.NETWORK
        listOf("ssl", "certificat", "handshake", "trust anchor").any { it in lower } -> FailureReason.SECURE_CONNECTION
        listOf("timed out", "timeout", "exceeded 30 seconds", "connection").any { it in lower } -> FailureReason.NETWORK
        text == ArticleContentUnavailable.MESSAGE -> FailureReason.NO_CONTENT
        else -> FailureReason.UNKNOWN
    }
}
