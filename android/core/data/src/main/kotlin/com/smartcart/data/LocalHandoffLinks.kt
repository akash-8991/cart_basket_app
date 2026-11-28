package com.smartcart.data

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * CR-006 handoff links when there is no backend (fixtures, or the debug Test Store without a server): the same
 * rules as the server's — opaque tokens with no IDs, at most 10 minutes, one current link per task (a newer one
 * replaces the older), used once.
 */
class LocalHandoffLinks(private val clock: Clock = Clock.systemUTC(), private val ttl: Duration = Duration.ofMinutes(10)) {
    private class Link(val taskId: String, val expiresAt: Instant) { @Volatile var used = false }

    private val random = SecureRandom()
    private val links = ConcurrentHashMap<String, Link>()
    private val current = ConcurrentHashMap<String, String>()

    /** A new token for [taskId] and when it expires; the task's previous token stops working. */
    fun issue(taskId: String): Pair<String, Instant> {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val expires = clock.instant().plus(ttl)
        links[token] = Link(taskId, expires)
        current.put(taskId, token)?.let(links::remove)
        return token to expires
    }

    /** The task the token was for, once; null for anything else. */
    fun redeem(token: String): String? {
        val l = links[token] ?: return null
        if (!clock.instant().isBefore(l.expiresAt)) return null
        synchronized(l) {
            if (l.used) return null
            l.used = true
        }
        return l.taskId
    }

    companion object {
        fun notFound() = clientError(com.smartcart.model.ErrorCode.NOT_FOUND, "This link has expired or was already used.")
    }
}
