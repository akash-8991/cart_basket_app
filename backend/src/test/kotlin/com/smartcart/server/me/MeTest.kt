package com.smartcart.server.me

import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.assertErrorEnvelope
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.login
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.postgresql.util.PSQLException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeTest {
    private fun consent(granted: Boolean, version: String = "2026-11") = """{"granted":$granted,"version":"$version"}"""

    @Test fun profileShowsAMaskedEmail() = withApp {
        val a = client.register()
        val r = client.getAuthed("/v1/me", a.tokens.accessToken).assertContract("GET", "/v1/me")
        val body = r.bodyAsText()
        assertTrue("\"email_masked\":\"p***@e***.com\"" in body, body)
        assertTrue("\"privacy_mode\":\"device_only\"" in body, "privacy-first default")
        assertFalse("priya@example.com" in body)
    }

    @Test fun meNeedsAToken() = withApp {
        assertEquals("unauthenticated", client.get("/v1/me").assertContract("GET", "/v1/me").errorCode())
    }

    @Test fun globalConsentsStartNotGranted() = withApp {
        val a = client.register()
        val r = client.getAuthed("/v1/me/consents", a.tokens.accessToken).assertContract("GET", "/v1/me/consents")
        val body = r.bodyAsText()
        assertTrue("{\"key\":\"ai_processing\",\"granted\":false" in body)
        assertTrue("{\"key\":\"notifications\",\"granted\":false" in body)
        assertEquals(2, Regex("\"key\"").findAll(body).count())
    }

    @Test fun settingAConsentIsRecordedWithHistoryAndActivity() = withApp { s ->
        val a = client.register()
        val t = a.tokens.accessToken
        val r = client.putJson("/v1/me/consents/automation.blinkit", consent(true), t).assertContract("PUT", "/v1/me/consents/{consentKey}")
        assertTrue(r.bodyAsText().startsWith("{\"key\":\"automation.blinkit\",\"granted\":true,\"version\":\"2026-11\""))
        client.putJson("/v1/me/consents/automation.blinkit", consent(true), t)   // no change
        client.putJson("/v1/me/consents/automation.blinkit", consent(false), t)

        assertTrue("{\"key\":\"automation.blinkit\",\"granted\":false" in client.getAuthed("/v1/me/consents", t).bodyAsText())
        s.db.connection.use { c ->
            val rs = c.createStatement().executeQuery("select count(*) from consent_events where consent_key = 'automation.blinkit'")
            rs.next(); assertEquals(3, rs.getInt(1), "every decision is kept")
            assertFailsWith<PSQLException> { c.createStatement().execute("update consent_events set granted = true") }
        }
        val activity = client.getAuthed("/v1/me/activity", t).assertContract("GET", "/v1/me/activity").bodyAsText()
        assertEquals(1, Regex("\"type\":\"consent_granted\"").findAll(activity).count(), "a repeated grant isn't a new event")
        assertEquals(1, Regex("\"type\":\"consent_revoked\"").findAll(activity).count())
        assertTrue("\"provider\":\"blinkit\"" in activity)
        assertTrue("You turned off SmartCart to prepare carts in Blinkit." in activity)
    }

    @Test fun onlyKnownConsentKeysAreAccepted() = withApp {
        val t = client.register().tokens.accessToken
        for (key in listOf("marketing", "automation.paytm", "automation.", "history", "is_admin")) {
            val r = client.putJson("/v1/me/consents/$key", consent(true), t).assertContract("PUT", "/v1/me/consents/{consentKey}")
            assertEquals("validation_failed", r.errorCode(), key)
        }
        assertEquals("validation_failed", client.putJson("/v1/me/consents/ai_processing", consent(true, ""), t).errorCode())
        assertEquals(200, client.putJson("/v1/me/consents/history.swiggy_instamart", consent(true), t).status.value)
    }

    @Test fun activityPagesAreStableAndComplete() {
        val clock = MutableClock()
        withApp(testServices(clock)) {
            val t = client.register().tokens.accessToken
            repeat(25) { i ->
                clock.advance(Duration.ofSeconds(1))
                client.putJson("/v1/me/consents/notifications", consent(i % 2 == 0), t)
            }
            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val r = client.getAuthed("/v1/me/activity?limit=10" + (cursor?.let { "&cursor=$it" } ?: ""), t).assertContract("GET", "/v1/me/activity")
                val body = r.bodyAsText()
                seen += Regex("\"event_id\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }
                cursor = Regex("\"next_cursor\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                pages++
            } while (cursor != null)
            assertEquals(3, pages)
            assertEquals(25, seen.size)
            assertEquals(25, seen.toSet().size, "no duplicates across pages")
            val first = client.getAuthed("/v1/me/activity?limit=1", t).bodyAsText()
            assertTrue("consent_granted" in first, "newest first (the 25th change granted)")
        }
    }

    @Test fun badPagingParametersAreRejected() = withApp {
        val t = client.register().tokens.accessToken
        assertEquals("validation_failed", client.getAuthed("/v1/me/activity?limit=0", t).assertContract("GET", "/v1/me/activity").errorCode())
        assertEquals("validation_failed", client.getAuthed("/v1/me/activity?limit=51", t).errorCode())
        assertEquals("validation_failed", client.getAuthed("/v1/me/activity?cursor=not-a-cursor", t).errorCode())
    }

    @Test fun usersOnlyEverSeeTheirOwnConsentsAndActivity() = withApp {
        val priya = client.register().tokens.accessToken
        val ravi = client.register(userId = "ravi.s", email = "ravi@example.com", deviceId = "dev-9").tokens.accessToken
        client.putJson("/v1/me/consents/automation.zomato", consent(true), priya)
        assertFalse("automation.zomato" in client.getAuthed("/v1/me/consents", ravi).bodyAsText())
        assertFalse("Zomato" in client.getAuthed("/v1/me/activity", ravi).bodyAsText())
        assertTrue("ravi.s" in client.getAuthed("/v1/me", ravi).bodyAsText())
    }

    @Test fun signingOutADeviceShowsInActivity() = withApp {
        val phone = client.register()
        val tablet = client.login(deviceId = "dev-2")
        client.delete("/v1/auth/sessions/${tablet.sessionId}") { bearerAuth(phone.tokens.accessToken) }
        val body = client.getAuthed("/v1/me/activity", phone.tokens.accessToken).bodyAsText()
        assertTrue("\"type\":\"session_revoked\",\"summary\":\"You signed out Android 15.\"" in body, body)
        assertNull(Regex("\"next_cursor\":\"").find(body))
    }

    @Test fun emailMasking() {
        assertEquals("p***@e***.com", maskEmail("priya@example.com"))
        assertEquals("a***@b***.in", maskEmail("a@b.co.in"))
    }
}
