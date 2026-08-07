package com.oneasmr.app.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oneasmr.app.data.local.TestDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
 *
 * The repository's [CoroutineScope] is injected as [TestScope.backgroundScope],
 * which the test scheduler drives (no real-time waits) and cancels when the
 * test ends. The previous fixed 5s real-time timeout timed out under full-suite
 * load (the eager `stateIn` collector was starved on the shared
 * `Dispatchers.Default`), and the never-cancelled repository scope leaked
 * coroutines into the next test class's uncaught-exception handler.
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
        scope: CoroutineScope,
    ): ScanRootRepository =
        ScanRootRepository(
            permissionStore = permissionStore,
            rootsStore = ScanRootsStore(dataStore),
            displayNameResolver = resolver,
            scope = scope,
        )

    private fun file(name: String) = TestDataStoreFile(tmp.newFile("$name.preferences_pb"))

    /**
     * Waits until [entries] satisfies [predicate]. The repository's `stateIn`
     * collector lives in [TestScope.backgroundScope], which the test scheduler
     * executes only while this test coroutine is SUSPENDED (kotlinx-coroutines
     * semantics: `advanceUntilIdle` never runs background work), so await the
     * StateFlow directly — every suspension lets the collector run while
     * DataStore's own IO thread delivers the persisted value. Deterministic,
     * no real-time timeout; runTest's 60s guard covers genuine hangs.
     */
    private suspend fun TestScope.awaitEntries(
        repo: ScanRootRepository,
        predicate: (List<ScanRootEntry>) -> Boolean,
    ): List<ScanRootEntry> {
        repo.entries.first { predicate(it) }
        return repo.entries.value
    }

    private suspend fun TestScope.awaitSize(repo: ScanRootRepository, size: Int): List<ScanRootEntry> =
        awaitEntries(repo) { it.size == size }

    @Test
    fun `add grants persistable permission and stores the root authorized`() = runTest {
        val permissionStore = FakePermissionStore()
        val repo = buildRepo(file("add").open(), permissionStore, scope = backgroundScope)

        assertEquals(AddRootResult.OK, repo.addRoot(TREE_URI_A, null))
        val entry = awaitEntries(repo) { it.size == 1 && it.single().status == RootGrantStatus.AUTHORIZED }.single()
        assertEquals(TREE_URI_A, entry.root.treeUri)
        assertEquals("primary%3AMusic", entry.root.displayName)
        assertEquals(RootGrantStatus.AUTHORIZED, entry.status)
        assertTrue(TREE_URI_A in permissionStore.persisted)
    }

    @Test
    fun `duplicate add is rejected`() = runTest {
        val repo = buildRepo(file("dup").open(), scope = backgroundScope)
        repo.addRoot(TREE_URI_A, null)
        assertEquals(AddRootResult.ALREADY_EXISTS, repo.addRoot(TREE_URI_A, null))
        assertEquals(1, awaitSize(repo, 1).size)
    }

    @Test
    fun `provider refusal leaves no entry behind`() = runTest {
        val permissionStore = FakePermissionStore(grantRefuses = true)
        val repo = buildRepo(file("refuse").open(), permissionStore, scope = backgroundScope)

        assertEquals(AddRootResult.PERMISSION_DENIED, repo.addRoot(TREE_URI_A, null))
        assertTrue(awaitSize(repo, 0).isEmpty())
        assertTrue(permissionStore.persisted.isEmpty())
    }

    @Test
    fun `remove drops the entry and releases the system permission`() = runTest {
        val permissionStore = FakePermissionStore()
        val repo = buildRepo(file("remove").open(), permissionStore, scope = backgroundScope)
        repo.addRoot(TREE_URI_A, null)

        repo.removeRoot(TREE_URI_A)

        assertTrue(awaitSize(repo, 0).isEmpty())
        assertEquals(listOf(TREE_URI_A), permissionStore.released)
        assertTrue(permissionStore.persisted.isEmpty())
    }

    @Test
    fun `system-side revocation marks entry REVOKED and never drops it`() = runTest {
        val permissionStore = FakePermissionStore()
        val repo = buildRepo(file("revoke").open(), permissionStore, scope = backgroundScope)
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
    fun `partial revocation marks only the revoked entry`() = runTest {
        val permissionStore = FakePermissionStore()
        val repo = buildRepo(file("partial").open(), permissionStore, scope = backgroundScope)
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
    fun `roots and grant state survive a repository restart`() = runTest {
        val tf = file("restart")
        val permissionStore = FakePermissionStore()
        // Dedicated scope for the first repository: a still-active stateIn
        // collector would keep the old DataStore's connection open, so the
        // reopened DataStore over the same file would be rejected ("multiple
        // DataStores active for the same file"). Cancel + join the collector
        // BEFORE the simulated restart to release the connection deterministically.
        val firstScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        buildRepo(tf.open(), permissionStore, scope = firstScope).addRoot(TREE_URI_A, null)
        firstScope.cancel()
        firstScope.coroutineContext[Job]!!.join()

        // New repository over the same store file + same system permission
        // state == process restart; the entry must come back AUTHORIZED.
        val restarted = buildRepo(tf.restart(), permissionStore, scope = backgroundScope)
        val entry = awaitEntries(restarted) { it.size == 1 && it.single().status == RootGrantStatus.AUTHORIZED }.single()
        assertEquals(TREE_URI_A, entry.root.treeUri)
        assertEquals(RootGrantStatus.AUTHORIZED, entry.status)
    }

    @Test
    fun `corrupt roots json degrades to empty list without crashing`() = runTest {
        val tf = file("corrupt")
        val dataStore = tf.open()
        dataStore.edit { it[stringPreferencesKey("roots_json")] = "{not json" }
        val repo = buildRepo(dataStore, scope = backgroundScope)
        assertTrue(awaitSize(repo, 0).isEmpty())
    }
}
