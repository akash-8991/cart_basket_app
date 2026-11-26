package com.smartcart.server.auth

import com.smartcart.server.audit.Audit
import com.smartcart.server.audit.RequestContext
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.mail.MailTemplate
import com.smartcart.server.mail.Mailer
import com.smartcart.server.security.PasswordPolicy
import com.smartcart.server.security.Passwords
import com.smartcart.server.security.RateLimiter
import com.smartcart.server.security.Secrets
import com.smartcart.server.security.Tokens
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class IssuedSession(val userId: String, val sessionId: String, val accessToken: String, val refreshToken: String, val expiresAt: Instant, val emailVerificationRequired: Boolean)

data class IssuedTokens(val accessToken: String, val refreshToken: String)

/** Rate-limit and lockout settings (threat model A-01, A-02). */
object Limits {
    val LOGIN_PER_IP = 30 to Duration.ofMinutes(10)
    val LOGIN_PER_DEVICE = 20 to Duration.ofMinutes(10)
    const val FAILURES_BEFORE_LOCK = 5
    val FAILURE_WINDOW: Duration = Duration.ofMinutes(15)
    val FIRST_LOCK: Duration = Duration.ofSeconds(30)
    val MAX_LOCK: Duration = Duration.ofMinutes(15)
    val REGISTER_PER_IP = 10 to Duration.ofHours(1)
    val RECOVERY_PER_IP = 5 to Duration.ofMinutes(30)
    val RECOVERY_PER_TARGET = 3 to Duration.ofHours(1)
    val RESET_PER_IP = 10 to Duration.ofMinutes(30)
    val REFRESH_PER_DEVICE = 60 to Duration.ofMinutes(10)
    const val CODE_ATTEMPTS = 5
    val CHALLENGE_TTL: Duration = Duration.ofMinutes(15)
    val REMEMBERED_SESSION: Duration = Duration.ofDays(30)
    val SHORT_SESSION: Duration = Duration.ofHours(12)
}

