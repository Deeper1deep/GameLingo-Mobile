package com.gamelingo.mobile.translate

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.TimeoutCancellationException

/**
 * What a provider error response is allowed to tell us. Only values that match
 * a fixed vocabulary or a strict token pattern are kept; the response message
 * (which could echo request text) and any header or key are never stored.
 */
data class ProviderErrorInfo(
    /** gRPC-style status such as RESOURCE_EXHAUSTED, from a fixed whitelist. */
    val status: String? = null,
    /** Provider quota identifier (per-minute vs per-day) when the provider names one. */
    val quotaId: String? = null,
    /** Provider's own retry delay in milliseconds, when given. */
    val retryDelayMs: Long? = null,
) {
    companion object {
        private val STATUSES = setOf(
            "RESOURCE_EXHAUSTED", "PERMISSION_DENIED", "UNAUTHENTICATED", "INVALID_ARGUMENT",
            "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED", "FAILED_PRECONDITION", "NOT_FOUND",
        )
        private val STATUS_RE = Regex("\"status\"\\s*:\\s*\"([A-Z_]{3,40})\"")
        private val QUOTA_ID_RE = Regex("\"quotaId\"\\s*:\\s*\"([A-Za-z0-9_\\-]{1,100})\"")
        private val RETRY_DELAY_RE = Regex("\"retryDelay\"\\s*:\\s*\"(\\d{1,5})(?:\\.\\d+)?s\"")
        private const val MAX_BODY_CHARS = 8_000
        private const val MAX_RETRY_DELAY_SEC = 600L

        /** Extracts only whitelisted fields; null body or no match gives an empty info. */
        fun parse(body: String?): ProviderErrorInfo {
            if (body.isNullOrBlank()) return ProviderErrorInfo()
            val text = body.take(MAX_BODY_CHARS)
            val status = STATUS_RE.find(text)?.groupValues?.get(1)?.takeIf { it in STATUSES }
            val quota = QUOTA_ID_RE.find(text)?.groupValues?.get(1)
            val delay = RETRY_DELAY_RE.find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?.coerceIn(0L, MAX_RETRY_DELAY_SEC)?.times(1_000L)
            return ProviderErrorInfo(status, quota, delay)
        }
    }
}

/** Parses an HTTP Retry-After header: delta-seconds or an HTTP-date. */
object RetryAfter {
    /** A hostile or buggy header cannot park the online engine longer than this. */
    const val MAX_SECONDS = 600L

    /** Milliseconds to wait, clamped to [0, MAX_SECONDS]; null when absent or unparsable. */
    fun parseMillis(header: String?, nowMillis: Long): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { seconds ->
            return if (seconds < 0) null else minOf(seconds, MAX_SECONDS) * 1_000L
        }
        val dateMillis = runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (dateMillis - nowMillis).coerceIn(0L, MAX_SECONDS * 1_000L)
    }
}

/** Stable, non-obfuscated error codes for diagnostics (class names are minified in release). */
object TranslationErrorCode {
    fun of(error: Throwable): String = when (error) {
        is QuotaExceededException -> buildString {
            append(if (error.fromCooldown) "QUOTA_COOLDOWN" else "QUOTA_429")
            error.provider.status?.let { append('/').append(it) }
            error.provider.quotaId?.let { append('/').append(it) }
        }
        is ApiAuthException -> "AUTH_REJECTED"
        is OnlineNotConfiguredException -> "ONLINE_NOT_CONFIGURED"
        is ProviderRefusedException -> "PROVIDER_REFUSED"
        is ProviderUnavailableException -> "PROVIDER_UNAVAILABLE"
        is TimeoutCancellationException -> "TIMEOUT"
        is java.io.IOException -> "NETWORK_IO"
        else -> "UNKNOWN"
    }
}
