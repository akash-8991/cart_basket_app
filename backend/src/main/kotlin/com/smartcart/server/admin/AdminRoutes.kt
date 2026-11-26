package com.smartcart.server.admin

import com.smartcart.server.Services
import com.smartcart.server.audit.RequestContext
import com.smartcart.server.auth.io
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.http.correlationId
import com.smartcart.server.providers.Approval
import com.smartcart.server.providers.ProviderRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable data class AdminFlag(val key: String, val enabled: Boolean, val updatedAt: String, val updatedBy: String, val reason: String)
@Serializable data class AdminFlagList(val flags: List<AdminFlag>)
@Serializable data class SetFlagRequest(val enabled: Boolean, val reason: String)
@Serializable data class ApproveProviderRequest(val adapterVersion: String, val approvedBy: String, val recordUrl: String, val reason: String)
@Serializable data class RevokeApprovalRequest(val reason: String)

/**
 * Operator API (not part of the public contract): flip flags and kill switches, record provider
 * approvals. Exists only when SMARTCART_ADMIN_TOKEN is set; every request names its operator
 * (X-Operator) and every change is audited with a reason. Keep it off the public network.
 */
fun Route.adminRoutes(s: Services) {
    val token = s.config.adminToken ?: return
    route("/admin") {
        get("/flags") {
            call.operator(token)
            call.respond(AdminFlagList(io { s.flags.all() }.values.sortedBy { it.key }.map { AdminFlag(it.key, it.enabled, it.updatedAt.toString(), it.updatedBy, it.reason) }))
        }

        put("/flags/{key}") {
            val op = call.operator(token)
            val key = call.parameters["key"]!!
            val req = call.receive<SetFlagRequest>()
            if (req.reason.isBlank()) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "reason", "required")
            if (!io { s.flags.exists(key) }) throw ApiException(ErrorCode.NOT_FOUND)
            val f = io { s.flags.set(key, req.enabled, op, req.reason) }
            s.audit.record("admin.flag_set", if (req.enabled) "on" else "off", call.adminContext(s), actor = "operator:$op", target = key, detail = mapOf("reason" to req.reason.take(200)))
            call.respond(AdminFlag(f.key, f.enabled, f.updatedAt.toString(), f.updatedBy, f.reason))
        }

        put("/providers/{key}/approval") {
            val op = call.operator(token)
            val key = call.parameters["key"]!!
            ProviderRegistry.find(key) ?: throw ApiException(ErrorCode.NOT_FOUND)
            val req = call.receive<ApproveProviderRequest>()
            if (listOf(req.adapterVersion, req.approvedBy, req.recordUrl, req.reason).any { it.isBlank() }) throw ApiException(ErrorCode.VALIDATION_FAILED)
            io { s.providerApprovals.approve(Approval(key, req.adapterVersion, req.approvedBy, req.recordUrl, s.clock.instant())) }
            s.audit.record("admin.provider_approved", "approved", call.adminContext(s), actor = "operator:$op", target = key,
                detail = mapOf("adapter_version" to req.adapterVersion, "approved_by" to req.approvedBy, "record_url" to req.recordUrl, "reason" to req.reason.take(200)))
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/providers/{key}/approval") {
            val op = call.operator(token)
            val key = call.parameters["key"]!!
            val req = call.receive<RevokeApprovalRequest>()
            if (req.reason.isBlank()) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "reason", "required")
            if (!io { s.providerApprovals.revoke(key, s.clock.instant()) }) throw ApiException(ErrorCode.NOT_FOUND)
            s.audit.record("admin.provider_approval_revoked", "revoked", call.adminContext(s), actor = "operator:$op", target = key, detail = mapOf("reason" to req.reason.take(200)))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Checks the admin token in constant time and returns the operator's name. */
private fun ApplicationCall.operator(token: String): String {
    val presented = request.header("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
    if (!MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())) throw ApiException(ErrorCode.UNAUTHENTICATED)
    val op = request.header("X-Operator")?.trim()
    if (op.isNullOrEmpty() || op.length > 64 || !op.matches(Regex("^[A-Za-z0-9._@-]+$"))) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "X-Operator", "required")
    return op
}

private fun ApplicationCall.adminContext(s: Services) = RequestContext(correlationId(), s.ipHasher.hash(request.origin.remoteHost))
