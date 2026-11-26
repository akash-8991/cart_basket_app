package com.smartcart.server.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.slf4j.LoggerFactory
import java.util.UUID

const val CORRELATION_HEADER = "X-Correlation-Id"

/** Wire format shared with the app: snake_case, nulls written, unknown request fields rejected. */
@OptIn(ExperimentalSerializationApi::class)
val ApiJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

private val log = LoggerFactory.getLogger("com.smartcart.server.http")
private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

fun ApplicationCall.correlationId(): String = callId ?: "unknown"

fun Application.installHttp() {
    install(ContentNegotiation) { json(ApiJson) }

    install(CallId) {
        // The client's X-Correlation-Id (a UUID) ties app logs to server logs; anything else is replaced.
        retrieveFromHeader(CORRELATION_HEADER)
        generate { UUID.randomUUID().toString() }
        verify { UUID_RE.matches(it) }
        replyToHeader(CORRELATION_HEADER)
    }

    install(CallLogging) {
        callIdMdc("correlation_id")
        filter { !it.request.path().startsWith("/healthz") && !it.request.path().startsWith("/metrics") }
    }

    install(DefaultHeaders) {
        // API responses carry personal data: never cache them anywhere.
        header(HttpHeaders.CacheControl, "no-store")
        header("X-Content-Type-Options", "nosniff")
    }

    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondError(e.code, e.fieldErrors, e.retryAfterSeconds) }
        exception<BadRequestException> { call, _ -> call.respondError(ErrorCode.VALIDATION_FAILED) }
        exception<SerializationException> { call, _ -> call.respondError(ErrorCode.VALIDATION_FAILED) }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respondError(ErrorCode.INTERNAL_ERROR)
        }
        status(HttpStatusCode.NotFound) { call, _ -> call.respondError(ErrorCode.NOT_FOUND) }
        status(HttpStatusCode.MethodNotAllowed) { call, _ -> call.respondError(ErrorCode.NOT_FOUND) }
        status(HttpStatusCode.UnsupportedMediaType) { call, _ -> call.respondError(ErrorCode.VALIDATION_FAILED) }
    }
}

suspend fun ApplicationCall.respondError(code: ErrorCode, fieldErrors: List<FieldError>? = null, retryAfterSeconds: Int? = null) {
    if (retryAfterSeconds != null) response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    val body = ErrorEnvelope(ErrorBody(code.wire, code.message, correlationId(), retryAfterSeconds, fieldErrors))
    respond(code.status, TextContent(ApiJson.encodeToString(ErrorEnvelope.serializer(), body), io.ktor.http.ContentType.Application.Json.withCharset(Charsets.UTF_8)))
}
