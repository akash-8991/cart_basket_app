package com.smartcart.server.auth

import com.smartcart.server.Services
import com.smartcart.server.audit.RequestContext
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ApiJson
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.http.correlationId
import com.smartcart.server.http.respondError
import com.smartcart.server.security.Claim
import com.smartcart.server.security.StoredResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.temporal.ChronoUnit

const val AUTH_USER = "user"

/** Authenticated caller: the user and the device session their access token belongs to. */
data class UserPrincipal(val userId: String, val sessionId: String)

suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

fun ApplicationCall.requestContext(s: Services, deviceId: String? = null) =
    RequestContext(correlationId(), s.ipHasher.hash(request.origin.remoteHost), deviceId)

fun ApplicationCall.user(): UserPrincipal = principal<UserPrincipal>() ?: throw ApiException(ErrorCode.UNAUTHENTICATED)

/**
 * Bearer access tokens (D-018). A token is accepted only while its session is still active, so logout,
 * "sign out other devices" and password reset take effect immediately, not at token expiry.
 */
fun Application.installAuthentication(s: Services) {
    install(Authentication) {
        jwt(AUTH_USER) {
            verifier(s.tokens.verifier)
            validate { cred ->
                val sid = cred.payload.getClaim("sid").asString()
                val sub = cred.payload.subject
                if (sid == null || sub == null) null else io { s.auth.activeSession(sid, sub) }?.let { UserPrincipal(sub, sid) }
            }
            challenge { _, _ ->
                // A presented-but-rejected token means "refresh, then sign in"; no token means "sign in".
                val presented = call.request.header("Authorization")?.startsWith("Bearer ", ignoreCase = true) == true
                call.respondError(if (presented) ErrorCode.SESSION_EXPIRED else ErrorCode.UNAUTHENTICATED)
            }
        }
    }
}

private const val RECOVERY_MESSAGE_PASSWORD = "If the details match an account, we have sent instructions to the registered email."
private const val RECOVERY_MESSAGE_USER_ID = "If the email is registered, we have sent your user ID to it."

private fun IssuedSession.toWire(s: Services) = AuthSession(
    userId = userId,
    sessionId = sessionId,
    tokens = TokenPair(accessToken, refreshToken, s.tokens.accessTtl.seconds.toInt()),
    emailVerificationRequired = emailVerificationRequired,
    sessionExpiresAt = expiresAt.truncatedTo(ChronoUnit.SECONDS).toString(),
)

fun Route.authRoutes(s: Services) = route("/v1/auth") {
    post("/register") {
        val key = call.request.header("Idempotency-Key")
        if (key == null || key.length !in 16..64) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "Idempotency-Key", "required")
        val req = call.receive<RegisterRequest>()
        Validator.validate {
            userId(req.userId); email(req.email); password(req.password)
            nonBlank(req.acceptedTermsVersion, "accepted_terms_version"); nonBlank(req.acceptedPrivacyVersion, "accepted_privacy_version")
            device(req.device)
        }
        val ctx = call.requestContext(s, req.device.deviceId)
        val scope = "register:${req.device.deviceId}"
        val fingerprint = s.secrets.hmacHex("idempotency", ApiJson.encodeToString(RegisterRequest.serializer(), req))
        val session = when (val claim = io { s.idempotency.claim(scope, key) }) {
            Claim.InProgress -> throw ApiException(ErrorCode.CONFLICT)
            // A retry of a registration that already succeeded: a fresh session, never stored tokens.
            is Claim.Replay ->
                if (claim.response.requestHash == fingerprint) io { s.auth.sessionForReplayedRegistration(claim.response.body, req.device.toDevice(), ctx) }
                else throw ApiException(ErrorCode.CONFLICT)
            Claim.Claimed -> try {
                io { s.auth.register(req.userId, req.email, req.password, req.acceptedTermsVersion, req.acceptedPrivacyVersion, req.device.toDevice(), ctx) }
                    .also { io { s.idempotency.complete(scope, key, StoredResponse(fingerprint, 201, it.userId)) } }
            } catch (t: Throwable) {
                io { s.idempotency.release(scope, key) }
                throw t
            }
        }
        call.respond(HttpStatusCode.Created, session.toWire(s))
    }

    post("/login") {
        val req = call.receive<LoginRequest>()
        Validator.validate { nonBlank(req.userId, "user_id", 64); check(req.password.isNotEmpty() && req.password.length <= 128, "password", "invalid_length"); device(req.device) }
        val ctx = call.requestContext(s, req.device.deviceId)
        call.respond(io { s.auth.login(req.userId, req.password, req.device.toDevice(), req.rememberDevice, ctx) }.toWire(s))
    }

    post("/refresh") {
        val req = call.receive<RefreshRequest>()
        Validator.validate { nonBlank(req.refreshToken, "refresh_token", 128); nonBlank(req.deviceId, "device_id", 128) }
        val t = io { s.auth.refresh(req.refreshToken, req.deviceId, call.requestContext(s, req.deviceId)) }
        call.respond(TokenPair(t.accessToken, t.refreshToken, s.tokens.accessTtl.seconds.toInt()))
    }

    post("/forgot-password") {
        val req = call.receive<ForgotPasswordRequest>()
        Validator.validate { nonBlank(req.userId, "user_id", 64); email(req.email) }
        val id = io { s.auth.forgotPassword(req.userId, req.email, call.requestContext(s)) }
        call.respond(HttpStatusCode.Accepted, RecoveryAccepted(RECOVERY_MESSAGE_PASSWORD, id, Limits.CHALLENGE_TTL.seconds.toInt()))
    }

    post("/forgot-user-id") {
        val req = call.receive<ForgotUserIdRequest>()
        Validator.validate { email(req.email) }
        val id = io { s.auth.forgotUserId(req.email, call.requestContext(s)) }
        call.respond(HttpStatusCode.Accepted, RecoveryAccepted(RECOVERY_MESSAGE_USER_ID, id, Limits.CHALLENGE_TTL.seconds.toInt()))
    }

    post("/reset-password") {
        val req = call.receive<ResetPasswordRequest>()
        Validator.validate { nonBlank(req.challengeId, "challenge_id", 128); check(req.code.length in 6..8, "code", "invalid_length"); password(req.newPassword, "new_password") }
        io { s.auth.resetPassword(req.challengeId, req.code, req.newPassword, call.requestContext(s)) }
        call.respond(ResetPasswordResponse(passwordReset = true, allOtherSessionsRevoked = true))
    }

    authenticate(AUTH_USER) {
        post("/logout") {
            val u = call.user()
            io { s.auth.logout(u.userId, u.sessionId, call.requestContext(s)) }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/sessions") {
            val u = call.user()
            val list = io { s.auth.sessions(u.userId) }.map {
                DeviceSession(
                    sessionId = it.sessionId,
                    deviceLabel = it.device.osVersion?.let { os -> "Android $os" } ?: "Android device",
                    platform = it.device.platform,
                    appVersion = it.device.appVersion,
                    approximateLocation = null, // no IP geolocation in Phase 3
                    lastSeenAt = it.lastSeenAt.truncatedTo(ChronoUnit.SECONDS).toString(),
                    current = it.sessionId == u.sessionId,
                )
            }
            call.respond(SessionList(list))
        }

        delete("/sessions/{sessionId}") {
            val u = call.user()
            io { s.auth.revokeSession(u.userId, call.parameters["sessionId"]!!, call.requestContext(s)) }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/sessions/revoke-all") {
            val u = call.user()
            call.respond(RevokeAllResponse(io { s.auth.revokeOthers(u.userId, u.sessionId, call.requestContext(s)) }))
        }
    }
}
