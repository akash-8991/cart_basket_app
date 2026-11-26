package com.smartcart.server.stubs

import com.smartcart.server.Services
import com.smartcart.server.auth.AUTH_USER
import com.smartcart.server.auth.io
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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable data class QuoteRequest(
    val category: String,
    val cartLines: List<JsonElement>? = null,
    val ride: JsonElement? = null,
    val eligibleProviders: List<String>,
    val selectedProvider: String? = null,
    val objective: String,
    val chatSessionId: String? = null,
)
@Serializable data class ConfirmQuoteRequest(val provider: String, val acknowledgedSwitch: Boolean)
@Serializable data class CreateCartTaskRequest(val quoteId: String, val provider: String, val taskType: String)
@Serializable data class ApproveCartTaskRequest(val approvedActionIds: List<String>, val approvalNonce: String)
@Serializable data class CheckpointDecision(val checkpointId: String, val decision: String)
@Serializable data class StopCartTaskRequest(val reason: String)
@Serializable data class HandoffRequest(val taskId: String)
@Serializable data class ImportRequest(val providers: List<String>, val fields: List<String>, val uploadMode: String)
@Serializable data class RecommendationFeedback(val useful: Boolean)

private val OBJECTIVES = setOf("lowest_total", "fastest", "best_value", "fewest_providers", "preferred_provider")
private val CATEGORIES = setOf("travel", "food", "grocery", "shopping")
private val IMPORT_FIELDS = setOf("order_date", "items", "quantity", "amounts", "status", "location_label")
private val YEAR_MONTH = Regex("^\\d{4}-(0[1-9]|1[0-2])$")

private fun bad(field: String, code: String = "invalid_value"): Nothing = throw ApiException.field(ErrorCode.VALIDATION_FAILED, field, code)
private fun provider(key: String?, field: String) { if (key != null && ProviderRegistry.find(key) == null) bad(field) }

/** Stubs exist only where enabled (D-020); production answers every Phase 4 operation with service_unavailable. */
private fun ApplicationCall.stubsOrUnavailable(s: Services) {
    if (!s.config.stubsPhase4) throw ApiException(ErrorCode.SERVICE_UNAVAILABLE)
    response.header("X-SmartCart-Stub", "phase4")
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) = respond(status, TextContent(body, ContentType.Application.Json))

/** Idempotency-Key handling for stub POSTs; the remembered body is canned data, never personal data. */
private suspend fun ApplicationCall.onceOnly(s: Services, scope: String, fingerprintOf: String, status: HttpStatusCode, block: suspend () -> JsonObject) {
    val key = request.header("Idempotency-Key")
    if (key == null || key.length !in 16..64) bad("Idempotency-Key", "required")
    val fp = s.secrets.hmacHex("idempotency", fingerprintOf)
    val body = when (val c = io { s.idempotency.claim(scope, key) }) {
        Claim.InProgress -> throw ApiException(ErrorCode.CONFLICT)
        is Claim.Replay -> if (c.response.requestHash == fp) c.response.body else throw ApiException(ErrorCode.CONFLICT)
        Claim.Claimed -> try {
            block().toString().also { io { s.idempotency.complete(scope, key, StoredResponse(fp, status.value, it)) } }
        } catch (t: Throwable) {
            io { s.idempotency.release(scope, key) }
            throw t
        }
    }
    respondJson(status, body)
}

