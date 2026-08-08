package com.oneasmr.app.data.remote

import com.oneasmr.app.data.local.settings.ServerConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [ServerAuthGateway] over [KikoeruApi] (Task 23 client).
 *
 * Probe contract (mirrors kikoeru-express routes/auth.js @ dd030f3, verified
 * against the Number178 fork docker image v0.6.14-20260502):
 *  - GET /api/health must answer "OK" (text) first — unreachable servers fail
 *    here with a Network/Server error mapped to a friendly message.
 *  - GET /api/version is BEST-EFFORT only: the fork serves it auth-gated
 *    (401 without a token), so a failure is skipped, never fatal.
 *  - GET /api/auth/me WITHOUT a token discriminates the auth mode:
 *      auth disabled -> 200 {user:{name:"admin",group:"administrator"},auth:false}
 *      auth enabled  -> 401 (KikoeruErrorMapper -> AuthExpired)
 *    so AuthExpired on the bare probe MEANS the server enforces login.
 *
 * Error mapping (KikoeruErrors.kt): 401->AuthExpired, 5xx->Server,
 * transport->Network; each surfaces as a precise user-facing message.
 * Tokens are persisted via [ServerSession] (encrypted KeystoreDataStore)
 * and are never logged here or anywhere else.
 */
@Singleton
class ServerAuthRepository @Inject constructor(
    private val session: ServerSession,
) : ServerAuthGateway {

    override suspend fun probe(baseUrl: String): ConnectionProbe {
        val api = session.tempApi(baseUrl)
        try {
            api.health()
        } catch (e: KikoeruException) {
            return ConnectionProbe.Failed(failureMessage(e))
        }
        var version: String? = null
        try {
            version = api.version().current
        } catch (e: KikoeruException) {
            // version is optional on every kikoeru variant; never fatal.
        }
        return try {
            val me = api.currentUser()
            ConnectionProbe.Ok(authEnabled = me.auth, user = me.user.name, version = version)
        } catch (e: KikoeruException) {
            when (e) {
                // 401 on the bare probe = auth enforced (see contract above).
                is KikoeruException.AuthExpired -> ConnectionProbe.Ok(
                    authEnabled = true,
                    user = "",
                    version = version,
                )
                else -> ConnectionProbe.Failed(failureMessage(e))
            }
        }
    }

    override suspend fun login(server: ServerConfig, name: String, password: String): LoginOutcome {
        return try {
            val response = session.apiFor(server).login(name, password)
            session.saveToken(server.id, response.token)
            LoginOutcome.Success(user = name)
        } catch (e: KikoeruException) {
            when (e) {
                is KikoeruException.AuthExpired -> LoginOutcome.WrongCredentials("用户名或密码错误（401）")
                else -> LoginOutcome.Failed(failureMessage(e))
            }
        }
    }

    override suspend fun validateStoredToken(server: ServerConfig): TokenValidation {
        val token = session.loadToken(server.id) ?: return TokenValidation.NoToken
        return try {
            val me = session.apiFor(server).currentUser()
            TokenValidation.Valid(user = me.user.name)
        } catch (e: KikoeruException) {
            when (e) {
                is KikoeruException.AuthExpired -> {
                    session.clearToken(server.id)
                    TokenValidation.Expired("登录已过期，请重新登录")
                }
                else -> TokenValidation.Unreachable(failureMessage(e))
            }
        }
    }

    override suspend fun clearToken(serverId: String) = session.clearToken(serverId)

    private fun failureMessage(e: KikoeruException): String = when (e) {
        is KikoeruException.Network -> "无法连接服务器（网络错误）"
        is KikoeruException.Server -> "服务器错误（${e.code}）"
        is KikoeruException.Parse -> "服务器响应异常"
        is KikoeruException.HttpError -> "服务器返回错误（${e.code}）"
        is KikoeruException.AuthExpired -> "登录已过期，请重新登录"
    }
}
