package com.smartcart.server.security

import com.smartcart.server.support.MutableClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityPrimitivesTest {
    private val pw = Passwords()

    @Test fun argon2idRoundTrip() {
        val h = pw.hash("correct horse battery")
        assertTrue(pw.verify("correct horse battery", h))
        assertFalse(pw.verify("correct horse batterY", h))
        assertNotEquals(h, pw.hash("correct horse battery"), "salted")
    }

    @Test fun tamperedOrForeignHashesNeverVerify() {
        val h = pw.hash("correct horse battery")
        assertFalse(pw.verify("correct horse battery", h.replace("argon2id", "argon2i")))
        assertFalse(pw.verify("correct horse battery", "plaintext"))
        assertFalse(pw.verify("anything", pw.dummyHash))
    }

    @Test fun passwordPolicy() {
        assertTrue(PasswordPolicy.isWeak("1234567890", "priya.k", "priya@example.com"))
        assertTrue(PasswordPolicy.isWeak("my-priya.k-pass", "priya.k", "priya@example.com"))
        assertTrue(PasswordPolicy.isWeak("ababababababab", "priya.k", "priya@example.com"))
        assertFalse(PasswordPolicy.isWeak("correct horse battery", "priya.k", "priya@example.com"))
    }

    @Test fun signedChallengeIdsCarryTheirTimeAndResistForgery() {
        val s = Secrets.random()
        val t = Instant.parse("2026-11-26T05:00:00Z")
        val id = s.signedChallengeId(t)
        assertEquals(t, s.challengeIssuedAt(id))
        assertNull(Secrets.random().challengeIssuedAt(id), "another key can't verify it")
        val flipped = id.dropLast(1) + (if (id.last() == 'A') 'B' else 'A')
        assertNull(s.challengeIssuedAt(flipped))
        assertNull(s.challengeIssuedAt("chl_short"))
    }

    @Test fun inMemoryLimiterWindows() {
        val clock = MutableClock()
        val l = InMemoryRateLimiter(clock)
        repeat(3) { assertTrue(l.hit("k", 3, Duration.ofMinutes(1)).allowed) }
        val d = l.hit("k", 3, Duration.ofMinutes(1))
        assertFalse(d.allowed); assertEquals(0, d.remaining); assertEquals(60, d.resetSeconds)
        clock.advance(Duration.ofSeconds(61))
        assertTrue(l.hit("k", 3, Duration.ofMinutes(1)).allowed)
    }
}
