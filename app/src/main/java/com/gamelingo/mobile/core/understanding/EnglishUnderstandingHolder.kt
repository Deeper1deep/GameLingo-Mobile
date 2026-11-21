package com.gamelingo.mobile.core.understanding

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the active [EnglishUnderstanding] dataset. Mirrors the dictionary
 * holder: the bundled asset is the base, a verified newer dataset can be
 * swapped in, and tests get a fixed override seam.
 */
@Singleton
class EnglishUnderstandingHolder @Inject constructor(
    @ApplicationContext context: android.content.Context,
) {

    @Volatile
    private var active: EnglishUnderstanding = loadBundled(context)

    /** Test-only seam: a fixed dataset in place of the bundled asset. */
    @Volatile
    internal var datasetOverride: EnglishUnderstanding? = null

    fun get(): EnglishUnderstanding = datasetOverride ?: active

    /**
     * Verified local update: payload must parse, its SHA-256 must equal
     * [expectedSha256Hex], and its version must be newer than the active
     * one. Any mismatch is a silent no-op; the old dataset keeps serving.
     */
    fun updateVerified(jsonText: String, expectedSha256Hex: String): Boolean {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(jsonText.toByteArray(Charsets.UTF_8))
        val actual = digest.joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256Hex.trim(), ignoreCase = true)) return false
        val candidate = runCatching { EnglishUnderstanding.load(jsonText) }.getOrNull() ?: return false
        if (candidate.version <= (datasetOverride ?: active).version) return false
        active = candidate
        return true
    }

    private fun loadBundled(context: android.content.Context): EnglishUnderstanding =
        runCatching {
            context.assets.open("understanding/english_expressions.json")
                .bufferedReader().use { it.readText() }
        }.map { EnglishUnderstanding.load(it) }
            .getOrElse { EnglishUnderstanding.empty() }
}
