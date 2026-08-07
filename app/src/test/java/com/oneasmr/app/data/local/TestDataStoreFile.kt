package com.oneasmr.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * DataStore over a temp file with an owned scope. DataStore refuses two live
 * instances on the same file, so "restart" simulations must cancel the old
 * scope before opening the file again — [restart] does exactly that.
 */
class TestDataStoreFile(private val file: File) {
    private var scope = CoroutineScope(Dispatchers.IO + Job())

    fun open(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    fun restart(): DataStore<Preferences> {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + Job())
        return open()
    }

    fun file(): File = file
}
