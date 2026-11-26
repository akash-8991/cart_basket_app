package com.smartcart.server.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors contracts/error-codes.yaml (and the OpenAPI ErrorCode enum); ErrorCatalogueTest keeps the
 * three in step. [message] is a user-safe default — the app shows its own copy keyed by code.
 */
enum class ErrorCode(val wire: String, val status: HttpStatusCode, val message: String) {
    VALIDATION_FAILED("validation_failed", HttpStatusCode.BadRequest, "Some details need fixing."),
    UNAUTHENTICATED("unauthenticated", HttpStatusCode.Unauthorized, "Please sign in."),
    SESSION_EXPIRED("session_expired", HttpStatusCode.Unauthorized, "Your session has expired."),
    FORBIDDEN("forbidden", HttpStatusCode.Forbidden, "You can't do that."),
    NOT_FOUND("not_found", HttpStatusCode.NotFound, "Not found."),
    CONFLICT("conflict", HttpStatusCode.Conflict, "This changed in the meantime."),
    USER_ID_UNAVAILABLE("user_id_unavailable", HttpStatusCode.Conflict, "That user ID isn't available."),
    WEAK_PASSWORD("weak_password", HttpStatusCode.BadRequest, "Choose a stronger password."),
    INVALID_CREDENTIALS("invalid_credentials", HttpStatusCode.Unauthorized, "User ID or password is incorrect."),
    ACCOUNT_LOCKED("account_locked", HttpStatusCode.TooManyRequests, "Too many attempts. Try again later."),
    CHALLENGE_EXPIRED("challenge_expired", HttpStatusCode.Gone, "This link has expired."),
    RATE_LIMITED("rate_limited", HttpStatusCode.TooManyRequests, "Too many requests. Try again later."),
    QUOTE_EXPIRED("quote_expired", HttpStatusCode.Gone, "Prices have expired."),
    PRICE_CHANGED("price_changed", HttpStatusCode.Conflict, "Prices have changed."),
    PROVIDER_DISABLED("provider_disabled", HttpStatusCode.Forbidden, "This app isn't available right now."),
    CONSENT_REQUIRED("consent_required", HttpStatusCode.Forbidden, "Your permission is needed first."),
    TASK_EXPIRED("task_expired", HttpStatusCode.Gone, "This task has expired."),
    TASK_STATE_CONFLICT("task_state_conflict", HttpStatusCode.Conflict, "This task has moved on."),
    POLICY_BLOCKED("policy_blocked", HttpStatusCode.Forbidden, "This step isn't allowed."),
    AI_UNAVAILABLE("ai_unavailable", HttpStatusCode.ServiceUnavailable, "The assistant is unavailable."),
    SERVICE_UNAVAILABLE("service_unavailable", HttpStatusCode.ServiceUnavailable, "Service unavailable. Try again."),
    INTERNAL_ERROR("internal_error", HttpStatusCode.InternalServerError, "Something went wrong. Try again."),
}

@Serializable
data class FieldError(val field: String, val code: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val correlationId: String,
    val retryAfterSeconds: Int? = null,
    // Contract: an array when present, never null — so omit it rather than write null.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fieldErrors: List<FieldError>? = null,
)

@Serializable
data class ErrorEnvelope(@SerialName("error") val error: ErrorBody)

/** Thrown by handlers; StatusPages renders it as an [ErrorEnvelope] with the code's HTTP status. */
class ApiException(
    val code: ErrorCode,
    val fieldErrors: List<FieldError>? = null,
    val retryAfterSeconds: Int? = null,
    /** Set for rate-limit refusals: rendered as RateLimit-* headers. */
    val rateLimit: com.smartcart.server.security.Decision? = null,
    message: String? = null,
) : RuntimeException(message ?: code.wire) {
    companion object {
        fun field(code: ErrorCode, field: String, fieldCode: String = code.wire) = ApiException(code, listOf(FieldError(field, fieldCode)))
        fun limited(code: ErrorCode, d: com.smartcart.server.security.Decision) = ApiException(code, retryAfterSeconds = d.retryAfterSeconds, rateLimit = d)
    }
}
