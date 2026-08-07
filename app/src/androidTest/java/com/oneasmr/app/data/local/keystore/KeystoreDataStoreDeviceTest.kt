package com.oneasmr.app.data.local.keystore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device probe for the REAL Android Keystore path: [KeystoreKeyCipher] (key
 * generated inside AndroidKeyStore) plus the [KeystoreDataStore] wiring on a
 * device DataStore file. JVM tests cannot load AndroidKeyStore, so this is
 * the only place the production cipher is exercised.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreDataStoreDeviceTest {

    @Test
    fun keystoreCipherRoundTripsOnDevice() {
        val cipher = KeystoreKeyCipher()
        val plaintext = "device-secret-RJ123456"
        val ciphertext = cipher.encrypt(plaintext.toByteArray())
        assertEquals(plaintext, String(cipher.decrypt(ciphertext)))
    }

    @Test
    fun keystoreKeySurvivesAColdRecreation() {
        // A second cipher instance (fresh KeyStore load) must recover the
        // SAME key by alias — otherwise restarts would lose all secrets.
        val encrypted = KeystoreKeyCipher().encrypt("persist-me".toByteArray())
        assertEquals("persist-me", String(KeystoreKeyCipher().decrypt(encrypted)))
    }

    @Test
    fun storeRoundTripWithRealKeystoreCipher() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val file = context.preferencesDataStoreFile("secure_device_probe")
            file.delete()
            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(produceFile = { file })
            val store = KeystoreDataStore(dataStore, KeystoreKeyCipher())

            store.put("probe_key", "hello")
            assertEquals("hello", store.get("probe_key"))
            store.remove("probe_key")
            assertNull(store.get("probe_key"))
            file.delete()
        }
    }
}
