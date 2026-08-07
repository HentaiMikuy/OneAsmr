package com.oneasmr.app.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oneasmr.app.data.local.TestDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Locks the ScanRootRepository bookkeeping with fakes for the Android-only
 * bits ([ScanRootPermissionStore], [RootDisplayNameResolver]) — grant flow,
 * dedupe, refusal handling, removal, and above all: revoked grants are
 * surfaced as REVOKED and never silently dropped.
 */
class ScanRootRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakePermissionStore(
        initialPersisted: Set<String> = emptySet(),
        var grantRefuses: Boolean = false,
    ) : ScanRootPermissionStore {
        val persisted = initialPersisted.toMutableSet()
        val released = mutableListOf<String>()
        override fun takePersistable(treeUri: String): Boolean {
            if (grantRefuses) return false
            persisted += treeUri
            return true
        }

        override fun releasePersistable(treeUri: String) {
            released += treeUri
            persisted -= treeUri
        }

        override fun persistedTreeUris(): Set<String> = persisted.toSet()
    }

    private class FakeResolver : RootDisplayNameResolver {
        override fun resolve(treeUri: String, fallback: String): String =
            treeUri.substringAfterLast('/').ifEmpty { fallback }
    }

    private val TREE_URI_A = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    private val TREE_URI_B = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    private fun buildRepo(
        dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
        permissionStore: ScanRootPermissionStore = FakePermissionStore(),
        resolver: RootDisplayNameResolver = FakeResolver(),
    ): ScanRootRepository =
        ScanRootRepository(
            permissionStore = permissionStore,
            rootsStore = ScanRootsStore(dataStore),
            displayNameResolver = resolver,
        )

    private fun file(name: String) = TestDataStoreFile(tmp.newFile("$name.preferences_pb"))

    /** Waits until [entries] satisfies [predicate] (DataStore/combine emission is async). */
    private suspend fun awaitEntries(
        repo: ScanRootRepository,
        predicate: (List<ScanRootEntry>) -> Boolean,
    ): List<ScanRootEntry> {
        withTimeout(5_000) { repo.entries.map { it }.first { predicate(it) } }
        return repo.entries.value
    }

    private suspend fun awaitSize(repo: ScanRootRepository, size: Int): List<ScanRootEntry> =
        awaitEntries(repo) { it.size == size }

    @Test
    fun `add grants persistable permission and stores the root authorized`() = runBlocking {
        val permissionStore = FakePermissionStore()
        val repo = buildRepo(file("add").open(), permissionStore)

        assertEquals(AddRootResult.OK, repo.addRoot(TREE_URI_A, null))
        val entry = awaitEntries(repo) { it.size == 1 && it.single().status == RootGrantStatus.AUTHORIZED }.single()
        assertEquals(TREE_URI_A, entry.root.treeUri)
        assertEquals("primary%3AMusic", entry.root.displayName)
        assertEquals(RootGrantStatus.AUTHORIZED, entry.status)
        assertTrue(TREE_URI_A in permissionStore.persisted)
    }

    @Test
    fun `duplicate add is rejected`() = runBlocking {
        val repo = buildRepo(file("dup").open())
        repo.addRoot(TREE_URI_A, null)
        assertEquals(AddRootResult.ALREADY_EXISTS, repo.addRoot(TREE_URI_A, null))
        assertEquals(1, awaitSize(repo, 1).size)
    }

    @Test
    fun `provider refusal leaves no entry behind`() = runBlocking {
        val permissionStore = FakePermissionStore(grantRefuses = true)
        val repo = buildRepo(file("refuse").open(), permissionStore)

        assertEquals(AddRootResult.PERMISSION_DENIED, repo.addRoot(TREE_URI_A, null))
        assertTrue(awaitSize(repo, 0).isEmpty())
        assertTrue(permissionStore.persisted.isEmpty())
    }

    @Test
    fun `remove drops the entry and releases the system permission`() = runBlocking {
        val permissionStore = FakePermissionStore()
        val repo = buildRepo(file("remove").open(), permissionStore)
        repo.addRoot(TREE_URI_A, null)

        repo.removeRoot(TREE_URI_A)

        assertTrue(awaitSize(repo, 0).isEmpty())
        assertEquals(listOf(TREE_URI_A), permissionStore.released)
        assertTrue(permissionStore.persisted.isEmpty())
    }

    @Test
    fun `system-side revocation marks entry REVOKED and never drops it`() = runBlocking {
        val permissionStore = FakePermissionStore()
        val repo = buildRepo(file("revoke").open(), permissionStore)
        repo.addRoot(TREE_URI_A, null)
        assertEquals(RootGrantStatus.AUTHORIZED, awaitSize(repo, 1).single().status)

        // User revokes the grant in system settings (outside the app).
        permissionStore.persisted -= TREE_URI_A
        repo.refreshValidation()

        val entry = awaitEntries(repo) { it.size == 1 && it.single().status == RootGrantStatus.REVOKED }.single()
        assertEquals(TREE_URI_A, entry.root.treeUri)
        assertEquals(RootGrantStatus.REVOKED, entry.status)
    }

    @Test
    fun `partial revocation marks only the revoked entry`() = runBlocking {
        val permissionStore = FakePermissionStore()
        val repo = buildRepo(file("partial").open(), permissionStore)
        repo.addRoot(TREE_URI_A, null)
        repo.addRoot(TREE_URI_B, null)

        permissionStore.persisted -= TREE_URI_B
        repo.refreshValidation()

        val entries = awaitEntries(repo) { e -> e.size == 2 && e.first { it.root.treeUri == TREE_URI_B }.status == RootGrantStatus.REVOKED }
            .associateBy { it.root.treeUri }
        assertEquals(RootGrantStatus.AUTHORIZED, entries.getValue(TREE_URI_A).status)
        assertEquals(RootGrantStatus.REVOKED, entries.getValue(TREE_URI_B).status)
    }

    @Test
    fun `roots and grant state survive a repository restart`() = runBlocking {
        val tf = file("restart")
        val permissionStore = FakePermissionStore()
        buildRepo(tf.open(), permissionStore).addRoot(TREE_URI_A, null)

        // New repository over the same store file + same system permission
        // state == process restart; the entry must come back AUTHORIZED.
        val restarted = buildRepo(tf.restart(), permissionStore)
        val entry = awaitEntries(restarted) { it.size == 1 && it.single().status == RootGrantStatus.AUTHORIZED }.single()
        assertEquals(TREE_URI_A, entry.root.treeUri)
        assertEquals(RootGrantStatus.AUTHORIZED, entry.status)
    }

    @Test
    fun `corrupt roots json degrades to empty list without crashing`() = runBlocking {
        val tf = file("corrupt")
        val dataStore = tf.open()
        dataStore.edit { it[stringPreferencesKey("roots_json")] = "{not json" }
        val repo = buildRepo(dataStore)
        assertTrue(awaitSize(repo, 0).isEmpty())
    }
}
