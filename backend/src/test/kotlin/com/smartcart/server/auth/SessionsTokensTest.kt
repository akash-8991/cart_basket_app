package com.smartcart.server.auth

import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.login
import com.smartcart.server.support.postJson
import com.smartcart.server.support.register
import com.smartcart.server.support.testServices
import com.smartcart.server.support.tokenPair
import com.smartcart.server.support.withApp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionsTokensTest {
    private fun refreshBody(token: String, deviceId: String = "dev-1") = """{"refresh_token":"$token","device_id":"$deviceId"}"""

    @Test fun refreshRotatesTheToken() = withApp {
        val a = client.register()
        val r = client.postJson("/v1/auth/refresh", refreshBody(a.tokens.refreshToken)).assertContract("POST", "/v1/auth/refresh")
        assertEquals(200, r.status.value)
        val next = r.tokenPair()
        assertNotEquals(a.tokens.refreshToken, next.refreshToken)
        assertEquals(200, client.get("/v1/auth/sessions") { bearerAuth(next.accessToken) }.status.value)
    }

    @Test fun reusingARefreshTokenEndsTheSession() = withApp {
        // Threat model A-03: an attacker replays a stolen (already used) refresh token.
        val a = client.register()
        val next = client.postJson("/v1/auth/refresh", refreshBody(a.tokens.refreshToken)).tokenPair()
        val replay = client.postJson("/v1/auth/refresh", refreshBody(a.tokens.refreshToken)).assertContract("POST", "/v1/auth/refresh")
        assertEquals("session_expired", replay.errorCode())
        // The whole family is revoked: the legitimate holder's newer tokens stop working too.
        assertEquals("session_expired", client.postJson("/v1/auth/refresh", refreshBody(next.refreshToken)).errorCode())
        assertEquals(401, client.get("/v1/auth/sessions") { bearerAuth(next.accessToken) }.status.value)
    }

    @Test fun refreshFromAnotherDeviceEndsTheSession() = withApp {
        val a = client.register()
        assertEquals("session_expired", client.postJson("/v1/auth/refresh", refreshBody(a.tokens.refreshToken, deviceId = "attacker")).errorCode())
        assertEquals("session_expired", client.postJson("/v1/auth/refresh", refreshBody(a.tokens.refreshToken)).errorCode())
    }

    @Test fun refreshTokensAreStoredOnlyAsHashes() = withApp { s ->
        val a = client.register()
        s.db.connection.use { c ->
            val rs = c.createStatement().executeQuery("select encode(token_hash, 'escape') from refresh_tokens")
            while (rs.next()) assertFalse(a.tokens.refreshToken in rs.getString(1))
        }
    }

    @Test fun sessionsExpire() {
        val clock = MutableClock()
        withApp(testServices(clock)) {
            client.register()
            val short = client.postJson("/v1/auth/login", com.smartcart.server.support.loginBody(remember = false, deviceId = "dev-2"))
            val rt = com.smartcart.server.http.ApiJson.decodeFromString(AuthSession.serializer(), short.bodyAsText()).tokens.refreshToken
            clock.advance(Duration.ofHours(13))
            assertEquals("session_expired", client.postJson("/v1/auth/refresh", refreshBody(rt, "dev-2")).errorCode())
        }
    }

    @Test fun accessTokensExpireAfterFifteenMinutes() {
        val clock = MutableClock()
        withApp(testServices(clock)) {
            val a = client.register()
            clock.advance(Duration.ofMinutes(16))
            val r = client.get("/v1/auth/sessions") { bearerAuth(a.tokens.accessToken) }.assertContract("GET", "/v1/auth/sessions")
            assertEquals("session_expired", r.errorCode())
        }
    }

    @Test fun protectedRoutesNeedAToken() = withApp {
        assertEquals("unauthenticated", client.get("/v1/auth/sessions").assertContract("GET", "/v1/auth/sessions").errorCode())
        assertEquals("session_expired", client.get("/v1/auth/sessions") { bearerAuth("not.a.jwt") }.errorCode())
    }

    @Test fun sessionListShowsDevicesAndMarksTheCurrentOne() = withApp {
        val phone = client.register()
        client.login(deviceId = "dev-2")
        val r = client.get("/v1/auth/sessions") { bearerAuth(phone.tokens.accessToken) }.assertContract("GET", "/v1/auth/sessions")
        val body = r.bodyAsText()
        assertEquals(2, Regex("\"session_id\"").findAll(body).count())
        assertTrue("\"session_id\":\"${phone.sessionId}\",\"device_label\":\"Android 15\"" in body)
        assertEquals(1, Regex("\"current\":true").findAll(body).count())
    }

    @Test fun logoutTakesEffectImmediately() = withApp {
        val a = client.register()
        val r = client.postJson("/v1/auth/logout", "", token = a.tokens.accessToken).assertContract("POST", "/v1/auth/logout")
        assertEquals(204, r.status.value)
        assertEquals(401, client.get("/v1/auth/sessions") { bearerAuth(a.tokens.accessToken) }.status.value)
        assertEquals("session_expired", client.postJson("/v1/auth/refresh", refreshBody(a.tokens.refreshToken)).errorCode())
    }

    @Test fun usersCannotTouchOtherUsersSessions() = withApp {
        // Threat model A-15: object-level authorization on session IDs.
        val priya = client.register()
        val ravi = client.register(userId = "ravi.s", email = "ravi@example.com", deviceId = "dev-9")
        val r = client.delete("/v1/auth/sessions/${priya.sessionId}") { bearerAuth(ravi.tokens.accessToken) }.assertContract("DELETE", "/v1/auth/sessions/{sessionId}")
        assertEquals(404, r.status.value)
        assertEquals(200, client.get("/v1/auth/sessions") { bearerAuth(priya.tokens.accessToken) }.status.value, "priya is still signed in")
        val unknown = client.delete("/v1/auth/sessions/ses_doesnotexist") { bearerAuth(ravi.tokens.accessToken) }
        assertEquals(r.bodyAsText().substringBefore("correlation_id"), unknown.bodyAsText().substringBefore("correlation_id"), "foreign and missing look the same")
    }

    @Test fun revokingADeviceSignsItOut() = withApp {
        val phone = client.register()
        val tablet = client.login(deviceId = "dev-2")
        val r = client.delete("/v1/auth/sessions/${tablet.sessionId}") { bearerAuth(phone.tokens.accessToken) }.assertContract("DELETE", "/v1/auth/sessions/{sessionId}")
        assertEquals(204, r.status.value)
        assertEquals(401, client.get("/v1/auth/sessions") { bearerAuth(tablet.tokens.accessToken) }.status.value)
    }

    @Test fun revokeAllKeepsOnlyTheCurrentDevice() = withApp {
        val phone = client.register()
        val tablet = client.login(deviceId = "dev-2")
        client.login(deviceId = "dev-3")
        val r = client.postJson("/v1/auth/sessions/revoke-all", "", token = phone.tokens.accessToken).assertContract("POST", "/v1/auth/sessions/revoke-all")
        assertEquals("{\"revoked_count\":2}", r.bodyAsText())
        assertEquals(200, client.get("/v1/auth/sessions") { bearerAuth(phone.tokens.accessToken) }.status.value)
        assertEquals(401, client.get("/v1/auth/sessions") { bearerAuth(tablet.tokens.accessToken) }.status.value)
    }
}
