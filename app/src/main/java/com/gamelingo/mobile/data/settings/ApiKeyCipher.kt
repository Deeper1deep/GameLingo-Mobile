package com.gamelingo.mobile.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts the user's online API key with a key that lives in the Android
 * Keystore (hardware-backed where available). Only the ciphertext ever
 * touches DataStore; the plaintext key never lands in logs or error messages.
 *
 * Format: base64( IV || ciphertext || GCM tag ). A decryption failure (key
 * invalidated by a lock-screen change, corrupt value) returns null so the
 * caller can treat the key as not configured instead of crashing or leaking.
 */
@Singleton
class ApiKeyCipher @Inject constructor() {

    // Lazy: the Android Keystore does not exist on the JVM (unit tests), and
    // constructing the cipher must stay harmless there. Only an actual
    // encrypt/decrypt call touches the keystore.
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // The keystore key is cached in memory: getEntry is a binder call into the
    // keystore daemon, far too expensive to repeat on every decrypt (the
    // settings flow resolves the key on every emission).
    @Volatile
    private var cachedKey: SecretKey? = null

    // Memoized decrypt results, keyed by the ciphertext: the same encrypted
    // value is never decrypted twice in a process, so hot paths (settings
    // reads on every capture tick and every UI collect) never touch the
    // keystore after the first resolution.
    private val decryptCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /** @return the decrypted key, or null when the stored value cannot be decrypted. */
    fun decrypt(encoded: String): String? {
        decryptCache[encoded]?.let { return it }
        val decrypted = runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > GCM_IV_LENGTH) { "value too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, bytes, 0, GCM_IV_LENGTH),
            )
            String(cipher.doFinal(bytes, GCM_IV_LENGTH, bytes.size - GCM_IV_LENGTH), Charsets.UTF_8)
        }.getOrNull() ?: return null
        if (decryptCache.size < MAX_CACHE_ENTRIES) {
            decryptCache[encoded] = decrypted
        }
        return decrypted
    }

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val key = loadOrCreateKey()
            cachedKey = key
            return key
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gamelingo_online_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
        private const val MAX_CACHE_ENTRIES = 8
    }
}
