package com.smartcart.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** CR-006 links without a backend follow the server's rules: opaque, once, newest only, 10 minutes. */
class LocalHandoffLinksTest {
    private class Ticking(var now: Instant = Instant.parse("2026-11-28T10:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    @Test fun aLinkWorksOnceOnlyTheNewestWorksAndItExpires() {
        val clock = Ticking()
        val links = LocalHandoffLinks(clock)
        val (old, _) = links.issue("tsk_1")
        val (token, expires) = links.issue("tsk_1")
        assertTrue("opaque and URL-safe", Regex("^[A-Za-z0-9_-]{16,128}$").matches(token) && "tsk" !in token)
        assertEquals(clock.now.plus(Duration.ofMinutes(10)), expires)
        assertNull("replaced by a newer one", links.redeem(old))
        assertEquals("tsk_1", links.redeem(token))
        assertNull("used", links.redeem(token))
        val (late, _) = links.issue("tsk_2")
        clock.now = clock.now.plus(Duration.ofMinutes(10))
        assertNull("expired", links.redeem(late))
        assertNull("unknown", links.redeem("A".repeat(43)))
    }
}
