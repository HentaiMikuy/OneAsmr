package com.oneasmr.app.data.local.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oneasmr.app.data.local.TestDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ServerStore multi-server config tests (plan Task 24): add/edit/delete,
 * active-server switching, id allocation, and persistence across a "restart"
 * (new store instance over the same temp file — the DataStore-flake-free
 * equivalent of a process relaunch).
 */
class ServerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String) = TestDataStoreFile(tmp.newFile("$name.preferences_pb"))

    private fun store(ds: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) =
        ServerStore(ds)

    @Test
    fun `add server allocates srv1 and makes it active`() = runTest {
        val tf = file("add")
        val store = store(tf.open())

        val server = store.addServer("NAS", "http://192.168.1.5:9527")

        assertEquals("srv1", server.id)
        assertEquals("http://192.168.1.5:9527", server.baseUrl)
        assertEquals(listOf(server), store.servers.first())
        assertEquals("srv1", store.activeServerId.first())
        assertEquals(server, store.activeServer.first())
    }

    @Test
    fun `baseUrl is normalized on save`() = runTest {
        val tf = file("norm")
        val store = store(tf.open())

        val server = store.addServer("x", " http://host:9527/api/ ")

        assertEquals("http://host:9527", server.baseUrl)
    }

    @Test
    fun `multiple servers get distinct ids and switching works`() = runTest {
        val tf = file("multi")
        val store = store(tf.open())
        val a = store.addServer("A", "http://a:1")
        val b = store.addServer("B", "http://b:2")
        val c = store.addServer("C", "http://c:3")

        assertEquals(listOf("srv1", "srv2", "srv3"), listOf(a.id, b.id, c.id))
        store.setActiveServer(a.id)
        assertEquals("srv1", store.activeServerId.first())
        store.setActiveServer(c.id)
        assertEquals("srv3", store.activeServerId.first())
    }

    @Test
    fun `update keeps the id and rewrites name and url`() = runTest {
        val tf = file("update")
        val store = store(tf.open())
        val server = store.addServer("A", "http://a:1")

        store.updateServer(server.id, "A2", "https://a.example/api")

        val updated = store.servers.first().single()
        assertEquals("srv1", updated.id)
        assertEquals("A2", updated.name)
        assertEquals("https://a.example", updated.baseUrl)
    }

    @Test
    fun `remove drops the config and clears the active pointer`() = runTest {
        val tf = file("remove")
        val store = store(tf.open())
        val server = store.addServer("A", "http://a:1")

        store.removeServer(server.id)

        assertTrue(store.servers.first().isEmpty())
        assertNull(store.activeServerId.first())
        assertNull(store.activeServer.first())
    }

    @Test
    fun `removed server id is reusable by the next add`() = runTest {
        val tf = file("reuse")
        val store = store(tf.open())
        val a = store.addServer("A", "http://a:1")
        store.addServer("B", "http://b:2")
        store.removeServer(a.id)

        // Lowest-free: the freed srv1 is reused by the next add, then growth resumes.
        val c = store.addServer("C", "http://c:3")
        val d = store.addServer("D", "http://d:4")

        assertEquals("srv1", c.id)
        assertEquals("srv3", d.id)
    }

    @Test
    fun `persistence across restart keeps servers and active selection`() = runTest {
        val tf = file("persist")
        val first = store(tf.open())
        first.addServer("A", "http://a:1")
        first.addServer("B", "http://b:2")
        first.setActiveServer("srv2")

        val second = store(tf.restart())
        val servers = second.servers.first()
        assertEquals(2, servers.size)
        assertEquals("srv2", second.activeServerId.first())
        assertEquals("B", second.activeServer.first()?.name)
    }

    @Test
    fun `corrupt stored list degrades to empty instead of crashing`() = runTest {
        val tf = file("corrupt")
        val ds = tf.open()
        ds.edit { it[stringPreferencesKey("servers_json")] = "{{{not json" }

        assertTrue(store(ds).servers.first().isEmpty())
    }

    // ------------------------------------------------------------------
    // pure validation
    // ------------------------------------------------------------------

    @Test
    fun `validateBaseUrl accepts http and https roots`() {
        assertNull(validateBaseUrl("http://10.0.2.2:9527"))
        assertNull(validateBaseUrl("https://kikoeru.example"))
        assertNull(validateBaseUrl("  http://host:9527/api/  "))
    }

    @Test
    fun `validateBaseUrl rejects blank, schemeless and hostless input`() {
        assertEquals(BaseUrlIssue.BLANK, validateBaseUrl(""))
        assertEquals(BaseUrlIssue.BLANK, validateBaseUrl("   "))
        assertEquals(BaseUrlIssue.NO_SCHEME, validateBaseUrl("192.168.1.5:9527"))
        assertEquals(BaseUrlIssue.NO_SCHEME, validateBaseUrl("ftp://host"))
        assertEquals(BaseUrlIssue.NO_HOST, validateBaseUrl("http://"))
        assertEquals(BaseUrlIssue.NO_HOST, validateBaseUrl("http:///path"))
    }

    @Test
    fun `normalizedBaseUrl strips trailing slash and api suffix`() {
        assertEquals("http://host:9527", ServerConfig("srv1", "x", "http://host:9527/").normalizedBaseUrl)
        assertEquals("http://host:9527", ServerConfig("srv1", "x", "http://host:9527/api").normalizedBaseUrl)
        assertEquals("http://host:9527", ServerConfig("srv1", "x", "http://host:9527/api/").normalizedBaseUrl)
        assertEquals("https://a.b", ServerConfig("srv1", "x", "https://a.b/").normalizedBaseUrl)
    }
}