fun Route.stubRoutes(s: Services) = authenticate(AUTH_USER) {
    val st = s.phase4

    // ---------------------------------------------------------------- comparison
    post("/v1/comparison/quotes") {
        call.stubsOrUnavailable(s)
        val u = call.user()
        val req = call.receive<QuoteRequest>()
        if (req.category !in CATEGORIES) bad("category")
        if (req.objective !in OBJECTIVES) bad("objective")
        if (req.eligibleProviders.isEmpty()) bad("eligible_providers", "required")
        req.eligibleProviders.forEach { provider(it, "eligible_providers") }
        provider(req.selectedProvider, "selected_provider")
        call.onceOnly(s, "quote:${u.userId}", ApiJson.encodeToString(QuoteRequest.serializer(), req), HttpStatusCode.Created) {
            io { st.createQuote(u.userId, req.category, req.selectedProvider, req.objective) }
        }
    }
    get("/v1/comparison/quotes/{quoteId}") {
        call.stubsOrUnavailable(s)
        call.respondJson(HttpStatusCode.OK, st.quote(call.user().userId, call.parameters["quoteId"]!!).toString())
    }
    post("/v1/comparison/quotes/{quoteId}/confirm") {
        call.stubsOrUnavailable(s)
        val u = call.user()
        val id = call.parameters["quoteId"]!!
        val req = call.receive<ConfirmQuoteRequest>()
        provider(req.provider, "provider")
        call.onceOnly(s, "confirm:${u.userId}:$id", ApiJson.encodeToString(ConfirmQuoteRequest.serializer(), req), HttpStatusCode.OK) {
            st.confirmQuote(u.userId, id, req.provider, req.acknowledgedSwitch)
        }
    }

    // ---------------------------------------------------------------- cart tasks
    post("/v1/cart-tasks") {
        call.stubsOrUnavailable(s)
        val u = call.user()
        val req = call.receive<CreateCartTaskRequest>()
        provider(req.provider, "provider")
        if (req.taskType !in setOf("add_to_cart", "ride_setup")) bad("task_type")
        call.onceOnly(s, "task:${u.userId}", ApiJson.encodeToString(CreateCartTaskRequest.serializer(), req), HttpStatusCode.Created) {
            io { st.createTask(u.userId, req.quoteId, req.provider, req.taskType) }
        }
    }
    get("/v1/cart-tasks/{taskId}") {
        call.stubsOrUnavailable(s)
        call.respondJson(HttpStatusCode.OK, st.task(call.user().userId, call.parameters["taskId"]!!).toString())
    }
    post("/v1/cart-tasks/{taskId}/approve") {
        call.stubsOrUnavailable(s)
        val u = call.user()
        val id = call.parameters["taskId"]!!
        val req = call.receive<ApproveCartTaskRequest>()
        if (req.approvedActionIds.isEmpty()) bad("approved_action_ids", "required")
        call.onceOnly(s, "approve:${u.userId}:$id", ApiJson.encodeToString(ApproveCartTaskRequest.serializer(), req), HttpStatusCode.OK) {
            st.approve(u.userId, id, req.approvedActionIds, req.approvalNonce)
        }
    }
    post("/v1/cart-tasks/{taskId}/checkpoint") {
        call.stubsOrUnavailable(s)
        val u = call.user()
        val id = call.parameters["taskId"]!!
        val req = call.receive<CheckpointDecision>()
        if (req.decision !in setOf("approve", "reject")) bad("decision")
        call.onceOnly(s, "checkpoint:${u.userId}:$id", ApiJson.encodeToString(CheckpointDecision.serializer(), req), HttpStatusCode.OK) {
            st.checkpoint(u.userId, id, req.checkpointId, req.decision)
        }
    }
    post("/v1/cart-tasks/{taskId}/stop") {
        call.stubsOrUnavailable(s)
        val req = call.receive<StopCartTaskRequest>()
        if (req.reason !in setOf("emergency_stop", "user_cancel", "pause_all")) bad("reason")
        call.respondJson(HttpStatusCode.OK, st.stop(call.user().userId, call.parameters["taskId"]!!).toString())
    }

    // ---------------------------------------------------------------- handoff
    post("/v1/deep-links") {
        call.stubsOrUnavailable(s)
        val u = call.user()
        val req = call.receive<HandoffRequest>()
        call.onceOnly(s, "handoff:${u.userId}", ApiJson.encodeToString(HandoffRequest.serializer(), req), HttpStatusCode.Created) {
            st.handoff(u.userId, req.taskId)
        }
    }

    // ---------------------------------------------------------------- history
    post("/v1/history/import") {
        call.stubsOrUnavailable(s)
        val u = call.user()
        val req = call.receive<ImportRequest>()
        if (req.providers.isEmpty()) bad("providers", "required")
        req.providers.forEach { provider(it, "providers") }
        if (req.fields.any { it !in IMPORT_FIELDS }) bad("fields")
        if (req.uploadMode !in setOf("device_only", "normalized_upload")) bad("upload_mode")
        call.onceOnly(s, "import:${u.userId}", ApiJson.encodeToString(ImportRequest.serializer(), req), HttpStatusCode.Created) {
            io { st.startImport(u.userId, req.providers) }
        }
    }
    get("/v1/history/import/{sessionId}") {
        call.stubsOrUnavailable(s)
        call.respondJson(HttpStatusCode.OK, st.importSession(call.user().userId, call.parameters["sessionId"]!!).toString())
    }
    get("/v1/history/orders") {
        call.stubsOrUnavailable(s)
        val p = call.request.queryParameters["provider"]
        provider(p, "provider")
        call.respondJson(HttpStatusCode.OK, st.orders(call.user().userId, p).toString())
    }
    delete("/v1/history/orders/{orderId}") {
        call.stubsOrUnavailable(s)
        st.deleteOrder(call.user().userId, call.parameters["orderId"]!!)
        call.respond(HttpStatusCode.NoContent)
    }

    // ---------------------------------------------------------------- dashboard & recommendations
    get("/v1/dashboard/summary") {
        call.stubsOrUnavailable(s)
        call.request.queryParameters["month"]?.let { if (!YEAR_MONTH.matches(it)) bad("month", "invalid_format") }
        call.user()
        call.respondJson(HttpStatusCode.OK, st.dashboard().toString())
    }
    get("/v1/dashboard/monthly") {
        call.stubsOrUnavailable(s)
        listOf("from", "to").forEach { k -> call.request.queryParameters[k]?.let { if (!YEAR_MONTH.matches(it)) bad(k, "invalid_format") } }
        provider(call.request.queryParameters["provider"], "provider")
        call.request.queryParameters["category"]?.let { if (it !in CATEGORIES) bad("category") }
        call.user()
        call.respondJson(HttpStatusCode.OK, st.monthly().toString())
    }
    get("/v1/recommendations") {
        call.stubsOrUnavailable(s)
        val c = call.request.queryParameters["category"]?.also { if (it !in CATEGORIES) bad("category") }
        call.user()
        call.respondJson(HttpStatusCode.OK, st.recommendations(c).toString())
    }
    post("/v1/recommendations/{recommendationId}/feedback") {
        call.stubsOrUnavailable(s)
        call.receive<RecommendationFeedback>()
        call.user()
        call.respond(HttpStatusCode.NoContent)
    }
}
