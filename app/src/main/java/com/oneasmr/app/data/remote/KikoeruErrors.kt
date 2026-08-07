package com.oneasmr.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Unified error surface for the kikoeru client (Task 23).
 *
 * Mapping table (locked by KikoeruErrorMappingTest):
 *  - HTTP 401                  -> [AuthExpired]  (wrong credentials / expired JWT)
 *  - HTTP 500..599             -> [Server]
 *  - any other HTTP status     -> [HttpError]   (e.g. 404 circle missing, 422 validation)
 *  - malformed 2xx body        -> [Parse]
 *  - transport failure         -> [Network]
 *  - CancellationException     -> rethrown unchanged (never wrapped)
 */
sealed class KikoeruException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 401 — the caller should drop the stored token and re-login (Task 24). */
    class AuthExpired(cause: Throwable? = null) :
        KikoeruException("kikoeru: authentication expired (401)", cause)

    /** DNS/connect/timeout/IO level failure. */
    class Network(cause: Throwable) :
        KikoeruException("kikoeru: network failure", cause)

    /** 5xx server errors. */
    class Server(val code: Int, cause: Throwable? = null) :
        KikoeruException("kikoeru: server error ($code)", cause)

    /** A 2xx body that did not deserialize into the expected DTO shape. */
    class Parse(cause: Throwable) :
        KikoeruException("kikoeru: malformed response body", cause)

    /** The server answered but rejected the request (404/422/3xx...). */
    class HttpError(val code: Int, cause: Throwable? = null) :
        KikoeruException("kikoeru: http $code", cause)
}

/** Pure mapping function — unit-tested without any network (error mapping table). */
object KikoeruErrorMapper {

    fun map(t: Throwable): KikoeruException = when (t) {
        is KikoeruException -> t
        is CancellationException -> throw t
        is HttpException -> when (t.code()) {
            401 -> KikoeruException.AuthExpired(t)
            in 500..599 -> KikoeruException.Server(t.code(), t)
            else -> KikoeruException.HttpError(t.code(), t)
        }
        is SerializationException -> KikoeruException.Parse(t)
        // IOException covers connect refusal, timeouts, DNS and socket errors.
        is IOException -> KikoeruException.Network(t)
        else -> KikoeruException.Network(t)
    }
}
