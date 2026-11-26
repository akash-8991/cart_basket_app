package com.smartcart.server.me

import com.smartcart.server.Services
import com.smartcart.server.auth.AUTH_USER
import com.smartcart.server.auth.io
import com.smartcart.server.auth.requestContext
import com.smartcart.server.auth.user
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ApiJson
import com.smartcart.server.http.ErrorCode
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable data class Profile(val userId: String, val emailMasked: String, val emailVerified: Boolean, val privacyMode: String, val createdAt: String)
@Serializable data class Consent(val key: String, val granted: Boolean, val version: String, val updatedAt: String)
@Serializable data class ConsentList(val consents: List<Consent>)
@Serializable data class SetConsentRequest(val granted: Boolean, val version: String)
@Serializable data class ActivityEvent(
    val eventId: String, val at: String, val type: String, val summary: String, val provider: String?, val taskId: String?,
    val summaryKey: String?, val summaryArgs: Map<String, String>?,
)
@Serializable data class SetPrivacyModeRequest(val privacyMode: String)
@Serializable data class ActivityPage(val events: List<ActivityEvent>, val nextCursor: String?)
@Serializable data class StepUp(val password: String)
@Serializable data class DeleteDataRequest(val scope: String, val password: String)
@Serializable data class DataRequestAccepted(val requestId: String, val status: String, val message: String, val slaDays: Int?)

private fun Instant.wire() = truncatedTo(ChronoUnit.SECONDS).toString()

/** "priya@example.com" → "p***@e***.com" (contract example: "a***@e***.com"). */
fun maskEmail(email: String): String {
    val local = email.substringBefore('@')
    val domain = email.substringAfter('@')
    val tld = domain.substringAfterLast('.', "")
    return "${local.take(1)}***@${domain.take(1)}***" + if (tld.isNotEmpty()) ".$tld" else ""
}

fun ProfileRow.wire() = Profile(userId, maskEmail(email), emailVerified, privacyMode, createdAt.wire())

private fun ConsentRow.wire() = Consent(key, granted, version, updatedAt.wire())

/**
 * Runs a sensitive POST at most once per Idempotency-Key (24 h). The stored body is the
 * DataRequestAccepted JSON, which holds no secrets; the fingerprint is keyed, as the body has a password.
 */
private suspend fun ApplicationCall.onceOnly(s: Services, scope: String, rawBody: String, block: suspend () -> DataRequestAccepted) {
    val key = request.header("Idempotency-Key")
    if (key == null || key.length !in 16..64) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "Idempotency-Key", "required")
    val fingerprint = s.secrets.hmacHex("idempotency", rawBody)
    val body = when (val claim = io { s.idempotency.claim(scope, key) }) {
        Claim.InProgress -> throw ApiException(ErrorCode.CONFLICT)
        is Claim.Replay -> if (claim.response.requestHash == fingerprint) claim.response.body else throw ApiException(ErrorCode.CONFLICT)
        Claim.Claimed -> try {
            ApiJson.encodeToString(DataRequestAccepted.serializer(), block()).also { io { s.idempotency.complete(scope, key, StoredResponse(fingerprint, 202, it)) } }
        } catch (t: Throwable) {
            io { s.idempotency.release(scope, key) }
            throw t
        }
    }
    respond(HttpStatusCode.Accepted, TextContent(body, ContentType.Application.Json))
}

fun Route.meRoutes(s: Services) = authenticate(AUTH_USER) {
    route("/v1/me") {
        get {
            call.respond(io { s.me.profile(call.user().userId) }.wire())
        }

        put("/privacy-mode") {
            val req = call.receive<SetPrivacyModeRequest>()
            call.respond(io { s.me.setPrivacyMode(call.user().userId, req.privacyMode, call.requestContext(s)) }.wire())
        }

        get("/consents") {
            call.respond(ConsentList(io { s.me.consents(call.user().userId) }.map { it.wire() }))
        }

        put("/consents/{consentKey}") {
            val u = call.user()
            val req = call.receive<SetConsentRequest>()
            val row = io { s.me.setConsent(u.userId, call.parameters["consentKey"]!!, req.granted, req.version, call.requestContext(s)) }
            call.respond(row.wire())
        }

        get("/activity") {
            val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..50 } ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "limit", "out_of_range") } ?: 20
            val page = io { s.me.activity(call.user().userId, limit, call.request.queryParameters["cursor"]) }
            call.respond(ActivityPage(page.items.map { ActivityEvent(it.eventId, it.at.wire(), it.type, it.summary, it.provider, it.taskId, it.summaryKey, it.summaryArgs) }, page.nextCursor))
        }

        post("/data/export") {
            val u = call.user()
            val req = call.receive<StepUp>()
            call.onceOnly(s, "export:${u.userId}", ApiJson.encodeToString(StepUp.serializer(), req)) {
                val r = io { s.me.requestExport(u.userId, req.password, call.requestContext(s)) }
                DataRequestAccepted(r.requestId, r.status, "Your export will be emailed to your registered address.", Limits3.EXPORT_SLA_DAYS)
            }
        }

        post("/data/delete") {
            val u = call.user()
            val req = call.receive<DeleteDataRequest>()
            call.onceOnly(s, "delete:${u.userId}", ApiJson.encodeToString(DeleteDataRequest.serializer(), req)) {
                val r = io { s.me.requestDeletion(u.userId, req.scope, req.password, call.requestContext(s)) }
                DataRequestAccepted(r.requestId, r.status, "Deletion has started.", Limits3.DELETE_SLA_DAYS)
            }
        }
    }
}
