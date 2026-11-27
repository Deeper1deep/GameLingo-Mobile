package com.gamelingo.mobile.translate

import java.io.IOException

/**
 * ONLINE mode was selected but the setup is incomplete (no API key or no
 * cloud consent). The user picked online explicitly, so this surfaces as a
 * visible failure instead of silently translating on-device.
 */
class OnlineNotConfiguredException : IOException("online mode selected but key or consent missing")

/**
 * The provider rejected the request for quota or rate reasons (HTTP 429).
 * [retryAfterMs] carries the provider's Retry-After hint when it sent one,
 * so the cooldown respects it instead of guessing.
 */
class QuotaExceededException(
    message: String,
    val retryAfterMs: Long? = null,
) : IOException(message)

/** The provider is temporarily unavailable (HTTP 5xx). */
open class ProviderUnavailableException(message: String) : IOException(message)

/**
 * The provider answered but refused or truncated the line (safety block,
 * MAX_TOKENS). Still a provider failure for routing, but reported as its own
 * state instead of being mislabelled as a network problem.
 */
class ProviderRefusedException(message: String) : ProviderUnavailableException(message)

/** The API key was rejected (HTTP 400/401/403): wrong, expired or blocked. */
class ApiAuthException(message: String) : IOException(message)

/**
 * The engine answered twice (the second time with reduced context) and both
 * answers looked corrupted. The line fails visibly; nothing is cached,
 * published as a translation or written to history.
 */
class RejectedOutputException : Exception("translation output rejected by the quality gate")
