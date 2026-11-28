package com.smartcart.server.tasks

import com.smartcart.server.security.Secrets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Cart handoff tokens (Phase 4.5, threat model A-08): opaque (no task, order or user data), signed with a
 * server sub-key, valid for at most 10 minutes, bound to one user, one task and one provider app, and usable
 * once. Only a keyed hash of each token is kept, so the store can't be replayed from.
 *
 * A link is redeemed with `POST /v1/deep-links/redeem` (CR-006), which uses [redeem].
 */
class HandoffTokens(private val secrets: Secrets, private val clock: Clock, private val ttl: Duration = TTL) {
    private class Grant(val userId: String, val taskId: String, val provider: String, val packageName: String?, val expiresAt: Instant) {
        @Volatile var used = false
    }

    sealed interface Redeemed {
        data class Ok(val taskId: String, val provider: String, val packageName: String?) : Redeemed
        /** Malformed, forged, someone else's, expired, already used or for another app: all look the same outside. */
        data class Refused(val why: String) : Redeemed
    }

    private val random = SecureRandom()
    private val grants = ConcurrentHashMap<String, Grant>()
    /** The current link per task (CR-006): a new one replaces the previous, which stops working. */
    private val current = ConcurrentHashMap<String, String>()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun key(token: String) = secrets.hmacHex("handoff-store", token)
    private fun sign(id: String, g: Grant) = b64.encodeToString(secrets.hmac("handoff", listOf(id, g.userId, g.taskId, g.provider, g.packageName ?: "", g.expiresAt.toString()).joinToString("|")).copyOf(16))

    /** A new token and when it expires. */
    fun issue(userId: String, taskId: String, provider: String, packageName: String?): Pair<String, Instant> {
        val expires = clock.instant().truncatedTo(ChronoUnit.SECONDS).plus(ttl)
        val g = Grant(userId, taskId, provider, packageName, expires)
        val id = b64.encodeToString(ByteArray(16).also(random::nextBytes))
        val token = id + sign(id, g) // 22 + 22 characters, [A-Za-z0-9_-] (contracts/schemas/deep-link.schema.json)
        val k = key(token)
        grants[k] = g
        current.put(taskId, k)?.let(grants::remove)
        return token to expires
    }

    /** Checks a token presented by [userId] for [packageName] and uses it up. */
    fun redeem(userId: String, token: String, packageName: String?): Redeemed {
        if (!TOKEN.matches(token)) return Redeemed.Refused("malformed")
        val g = grants[key(token)] ?: return Redeemed.Refused("unknown")
        if (!MessageDigest.isEqual(token.drop(22).toByteArray(), sign(token.take(22), g).toByteArray())) return Redeemed.Refused("forged")
        if (g.userId != userId) return Redeemed.Refused("other user")
        if (!clock.instant().isBefore(g.expiresAt)) return Redeemed.Refused("expired")
        if (g.packageName != null && g.packageName != packageName) return Redeemed.Refused("other app")
        synchronized(g) {
            if (g.used) return Redeemed.Refused("used")
            g.used = true
        }
        return Redeemed.Ok(g.taskId, g.provider, g.packageName)
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(10)
        private val TOKEN = Regex("^[A-Za-z0-9_-]{44}$")
    }
}
