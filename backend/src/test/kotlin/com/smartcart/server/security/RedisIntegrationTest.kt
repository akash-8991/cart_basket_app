package com.smartcart.server.security

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs the Redis implementations against a throwaway redis-server, if one is installed (D-017). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisIntegrationTest {
    private var process: Process? = null
    private var url = ""

    @BeforeAll fun start() {
        val bin = listOf("/opt/homebrew/bin/redis-server", "/usr/local/bin/redis-server", "/usr/bin/redis-server").firstOrNull { File(it).canExecute() }
        assumeTrue(bin != null, "redis-server not installed; Redis integration skipped")
        val port = ServerSocket(0).use { it.localPort }
        process = ProcessBuilder(bin, "--port", "$port", "--save", "", "--appendonly", "no").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        url = "redis://127.0.0.1:$port"
        repeat(50) { if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) return; Thread.sleep(100) }
        error("redis-server did not start")
    }

    @AfterAll fun stop() { process?.destroy() }

    @Test fun rateLimiterCountsAndExpires() {
        RedisRateLimiter(url).use { l ->
            repeat(2) { assertTrue(l.hit("t1", 2, Duration.ofSeconds(1)).allowed) }
            val d = l.hit("t1", 2, Duration.ofSeconds(1))
            assertFalse(d.allowed); assertEquals(0, d.remaining)
            Thread.sleep(1100)
            assertTrue(l.hit("t1", 2, Duration.ofSeconds(1)).allowed)
            l.setFlag("lock:x", Duration.ofSeconds(30))
            assertTrue(l.ttlSeconds("lock:x") in 29..30)
            l.reset("lock:x"); assertEquals(0, l.ttlSeconds("lock:x"))
        }
    }

    @Test fun idempotencyStoreClaimsOnce() {
        RedisIdempotencyStore(url).use { s ->
            assertIs<Claim.Claimed>(s.claim("register", "key-123456789012"))
            assertIs<Claim.InProgress>(s.claim("register", "key-123456789012"))
            s.complete("register", "key-123456789012", StoredResponse("h", 201, "priya.k"))
            val replay = assertIs<Claim.Replay>(s.claim("register", "key-123456789012"))
            assertEquals(StoredResponse("h", 201, "priya.k"), replay.response)
        }
    }
}
