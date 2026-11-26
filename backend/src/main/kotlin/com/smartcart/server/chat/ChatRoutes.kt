package com.smartcart.server.chat

import com.smartcart.server.Services
import com.smartcart.server.auth.AUTH_USER
import com.smartcart.server.auth.io
import com.smartcart.server.auth.requestContext
import com.smartcart.server.auth.user
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ApiJson
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.providers.ProviderRegistry
import com.smartcart.server.security.Claim
import com.smartcart.server.security.StoredResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.time.temporal.ChronoUnit

private fun Instant.wire() = truncatedTo(ChronoUnit.SECONDS).toString()

private fun SessionRow.wire() = ChatSession(sessionId, category, title, createdAt.wire(), updatedAt?.wire())

/**
 * Runs a POST once per Idempotency-Key (24 h) and returns the ID of what it created. Only that ID is
 * remembered — never chat text — and callers read the result back from the database, scoped to the user.
 */
private suspend fun ApplicationCall.onceOnly(s: Services, scope: String, fingerprintOf: String, create: suspend () -> String): String {
    val key = request.header("Idempotency-Key")
    if (key == null || key.length !in 16..64) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "Idempotency-Key", "required")
    val fingerprint = s.secrets.hmacHex("idempotency", fingerprintOf)
    return when (val claim = io { s.idempotency.claim(scope, key) }) {
        Claim.InProgress -> throw ApiException(ErrorCode.CONFLICT)
        is Claim.Replay -> if (claim.response.requestHash == fingerprint) claim.response.body else throw ApiException(ErrorCode.CONFLICT)
        Claim.Claimed -> try {
            create().also { id -> io { s.idempotency.complete(scope, key, StoredResponse(fingerprint, 200, id)) } }
        } catch (t: Throwable) {
            io { s.idempotency.release(scope, key) }
            throw t
        }
    }
}

fun Route.chatRoutes(s: Services) = authenticate(AUTH_USER) {
    route("/v1/chat") {
        get("/sessions") {
            val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..50 } ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "limit", "out_of_range") } ?: 20
            val page = io { s.chat.sessions(call.user().userId, limit, call.request.queryParameters["cursor"]) }
            call.respond(ChatSessionPage(page.items.map { it.wire() }, page.nextCursor))
        }

        post("/sessions") {
            val u = call.user()
            val req = call.receive<CreateChatSessionRequest>()
            val id = call.onceOnly(s, "chat-session:${u.userId}", req.category) { io { s.chat.createSession(u.userId, req.category) }.sessionId }
            call.respond(HttpStatusCode.Created, io { s.chat.session(u.userId, id) }.wire())
        }

        get("/sessions/{sessionId}") {
            val (session, messages) = io { s.chat.detail(call.user().userId, call.parameters["sessionId"]!!) }
            call.respond(
                ChatSessionDetail(
                    session.wire(),
                    messages.map { m -> ChatMessage(m.messageId, m.role, m.text, m.createdAt.wire(), m.turn?.let { ApiJson.decodeFromString(AssistantTurn.serializer(), it) }) },
                ),
            )
        }

        delete("/sessions/{sessionId}") {
            io { s.chat.delete(call.user().userId, call.parameters["sessionId"]!!) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/messages") {
            val u = call.user()
            val req = call.receive<ChatMessageRequest>()
            if (req.text.isBlank() || req.text.length > 2000) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "text", "invalid_length")
            if (req.context.installedProviders.any { ProviderRegistry.find(it) == null }) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "context.installed_providers", "invalid_value")
            if (req.context.locale.length !in 2..35 || !Regex("^[A-Z]{3}$").matches(req.context.currency)) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "context", "invalid_format")
            val ctx = call.requestContext(s)
            val messageId = call.onceOnly(s, "chat-message:${u.userId}", ApiJson.encodeToString(ChatMessageRequest.serializer(), req)) {
                io { s.chat.send(u.userId, req, ctx) }
            }
            call.respond(TextContent(io { s.chat.storedTurn(u.userId, messageId) }, ContentType.Application.Json))
        }
    }
}
