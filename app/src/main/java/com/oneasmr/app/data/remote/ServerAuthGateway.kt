package com.oneasmr.app.data.remote

import com.oneasmr.app.data.local.settings.ServerConfig

/** Outcome of probing a server's reachability + auth mode. */
sealed interface ConnectionProbe {
    /** Reachable; [authEnabled] is the server's live auth flag (GET /api/auth/me). */
    data class Ok(val authEnabled: Boolean, val user: String, val version: String?) : ConnectionProbe
    data class Failed(val message: String) : ConnectionProbe
}

/** Outcome of a POST /api/auth/me attempt. */
sealed interface LoginOutcome {
    data class Success(val user: String) : LoginOutcome
    /** HTTP 401 — wrong name/password or expired account. */
    data class WrongCredentials(val message: String) : LoginOutcome
    /** Transport/5xx/parse failure — server unreachable or unhealthy. */
    data class Failed(val message: String) : LoginOutcome
}

/** Outcome of validating a stored JWT against the server. */
sealed interface TokenValidation {
    data class Valid(val user: String) : TokenValidation
    /** 401 — the stored token is expired/invalid; the caller must clear it and re-login. */
    data class Expired(val message: String) : TokenValidation
    /** Server unreachable — the token is kept (unknown validity). */
    data class Unreachable(val message: String) : TokenValidation
    data object NoToken : TokenValidation
}

/**
 * Network surface for the server-login flow. Implemented by
 * [ServerAuthRepository]; the ViewModel depends on this interface so unit
 * tests drive the whole state machine with a fake (zero I/O, deterministic —
 * repo flake convention).
 */
interface ServerAuthGateway {
    suspend fun probe(baseUrl: String): ConnectionProbe
    suspend fun login(server: ServerConfig, name: String, password: String): LoginOutcome
    suspend fun validateStoredToken(server: ServerConfig): TokenValidation
    suspend fun clearToken(serverId: String)
}
