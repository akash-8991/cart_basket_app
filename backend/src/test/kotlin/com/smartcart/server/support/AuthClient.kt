package com.smartcart.server.support

import com.smartcart.server.auth.AuthSession
import com.smartcart.server.auth.TokenPair
import com.smartcart.server.http.ApiJson
import com.smartcart.server.http.CORRELATION_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.util.UUID

const val PASSWORD = "correct horse battery"

fun device(id: String = "dev-1") = """{"device_id":"$id","platform":"android","app_version":"1.0.0","os_version":"15"}"""

fun registerBody(userId: String = "priya.k", email: String = "priya@example.com", password: String = PASSWORD, deviceId: String = "dev-1") =
    """{"user_id":"$userId","email":"$email","password":"$password","accepted_terms_version":"2026-11","accepted_privacy_version":"2026-11","device":${device(deviceId)}}"""

fun loginBody(userId: String = "priya.k", password: String = PASSWORD, deviceId: String = "dev-1", remember: Boolean = true) =
    """{"user_id":"$userId","password":"$password","device":${device(deviceId)},"remember_device":$remember}"""

suspend fun HttpClient.postJson(path: String, body: String, idempotencyKey: String? = null, token: String? = null): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        header(CORRELATION_HEADER, UUID.randomUUID().toString())
        idempotencyKey?.let { header("Idempotency-Key", it) }
        token?.let { bearerAuth(it) }
        setBody(body)
    }

suspend fun HttpClient.register(userId: String = "priya.k", email: String = "priya@example.com", deviceId: String = "dev-1"): AuthSession {
    val r = postJson("/v1/auth/register", registerBody(userId, email, deviceId = deviceId), idempotencyKey = UUID.randomUUID().toString())
    check(r.status.value == 201) { "register failed: ${r.status} ${r.bodyAsText()}" }
    return ApiJson.decodeFromString(AuthSession.serializer(), r.bodyAsText())
}

suspend fun HttpClient.login(userId: String = "priya.k", password: String = PASSWORD, deviceId: String = "dev-1"): AuthSession {
    val r = postJson("/v1/auth/login", loginBody(userId, password, deviceId))
    check(r.status.value == 200) { "login failed: ${r.status} ${r.bodyAsText()}" }
    return ApiJson.decodeFromString(AuthSession.serializer(), r.bodyAsText())
}

suspend fun HttpResponse.tokenPair(): TokenPair = ApiJson.decodeFromString(TokenPair.serializer(), bodyAsText())

/** The error code in an error envelope, or null. */
suspend fun HttpResponse.errorCode(): String? =
    Regex("\"code\":\"([a-z_]+)\"").find(bodyAsText())?.groupValues?.get(1)

/** Body with the per-request fields (correlation and challenge IDs) blanked, for byte comparisons. */
suspend fun HttpResponse.normalisedBody(): String =
    bodyAsText().replace(Regex("\"(correlation_id|challenge_id)\":\"[^\"]*\""), "\"$1\":\"*\"")

suspend fun HttpClient.putJson(path: String, body: String, token: String): HttpResponse =
    put(path) {
        contentType(ContentType.Application.Json)
        header(CORRELATION_HEADER, UUID.randomUUID().toString())
        bearerAuth(token)
        setBody(body)
    }

suspend fun HttpClient.getAuthed(path: String, token: String): HttpResponse =
    get(path) { bearerAuth(token) }
