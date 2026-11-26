package com.smartcart.server.security

import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** A completed response remembered under an Idempotency-Key. */
data class StoredResponse(val requestHash: String, val status: Int, val body: String)

sealed interface Claim {
    /** First use of the key: process the request, then call [IdempotencyStore.complete]. */
    data object Claimed : Claim
    /** The same key is being processed right now by another request. */
    data object InProgress : Claim
    data class Replay(val response: StoredResponse) : Claim
}

/**
 * Remembers responses to non-idempotent requests for 24 hours so a retried request (same key, same
 * body) gets the original response instead of acting twice. A reused key with a different body is a
 * conflict. Keys are scoped per operation and caller.
 */
interface IdempotencyStore {
    fun claim(scope: String, key: String): Claim
    fun complete(scope: String, key: String, response: StoredResponse)
    fun release(scope: String, key: String)
}

val IDEMPOTENCY_TTL: Duration = Duration.ofHours(24)

class InMemoryIdempotencyStore(private val clock: Clock = Clock.systemUTC()) : IdempotencyStore {
    private data class Entry(val response: StoredResponse?, val expiresAtMs: Long)
    private val map = ConcurrentHashMap<String, Entry>()

    @Synchronized
    override fun claim(scope: String, key: String): Claim {
        val k = "$scope|$key"
        val e = map[k]?.takeIf { it.expiresAtMs > clock.millis() }
        return when {
            e == null -> { map[k] = Entry(null, clock.millis() + IDEMPOTENCY_TTL.toMillis()); Claim.Claimed }
            e.response == null -> Claim.InProgress
            else -> Claim.Replay(e.response)
        }
    }

    override fun complete(scope: String, key: String, response: StoredResponse) {
        map["$scope|$key"] = Entry(response, clock.millis() + IDEMPOTENCY_TTL.toMillis())
    }

    override fun release(scope: String, key: String) { map.remove("$scope|$key") }
}

class RedisIdempotencyStore(url: String) : IdempotencyStore, AutoCloseable {
    private val client = RedisClient.create(url)
    private val connection = client.connect()
    private val redis: RedisCommands<String, String> = connection.sync()
    private fun k(scope: String, key: String) = "idem:$scope:$key"

    override fun claim(scope: String, key: String): Claim {
        val set = redis.set(k(scope, key), IN_PROGRESS, SetArgs().nx().px(IDEMPOTENCY_TTL.toMillis()))
        if (set == "OK") return Claim.Claimed
        val v = redis.get(k(scope, key)) ?: return claim(scope, key)
        if (v == IN_PROGRESS) return Claim.InProgress
        val (hash, status, body) = v.split('\u0000', limit = 3)
        return Claim.Replay(StoredResponse(hash, status.toInt(), body))
    }

    override fun complete(scope: String, key: String, response: StoredResponse) {
        redis.psetex(k(scope, key), IDEMPOTENCY_TTL.toMillis(), "${response.requestHash}\u0000${response.status}\u0000${response.body}")
    }

    override fun release(scope: String, key: String) { redis.del(k(scope, key)) }

    override fun close() {
        connection.close()
        client.shutdown()
    }

    private companion object { const val IN_PROGRESS = "\u0001in-progress" }
}
