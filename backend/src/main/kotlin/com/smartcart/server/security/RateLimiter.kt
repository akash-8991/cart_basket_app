package com.smartcart.server.security

import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Outcome of counting one event against a limit. */
data class Decision(val allowed: Boolean, val limit: Int, val remaining: Int, val resetSeconds: Int) {
    val retryAfterSeconds: Int get() = resetSeconds.coerceAtLeast(1)
}

/**
 * Fixed-window counters (D-017). Each call counts one event for [key]; the window starts at the first
 * event. Also stores small expiring values used for account lockout.
 */
interface RateLimiter {
    fun hit(key: String, limit: Int, window: Duration): Decision

    /** Current count for [key] without adding to it. */
    fun peek(key: String): Int

    fun reset(key: String)

    /** Seconds until [key] expires, or 0 if it doesn't exist. */
    fun ttlSeconds(key: String): Int

    /** Sets [key] to exist for [ttl] (used as a lock flag). */
    fun setFlag(key: String, ttl: Duration)
}

class InMemoryRateLimiter(private val clock: Clock = Clock.systemUTC()) : RateLimiter {
    private data class Entry(var count: Int, val expiresAtMs: Long)
    private val map = ConcurrentHashMap<String, Entry>()

    private fun live(key: String): Entry? = map[key]?.takeIf { it.expiresAtMs > clock.millis() } ?: run { map.remove(key); null }

    @Synchronized
    override fun hit(key: String, limit: Int, window: Duration): Decision {
        val e = live(key) ?: Entry(0, clock.millis() + window.toMillis()).also { map[key] = it }
        e.count++
        val reset = ((e.expiresAtMs - clock.millis() + 999) / 1000).toInt()
        return Decision(e.count <= limit, limit, (limit - e.count).coerceAtLeast(0), reset)
    }

    override fun peek(key: String): Int = live(key)?.count ?: 0
    override fun reset(key: String) { map.remove(key) }
    override fun ttlSeconds(key: String): Int = live(key)?.let { ((it.expiresAtMs - clock.millis() + 999) / 1000).toInt() } ?: 0
    override fun setFlag(key: String, ttl: Duration) { map[key] = Entry(1, clock.millis() + ttl.toMillis()) }
}

class RedisRateLimiter(url: String) : RateLimiter, AutoCloseable {
    private val client = RedisClient.create(url)
    private val connection = client.connect()
    private val redis: RedisCommands<String, String> = connection.sync()

    // INCR and set the expiry on the first event, atomically. Returns {count, ttlMs}.
    private val script = """
        local c = redis.call('INCR', KEYS[1])
        if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
        return {c, redis.call('PTTL', KEYS[1])}
    """.trimIndent()

    override fun hit(key: String, limit: Int, window: Duration): Decision {
        val r = redis.eval<List<Long>>(script, ScriptOutputType.MULTI, arrayOf(PREFIX + key), window.toMillis().toString())
        val count = r[0].toInt()
        val reset = ((r[1] + 999) / 1000).toInt().coerceAtLeast(1)
        return Decision(count <= limit, limit, (limit - count).coerceAtLeast(0), reset)
    }

    override fun peek(key: String): Int = redis.get(PREFIX + key)?.toIntOrNull() ?: 0
    override fun reset(key: String) { redis.del(PREFIX + key) }
    override fun ttlSeconds(key: String): Int = redis.ttl(PREFIX + key).let { if (it > 0) it.toInt() else 0 }
    override fun setFlag(key: String, ttl: Duration) { redis.psetex(PREFIX + key, ttl.toMillis(), "1") }

    override fun close() {
        connection.close()
        client.shutdown()
    }

    private companion object { const val PREFIX = "rl:" }
}
