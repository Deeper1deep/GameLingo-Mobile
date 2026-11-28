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
        private val RETRY_DELAY_RE = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"")
        private const val MAX_BODY_CHARS = 8_000

        /** Extracts only whitelisted fields; null body or no match gives an empty info. */
        fun parse(body: String?): ProviderErrorInfo {
            if (body.isNullOrBlank()) return ProviderErrorInfo()
            val text = body.take(MAX_BODY_CHARS)
            val status = STATUS_RE.find(text)?.groupValues?.get(1)?.takeIf { it in STATUSES }
            val quota = QUOTA_ID_RE.find(text)?.groupValues?.get(1)
            val delay = RETRY_DELAY_RE.find(text)?.groupValues?.get(1)?.let { RetryAfter.secondsToMillis(it) }
            return ProviderErrorInfo(status, quota, delay)
        }
    }
}

/** Parses an HTTP Retry-After header: delta-seconds or an HTTP-date. */
object RetryAfter {
    /**
     * Marker for a hint too large to represent as milliseconds. It is NEVER shortened into a
     * retry time: the gate treats it as blocked until a deliberate recovery (a new session).
     */
    const val INDEFINITE_MS = Long.MAX_VALUE

    /** True for the indefinite marker or any wait so large that it cannot be a real delay. */
    fun isIndefinite(ms: Long): Boolean = ms >= INDEFINITE_MS / 2

    /** Whole seconds as text to milliseconds; exact when representable, else [INDEFINITE_MS]. */
    fun secondsToMillis(digits: String): Long {
        val seconds = digits.toLongOrNull() ?: return INDEFINITE_MS
        return if (seconds > Long.MAX_VALUE / 2 / 1_000L) INDEFINITE_MS else seconds * 1_000L
    }

    /** Milliseconds to wait, exactly as given; [INDEFINITE_MS] when unrepresentable; null when absent or unparsable. */
    fun parseMillis(header: String?, nowMillis: Long): Long? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.all { it.isDigit() }) return secondsToMillis(value)
        val dateMillis = runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (dateMillis - nowMillis).coerceAtLeast(0L)
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
