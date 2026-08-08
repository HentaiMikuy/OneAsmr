package com.oneasmr.app.data.remote

import com.oneasmr.app.data.local.keystore.KeystoreDataStore
import com.oneasmr.app.data.local.settings.ServerConfig
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Owns per-server [KikoeruApi] instances and their JWTs (plan Task 24).
 *
 * One [KikoeruApi] per server id, cached for the process lifetime; its
 * tokenProvider reads the per-server token from an in-memory cache that
 * mirrors the encrypted [KeystoreDataStore] slot (loaded at startup, written
 * on login, cleared on logout/expiry). The cache keeps the Retrofit
 * interceptor synchronous — no runBlocking in a request path. Each server's
 * token is its own encrypted secret, so switching servers never leaks tokens
 * across scopes; tokens are never logged and never leave the process in
 * plaintext.
 */
@Singleton
class ServerSession @Inject constructor(
    private val keystore: KeystoreDataStore,
    @Named(ServerModule.SERVER_HTTP) private val client: OkHttpClient,
) {
    private val apis = ConcurrentHashMap<String, KikoeruApi>()
    private val tokenCache = ConcurrentHashMap<String, String>()
    private val tokenMutex = Mutex()

    fun apiFor(server: ServerConfig): KikoeruApi = apis.computeIfAbsent(server.id) {
        KikoeruApi(server.normalizedBaseUrl, tokenProvider = { tokenCache[server.id] }, client = client)
    }

    /** A throwaway client for probing an UNSAVED baseUrl (no token, not cached). */
    fun tempApi(baseUrl: String): KikoeruApi = KikoeruApi(baseUrl, client = client)

    suspend fun loadToken(serverId: String): String? = tokenMutex.withLock {
        keystore.serverToken(serverId)?.also { tokenCache[serverId] = it }
    }

    suspend fun saveToken(serverId: String, token: String) = tokenMutex.withLock {
        keystore.saveServerToken(serverId, token)
        tokenCache[serverId] = token
    }

    suspend fun clearToken(serverId: String) = tokenMutex.withLock {
        keystore.removeServerToken(serverId)
        tokenCache.remove(serverId)
        Unit
    }
}
