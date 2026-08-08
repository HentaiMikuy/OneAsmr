package com.oneasmr.app.data.local.keystore

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oneasmr.app.data.local.TestDataStoreFile
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the secret-storage wiring: the crypto core
 * ([AesGcmKeyCipher]) plus [KeystoreDataStore] round-trips over a temp-file
 * DataStore. The Android Keystore-backed [KeystoreKeyCipher] itself is
 * device-verified in androidTest (KeystoreDataStoreDeviceTest) — the JVM can
 * not load AndroidKeyStore.
 */
class KeystoreDataStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cipher(key: SecretKey): KeyCipher = AesGcmKeyCipher(key)

    private fun testKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun storeIn(ds: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>, key: SecretKey) =
        KeystoreDataStore(ds, cipher(key))

    private fun file(name: String) = TestDataStoreFile(tmp.newFile("$name.preferences_pb"))

    @Test
    fun `round trip survives a new store instance over the same file`() = runTest {
        val tf = file("rt")
        val key = testKey()
        storeIn(tf.open(), key).put("server_token", "eyJhbGciOiJIUzI1NiJ9.secret")

        assertEquals("eyJhbGciOiJIUzI1NiJ9.secret", storeIn(tf.restart(), key).get("server_token"))
    }

    @Test
    fun `missing key returns null`() = runTest {
        val store = storeIn(file("missing").open(), testKey())
        assertNull(store.get("never_written"))
        assertNull(store.serverToken())
    }

    @Test
    fun `remove deletes the secret`() = runTest {
        val tf = file("remove")
        val key = testKey()
        val store = storeIn(tf.open(), key)
        store.put("k", "v")
        store.remove("k")
        assertNull(storeIn(tf.restart(), key).get("k"))
    }

    @Test
    fun `encrypted payloads are stored encrypted and keyed by name`() = runTest {
        val tf = file("payload")
        val key = testKey()
        val store = storeIn(tf.open(), key)
        store.put("alpha", "plain-a")
        store.put("beta", "plain-b")

        // Raw file must NOT contain the plaintext (casual-read protection).
        val raw = tf.file().readText()
        assertFalse("plaintext leaked into the DataStore file", raw.contains("plain-a"))
        assertFalse("plaintext leaked into the DataStore file", raw.contains("plain-b"))

        // Different keys under one store must not clobber each other.
        val restarted = storeIn(tf.restart(), key)
        assertEquals("plain-a", restarted.get("alpha"))
        assertEquals("plain-b", restarted.get("beta"))
    }

    @Test
    fun `tampered payload returns null instead of throwing`() = runTest {
        val tf = file("tamper")
        val key = testKey()
        val store = storeIn(tf.open(), key)
        store.put("k", "v")

        // Flip a base64 char inside the stored payload (breaks the GCM tag).
        val keyPref = stringPreferencesKey("secret.k")
        val ds = tf.restart()
        val payload = ds.data.first()[keyPref]!!
        val flipped = payload.toCharArray().also {
            it[payload.length / 2] = if (it[payload.length / 2] == 'A') 'B' else 'A'
        }.concatToString()
        ds.edit { it[keyPref] = flipped }

        assertNull(storeIn(ds, key).get("k"))
    }

    @Test
    fun `garbage payload returns null instead of throwing`() = runTest {
        val tf = file("garbage")
        val ds = tf.open()
        ds.edit { it[stringPreferencesKey("secret.k")] = "!!!! not base64 !!!!" }
        assertNull(storeIn(ds, testKey()).get("k"))
    }

    @Test
    fun `wrong key cannot decrypt`() = runTest {
        val tf = file("wrongkey")
        storeIn(tf.open(), testKey()).put("k", "secret-value")
        // A DIFFERENT key over the same file must not recover the value.
        assertNull(storeIn(tf.restart(), testKey()).get("k"))
    }

    @Test
    fun `server token convenience round trip`() = runTest {
        val tf = file("token")
        val key = testKey()
        val store = storeIn(tf.open(), key)
        store.saveServerToken("jwt.token.payload")
        assertEquals("jwt.token.payload", store.serverToken())
    }

    @Test
    fun `per-server tokens are isolated and survive restart`() = runTest {
        val tf = file("perserver")
        val key = testKey()
        val store = storeIn(tf.open(), key)
        store.saveServerToken("srv1", "token-for-srv1")
        store.saveServerToken("srv2", "token-for-srv2")

        assertEquals("token-for-srv1", store.serverToken("srv1"))
        assertEquals("token-for-srv2", store.serverToken("srv2"))
        assertNull(store.serverToken("srv3"))

        val restarted = storeIn(tf.restart(), key)
        assertEquals("token-for-srv1", restarted.serverToken("srv1"))
        assertEquals("token-for-srv2", restarted.serverToken("srv2"))

        restarted.removeServerToken("srv1")
        assertNull(restarted.serverToken("srv1"))
        assertEquals("token-for-srv2", restarted.serverToken("srv2"))
    }

    @Test
    fun `per-server tokens never appear in plaintext in the store file`() = runTest {
        val tf = file("perserver-plain")
        val key = testKey()
        storeIn(tf.open(), key).saveServerToken("srv1", "super-secret-jwt-value")

        assertFalse(tf.file().readText().contains("super-secret-jwt-value"))
    }

    @Test
    fun `gcm ciphertext differs between runs of the same plaintext`() {
        val key = testKey()
        val c = cipher(key)
        val a = c.encrypt("same".toByteArray())
        val b = c.encrypt("same".toByteArray())
        // Random IV => no two ciphertexts equal; prevents replay/pattern leaks.
        assertFalse(a.contentEquals(b))
        assertEquals("same", String(c.decrypt(a)))
        assertEquals("same", String(c.decrypt(b)))
    }
}
