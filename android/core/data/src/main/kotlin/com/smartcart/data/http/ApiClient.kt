package com.smartcart.data.http

import com.smartcart.data.ApiJson
import com.smartcart.data.ApiResult
import com.smartcart.model.ApiError
import com.smartcart.model.ErrorCode
import com.smartcart.model.ErrorEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Holds the short-lived access token in memory and knows how to get a new one. The refresh token itself
 * lives in the Keystore-backed TokenStore, never here.
 */
interface SessionTokens {
    val accessToken: String?
    /** Gets a fresh access token with the stored refresh token; false means the user must sign in again. */
    suspend fun refresh(): Boolean
}

/**
 * The one place the app talks HTTP to the backend (Phase 3.7). Every call carries an X-Correlation-Id;
 * calls the contract marks idempotent carry a fresh Idempotency-Key; authenticated calls carry the access
 * token and, on `session_expired`, refresh once (shared by concurrent callers) and retry once. Failures
 * come back as the contract's ApiError, so the UI handles server and network errors the same way.
 */
class ApiClient(
    baseUrl: String,
    private val http: OkHttpClient = defaultHttp(),
) {
    private val base = baseUrl.trimEnd('/')
    private val refreshLock = Mutex()
    var tokens: SessionTokens? = null

    suspend fun <T> call(
        method: String,
        path: String,
        response: KSerializer<T>?,
        body: String? = null,
        authenticated: Boolean = true,
        idempotent: Boolean = false,
    ): ApiResult<T> {
        val idempotencyKey = if (idempotent) UUID.randomUUID().toString() else null // same key on the retry
        val first = send(method, path, body, authenticated, idempotencyKey, response)
        if (authenticated && first is ApiResult.Failure && first.error.code == ErrorCode.SESSION_EXPIRED) {
            val tokenUsed = tokens?.accessToken
            val refreshed = refreshLock.withLock {
                // Another caller may have refreshed while we waited.
                if (tokens?.accessToken != tokenUsed) true else tokens?.refresh() == true
            }
            if (refreshed) return send(method, path, body, true, idempotencyKey, response)
        }
        return first
    }

    private suspend fun <T> send(method: String, path: String, body: String?, authenticated: Boolean, idempotencyKey: String?, response: KSerializer<T>?): ApiResult<T> =
        withContext(Dispatchers.IO) {
            val correlationId = UUID.randomUUID().toString()
            val request = Request.Builder()
                .url(base + path)
                .header("X-Correlation-Id", correlationId)
                .header("Accept", "application/json")
                .apply {
                    if (authenticated) tokens?.accessToken?.let { header("Authorization", "Bearer $it") }
                    idempotencyKey?.let { header("Idempotency-Key", it) }
                }
                .method(method, body?.toRequestBody(JSON) ?: if (method in BODY_METHODS) ByteArray(0).toRequestBody(JSON) else null)
                .build()
            try {
                http.newCall(request).execute().use { r ->
                    val text = r.body.string()
                    when {
                        r.isSuccessful -> {
                            @Suppress("UNCHECKED_CAST")
                            if (response == null || text.isBlank()) ApiResult.Success(Unit as T)
                            else ApiResult.Success(ApiJson.lenient.decodeFromString(response, text))
                        }
                        else -> ApiResult.Failure(parseError(text, r.code, correlationId))
                    }
                }
            } catch (e: IOException) {
                ApiResult.Failure(ApiError(ErrorCode.SERVICE_UNAVAILABLE, "Can't reach SmartCart. Check your connection.", correlationId))
            } catch (e: kotlinx.serialization.SerializationException) {
                ApiResult.Failure(ApiError(ErrorCode.INTERNAL_ERROR, "Unexpected response.", correlationId))
            }
        }

    private fun parseError(text: String, status: Int, correlationId: String): ApiError =
        runCatching { ApiJson.lenient.decodeFromString(ErrorEnvelope.serializer(), text).error }.getOrElse {
            ApiError(if (status >= 500) ErrorCode.SERVICE_UNAVAILABLE else ErrorCode.INTERNAL_ERROR, "Unexpected response.", correlationId)
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // retries are decided here, with the same Idempotency-Key
            .build()
    }
}