class AuthService(
    private val repo: AuthRepository,
    private val passwords: Passwords,
    private val tokens: Tokens,
    private val limiter: RateLimiter,
    private val secrets: Secrets,
    private val mailer: Mailer,
    private val audit: Audit,
    private val clock: Clock,
) {
    // ------------------------------------------------------------------ register / login

    fun register(userId: String, email: String, password: String, terms: String, privacy: String, device: Device, ctx: RequestContext): IssuedSession {
        limit("register:ip:${ctx.ipHash}", Limits.REGISTER_PER_IP)
        if (PasswordPolicy.isWeak(password, userId, email)) throw ApiException.field(ErrorCode.WEAK_PASSWORD, "password")
        if (!repo.createUser(userId, email, passwords.hash(password), terms, privacy)) {
            audit.record("auth.register", "user_id_unavailable", ctx, target = userId)
            throw ApiException.field(ErrorCode.USER_ID_UNAVAILABLE, "user_id")
        }
        audit.record("auth.register", "success", ctx, actor = userId)
        return openSession(repo.findUser(userId)!!, device, remembered = true, ctx)
    }

    /** New session for an account created by an earlier attempt with the same Idempotency-Key and body. */
    fun sessionForReplayedRegistration(userId: String, device: Device, ctx: RequestContext): IssuedSession =
        openSession(repo.findUser(userId) ?: throw ApiException(ErrorCode.CONFLICT), device, remembered = true, ctx)

    fun login(userId: String, password: String, device: Device, remember: Boolean, ctx: RequestContext): IssuedSession {
        limit("login:ip:${ctx.ipHash}", Limits.LOGIN_PER_IP)
        limit("login:device:${device.deviceId}", Limits.LOGIN_PER_DEVICE)
        val account = userId.lowercase()
        // Lockout is tracked per submitted user ID whether or not it exists, so it reveals nothing.
        limiter.ttlSeconds("lock:$account").takeIf { it > 0 }?.let { throw ApiException(ErrorCode.ACCOUNT_LOCKED, retryAfterSeconds = it) }

        val user = repo.findUser(account)
        val ok = passwords.verify(password, user?.passwordHash ?: passwords.dummyHash) && user != null
        if (!ok) {
            val failures = limiter.hit("fail:$account", Int.MAX_VALUE, Limits.FAILURE_WINDOW).let { Int.MAX_VALUE - it.remaining }
            if (failures >= Limits.FAILURES_BEFORE_LOCK) {
                val lock = Limits.FIRST_LOCK.multipliedBy(1L shl (failures - Limits.FAILURES_BEFORE_LOCK).coerceAtMost(10)).coerceAtMost(Limits.MAX_LOCK)
                limiter.setFlag("lock:$account", lock)
                audit.record("auth.lockout", "locked", ctx, target = account, detail = mapOf("failures" to failures.toString(), "lock_seconds" to lock.seconds.toString()))
            }
            audit.record("auth.login", "invalid_credentials", ctx, target = account)
            throw ApiException(ErrorCode.INVALID_CREDENTIALS)
        }
        limiter.reset("fail:$account")
        audit.record("auth.login", "success", ctx, actor = user!!.userId)
        return openSession(user, device, remember, ctx)
    }

    private fun openSession(user: User, device: Device, remembered: Boolean, ctx: RequestContext): IssuedSession {
        val now = clock.instant()
        val sessionId = tokens.newId("ses")
        val refresh = tokens.newRefreshToken()
        val expires = now.plus(if (remembered) Limits.REMEMBERED_SESSION else Limits.SHORT_SESSION)
        repo.createSession(sessionId, user.userId, device, remembered, now, expires, Tokens.hash(refresh))
        audit.record("auth.session_created", "success", ctx, actor = user.userId, target = sessionId)
        return IssuedSession(user.userId, sessionId, tokens.accessToken(user.userId, sessionId), refresh, expires, !user.emailVerified)
    }

    // ------------------------------------------------------------------ refresh / sessions

    fun refresh(refreshToken: String, deviceId: String, ctx: RequestContext): IssuedTokens {
        limit("refresh:device:$deviceId", Limits.REFRESH_PER_DEVICE)
        val now = clock.instant()
        when (val use = repo.useRefreshToken(Tokens.hash(refreshToken), now)) {
            RefreshUse.Unknown -> throw ApiException(ErrorCode.SESSION_EXPIRED)
            is RefreshUse.Reused -> {
                // Someone else holds a copy of this token: end the session everywhere (threat model A-03).
                repo.revokeSession(use.session.userId, use.session.sessionId, "refresh_token_reuse", now)
                audit.record("auth.refresh", "reuse_detected", ctx, actor = use.session.userId, target = use.session.sessionId)
                throw ApiException(ErrorCode.SESSION_EXPIRED)
            }
            is RefreshUse.Fresh -> {
                val s = use.session
                if (!s.isActive(now)) throw ApiException(ErrorCode.SESSION_EXPIRED)
                if (!MessageDigest.isEqual(s.device.deviceId.toByteArray(), deviceId.toByteArray())) {
                    repo.revokeSession(s.userId, s.sessionId, "device_mismatch", now)
                    audit.record("auth.refresh", "device_mismatch", ctx, actor = s.userId, target = s.sessionId)
                    throw ApiException(ErrorCode.SESSION_EXPIRED)
                }
                val next = tokens.newRefreshToken()
                repo.rotate(s.sessionId, Tokens.hash(next), now)
                return IssuedTokens(tokens.accessToken(s.userId, s.sessionId), next)
            }
        }
    }

    /** The session behind an access token, if it is still active; marks it seen. */
    fun activeSession(sessionId: String, userId: String): Session? {
        val now = clock.instant()
        return repo.session(sessionId)?.takeIf { it.userId == userId && it.isActive(now) }?.also { repo.touch(it.sessionId, now) }
    }

    fun logout(userId: String, sessionId: String, ctx: RequestContext) {
        repo.revokeSession(userId, sessionId, "logout", clock.instant())
        audit.record("auth.logout", "success", ctx, actor = userId, target = sessionId)
    }

    fun sessions(userId: String): List<Session> = repo.activeSessions(userId, clock.instant())

    fun revokeSession(userId: String, sessionId: String, ctx: RequestContext) {
        if (!repo.revokeSession(userId, sessionId, "revoked_by_user", clock.instant())) throw ApiException(ErrorCode.NOT_FOUND)
        audit.record("auth.session_revoked", "success", ctx, actor = userId, target = sessionId)
    }

    fun revokeOthers(userId: String, currentSessionId: String, ctx: RequestContext): Int =
        repo.revokeAll(userId, currentSessionId, "revoke_all", clock.instant()).also {
            audit.record("auth.sessions_revoked_all", "success", ctx, actor = userId, detail = mapOf("count" to it.toString()))
        }

    // ------------------------------------------------------------------ recovery

    /** Always returns a challenge ID; only a matching user ID + email gets a code by email (A-02). */
    fun forgotPassword(userId: String, email: String, ctx: RequestContext): String {
        limit("recovery:ip:${ctx.ipHash}", Limits.RECOVERY_PER_IP)
        limit("recovery:target:${userId.lowercase()}", Limits.RECOVERY_PER_TARGET)
        val now = clock.instant()
        val challengeId = secrets.signedChallengeId(now)
        val user = repo.findUser(userId.lowercase())
        if (user != null && user.email.equals(email, ignoreCase = true)) {
            val code = tokens.newCode()
            repo.createChallenge(challengeId, user.userId, codeHash(challengeId, code), now, now.plus(Limits.CHALLENGE_TTL))
            mailer.send(user.email, MailTemplate.PASSWORD_RESET_CODE, mapOf("code" to code, "user_id" to user.userId))
            audit.record("auth.recovery_requested", "sent", ctx, target = user.userId)
        } else {
            audit.record("auth.recovery_requested", "no_match", ctx)
        }
        return challengeId
    }

    fun forgotUserId(email: String, ctx: RequestContext): String {
        limit("recovery:ip:${ctx.ipHash}", Limits.RECOVERY_PER_IP)
        limit("recovery:target:${email.lowercase()}", Limits.RECOVERY_PER_TARGET)
        val ids = repo.userIdsForEmail(email)
        if (ids.isNotEmpty()) mailer.send(email, MailTemplate.USER_ID_REMINDER, mapOf("user_ids" to ids.joinToString(", ")))
        audit.record("auth.user_id_reminder", if (ids.isEmpty()) "no_match" else "sent", ctx)
        return secrets.signedChallengeId(clock.instant())
    }

    /**
     * Real and made-up challenge IDs behave the same: both expire 15 minutes after issue (time is signed
     * into the ID), both lock after 5 wrong codes, both answer a wrong code identically.
     */
    fun resetPassword(challengeId: String, code: String, newPassword: String, ctx: RequestContext) {
        limit("reset:ip:${ctx.ipHash}", Limits.RESET_PER_IP)
        val now = clock.instant()
        val issuedAt = secrets.challengeIssuedAt(challengeId) ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "code", "invalid_code")
        if (!issuedAt.plus(Limits.CHALLENGE_TTL).isAfter(now)) throw ApiException(ErrorCode.CHALLENGE_EXPIRED)
        val attemptKey = "challenge:$challengeId"
        if (limiter.peek(attemptKey) >= Limits.CODE_ATTEMPTS) throw ApiException(ErrorCode.CHALLENGE_EXPIRED)

        val ch = repo.challenge(challengeId)
        if (ch?.consumedAt != null) throw ApiException(ErrorCode.CHALLENGE_EXPIRED)
        val correct = ch != null && MessageDigest.isEqual(ch.codeHash, codeHash(challengeId, code))
        if (!correct) {
            limiter.hit(attemptKey, Limits.CODE_ATTEMPTS, Limits.CHALLENGE_TTL)
            ch?.let { repo.recordWrongCode(it.challengeId) }
            audit.record("auth.password_reset", "wrong_code", ctx)
            throw ApiException.field(ErrorCode.VALIDATION_FAILED, "code", "invalid_code")
        }
        val user = repo.findUser(ch!!.userId)!!
        if (PasswordPolicy.isWeak(newPassword, user.userId, user.email)) throw ApiException.field(ErrorCode.WEAK_PASSWORD, "new_password")
        if (!repo.resetPassword(challengeId, user.userId, passwords.hash(newPassword), now)) throw ApiException(ErrorCode.CHALLENGE_EXPIRED)
        val revoked = repo.revokeAll(user.userId, null, "password_reset", now)
        limiter.reset("lock:${user.userId}"); limiter.reset("fail:${user.userId}")
        audit.record("auth.password_reset", "success", ctx, actor = user.userId, detail = mapOf("sessions_revoked" to revoked.toString()))
    }

    private fun codeHash(challengeId: String, code: String) = secrets.hmac("recovery-code", "$challengeId:$code")

    private fun limit(key: String, rule: Pair<Int, Duration>) {
        val d = limiter.hit(key, rule.first, rule.second)
        if (!d.allowed) throw ApiException.limited(ErrorCode.RATE_LIMITED, d)
    }
}
