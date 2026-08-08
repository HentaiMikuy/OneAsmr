package com.oneasmr.app.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One configured kikoeru server (plan Task 24 "服务器模式").
 *
 * [id] is the stable config identity — "srv1", "srv2", … — and doubles as the
 * remote source scope for plan Task 4's key spec ("srv{n}:{rjCode}"); it never
 * changes across edits. [baseUrl] is the SERVER ROOT the user typed
 * (scheme://host[:port], no trailing slash, no "/api" — [normalizedBaseUrl]
 * tolerates both) and is never hardcoded anywhere (Task 23 rule).
 *
 * The connection probe learns whether the server enforces auth at runtime
 * (GET /api/auth/me) — it is deliberately NOT persisted here so the UI can
 * never trust a stale auth flag (stale_state guard; re-probe is cheap).
 */
@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
) {
    /** Server root without trailing slash and without a trailing "/api". */
    val normalizedBaseUrl: String
        get() {
            var root = baseUrl.trim().trimEnd('/')
            if (root.endsWith("/api")) root = root.dropLast(4).trimEnd('/')
            return root
        }
}

/** Why a baseUrl was rejected ([ServerStore.addServer]/[updateServer] refuse). */
enum class BaseUrlIssue {
    BLANK,
    NO_SCHEME,
    NO_HOST,
}

/**
 * Pure validation for user-entered server roots — JVM-testable. Accepts
 * http(s) URLs only (kikoeru-express serves plain HTTP on a LAN by default;
 * TLS servers also work). "http://10.0.2.2:9527" and "https://kikoeru.example"
 * pass; "192.168.1.5:9527" (no scheme) and garbage are rejected with the
 * specific [BaseUrlIssue] so the UI can show a precise message.
 */
fun validateBaseUrl(raw: String): BaseUrlIssue? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return BaseUrlIssue.BLANK
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return BaseUrlIssue.NO_SCHEME
    }
    val host = try {
        URI(trimmed).host
    } catch (e: Exception) {
        null
    }
    if (host.isNullOrBlank()) return BaseUrlIssue.NO_HOST
    return null
}

/**
 * Multi-server config list + active-server selection, persisted in a dedicated
 * DataStore Preferences file ("server_config").
 *
 * The whole list round-trips through ONE preference as kotlinx-serialization
 * JSON — the list is tiny and single-key writes are atomic, so add/edit/delete
 * can never leave a half-written list (a corrupt blob degrades to an empty
 * list rather than crashing). [activeServerId] is its own key so switching
 * stays independent of list mutations.
 *
 * Server ids are allocated monotonically ("srv1", "srv2", …) from the current
 * list — removing a server frees its id for reuse, which is fine because ids
 * are only ever scopes for remote keys, never persisted elsewhere.
 */
@Singleton
class ServerStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val servers: Flow<List<ServerConfig>> =
        dataStore.data.map { prefs -> decodeServers(prefs[KEY_SERVERS]) }

    val activeServerId: Flow<String?> =
        dataStore.data.map { prefs -> prefs[KEY_ACTIVE_SERVER_ID] }

    /** The active server, or null when none is configured/selected. */
    val activeServer: Flow<ServerConfig?> =
        combine(servers, activeServerId) { list, id -> list.firstOrNull { it.id == id } }

    suspend fun serversOnce(): List<ServerConfig> = servers.first()

    /**
     * Adds a server and makes it active (the natural "first configure" flow).
     * Returns the created config with its allocated id.
     */
    suspend fun addServer(name: String, baseUrl: String): ServerConfig {
        val raw = ServerConfig(id = nextServerId(), name = name.trim(), baseUrl = baseUrl)
        val server = raw.copy(baseUrl = raw.normalizedBaseUrl)
        dataStore.edit { prefs ->
            val list = decodeServers(prefs[KEY_SERVERS]) + server
            prefs[KEY_SERVERS] = encodeServers(list)
            prefs[KEY_ACTIVE_SERVER_ID] = server.id
        }
        return server
    }

    suspend fun updateServer(id: String, name: String, baseUrl: String) {
        dataStore.edit { prefs ->
            val list = decodeServers(prefs[KEY_SERVERS]).map {
                if (it.id == id) {
                    val raw = it.copy(name = name.trim(), baseUrl = baseUrl)
                    raw.copy(baseUrl = raw.normalizedBaseUrl)
                } else it
            }
            prefs[KEY_SERVERS] = encodeServers(list)
        }
    }

    /** Removes the server; if it was active, no server stays active. */
    suspend fun removeServer(id: String) {
        dataStore.edit { prefs ->
            val list = decodeServers(prefs[KEY_SERVERS]).filterNot { it.id == id }
            prefs[KEY_SERVERS] = encodeServers(list)
            if (prefs[KEY_ACTIVE_SERVER_ID] == id) prefs.remove(KEY_ACTIVE_SERVER_ID)
        }
    }

    suspend fun setActiveServer(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_SERVER_ID) else prefs[KEY_ACTIVE_SERVER_ID] = id
        }
    }

    private suspend fun nextServerId(): String {
        val existing = serversOnce().map { it.id }.toSet()
        var n = 1
        while ("srv$n" in existing) n++
        return "srv$n"
    }

    companion object {
        private val KEY_SERVERS = stringPreferencesKey("servers_json")
        private val KEY_ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
        private val json = Json { ignoreUnknownKeys = true }

        /** Shared encode/decode so tests can assert the exact wire shape. */
        internal fun encodeServers(servers: List<ServerConfig>): String =
            json.encodeToString(servers)

        internal fun decodeServers(stored: String?): List<ServerConfig> =
            if (stored.isNullOrBlank()) emptyList()
            else try {
                json.decodeFromString<List<ServerConfig>>(stored)
            } catch (e: Exception) {
                emptyList()
            }
    }
}
