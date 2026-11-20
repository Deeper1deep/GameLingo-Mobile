package com.gamelingo.mobile.translate

import java.io.IOException

/**
 * ONLINE mode was selected but the setup is incomplete (no API key or no
 * cloud consent). The user picked online explicitly, so this surfaces as a
 * visible failure instead of silently translating on-device.
 */
class OnlineNotConfiguredException : IOException("online mode selected but key or consent missing")

/** The provider rejected the request for quota or rate reasons (HTTP 429). */
class QuotaExceededException(message: String) : IOException(message)

/** The provider is temporarily unavailable (HTTP 5xx). */
class ProviderUnavailableException(message: String) : IOException(message)

/** The API key was rejected (HTTP 400/401/403): wrong, expired or blocked. */
class ApiAuthException(message: String) : IOException(message)
