package com.oneasmr.app.data.local.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Symmetric encryption primitive used by [KeystoreDataStore].
 *
 * THREAT MODEL (honest — what this protects and what it does NOT):
 * - Protects against: casual reads of the app's DataStore files on a device
 *   (e.g. an unprivileged process walking /data/data, a dumped backup file
 *   read by someone without root). The AES key never leaves the Android
 *   Keystore, so the ciphertext is useless without it.
 * - Does NOT protect against: root (the key is still usable by the owning
 *   app on the device, and a root process can read the keystore or hook the
 *   process), full disk image extraction with the keystore, or
 *   `adb backup`-style transports where the key material ships with the data.
 *   No on-device crypto can fix those; they are out of scope by design
 *   (see plan Task 5, decision taken because EncryptedSharedPreferences is
 *   deprecated and offered no extra guarantees against these either).
 */
interface KeyCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Throws (e.g. [javax.crypto.AEADBadTagException]) on tampered/wrong-key input. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-GCM/[NoPadding] over an injected [SecretKey] — the JVM-testable core of
 * the crypto path (no Android APIs here). Payload layout: 12-byte GCM IV
 * followed by ciphertext+tag, so [decrypt] only needs the raw bytes.
 */
class AesGcmKeyCipher(private val key: SecretKey) : KeyCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val body = cipher.doFinal(plaintext)
        return cipher.iv + body
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > GCM_IV_LENGTH) { "ciphertext too short to carry a GCM IV" }
        val iv = ciphertext.copyOfRange(0, GCM_IV_LENGTH)
        val body = ciphertext.copyOfRange(GCM_IV_LENGTH, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    private companion object {
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}

/**
 * [KeyCipher] whose AES key is generated inside (and never exported from) the
 * Android Keystore. The key persists across app reinstalls; destroying it
 * (Settings → fingerprint/keystore reset) permanently loses the ability to
 * decrypt previously stored secrets — [KeystoreDataStore.get] degrades to
 * `null` in that case (logged), which is the documented failure mode.
 */
class KeystoreKeyCipher(
    private val alias: String = DEFAULT_KEY_ALIAS,
) : KeyCipher {
    private val delegate: AesGcmKeyCipher by lazy {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(alias, null) as? SecretKey ?: generateKey()
        AesGcmKeyCipher(key)
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray = delegate.encrypt(plaintext)
    override fun decrypt(ciphertext: ByteArray): ByteArray = delegate.decrypt(ciphertext)

    companion object {
        const val DEFAULT_KEY_ALIAS = "oneasmr_secret_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Encrypted secrets on top of a plain DataStore Preferences file ("secure"),
 * each value base64(AES-GCM IV + ciphertext) under a `secret.<key>` pref.
 *
 * Values are typed convenience wrappers for known secrets; the generic
 * [put]/[get]/[remove] surface serves anything later tasks need. Task 24 uses
 * [SERVER_TOKEN_KEY] via [saveServerToken]/[serverToken] for the kikoeru JWT.
 *
 * A corrupted/tampered/wrong-key payload must never crash readers: [get]
 * catches decrypt failures, logs them and returns `null` (the secret is
 * treated as absent — same contract as `SharedPreferences` returning null).
 */
@Singleton
class KeystoreDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: KeyCipher,
) {
    suspend fun put(key: String, plaintext: String) {
        // java.util.Base64 (API 26+) keeps this class JVM-testable; the
        // android.util variant is not mocked in plain unit tests.
        val payload = Base64.getEncoder().encodeToString(cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8)))
        dataStore.edit { it[stringPreferencesKey(secretKey(key))] = payload }
    }

    suspend fun get(key: String): String? {
        val payload = dataStore.data.first()[stringPreferencesKey(secretKey(key))] ?: return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(payload)), Charsets.UTF_8)
        } catch (e: Exception) {
            // AEADBadTagException (tamper/wrong key), IllegalArgumentException (bad base64)...
            Log.w(TAG, "secret '$key' unreadable (${e.javaClass.simpleName}); treating as absent", e)
            null
        }
    }

    suspend fun remove(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(secretKey(key))) }
    }

    suspend fun saveServerToken(token: String) = put(SERVER_TOKEN_KEY, token)

    suspend fun serverToken(): String? = get(SERVER_TOKEN_KEY)

    companion object {
        const val SERVER_TOKEN_KEY = "server_token"
        private const val TAG = "OneAsmrKeystore"
        private fun secretKey(key: String) = "secret.$key"
    }
}
