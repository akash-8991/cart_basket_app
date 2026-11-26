package com.smartcart.server.auth

import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.PASSWORD
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.loginBody
import com.smartcart.server.support.normalisedBody
import com.smartcart.server.support.postJson
import com.smartcart.server.support.register
import com.smartcart.server.support.registerBody
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RegisterLoginTest {
    private val key get() = UUID.randomUUID().toString()

    @Test fun registerCreatesAnAccountAndASession() = withApp { s ->
        val r = client.postJson("/v1/auth/register", registerBody(), key).assertContract("POST", "/v1/auth/register")
        assertEquals(201, r.status.value)
        val body = r.bodyAsText()
        assertTrue("\"email_verification_required\":true" in body)
        // Password stored only as Argon2id; never in plain text anywhere.
        s.db.connection.use { c ->
            val rs = c.createStatement().executeQuery("select password_hash from users where user_id = 'priya.k'")
            rs.next(); assertTrue(rs.getString(1).startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        }
    }

    @Test fun takenUserIdIsReportedOnTheField() = withApp {
        client.register()
        val r = client.postJson("/v1/auth/register", registerBody(email = "other@example.com", deviceId = "dev-2"), key).assertContract("POST", "/v1/auth/register")
        assertEquals(409, r.status.value)
        assertTrue("\"field\":\"user_id\",\"code\":\"user_id_unavailable\"" in r.bodyAsText())
    }

    @Test fun weakPasswordsAreRefused() = withApp {
        for (pw in listOf("1234567890", "priya.k-2026x", "aaaaaaaaaaab")) {
            val r = client.postJson("/v1/auth/register", registerBody(password = pw), key).assertContract("POST", "/v1/auth/register")
            assertEquals("weak_password", r.errorCode(), pw)
        }
    }

    @Test fun malformedRequestsAreValidationErrors() = withApp {
        val noKey = client.postJson("/v1/auth/register", registerBody()).assertContract("POST", "/v1/auth/register")
        assertEquals("validation_failed", noKey.errorCode())
        val badId = client.postJson("/v1/auth/register", registerBody(userId = "Priya K!"), key).assertContract("POST", "/v1/auth/register")
        assertTrue("\"field\":\"user_id\"" in badId.bodyAsText())
        val extraField = client.postJson("/v1/auth/register", registerBody().replace("{\"user_id\"", "{\"is_admin\":true,\"user_id\""), key)
            .assertContract("POST", "/v1/auth/register")
        assertEquals("validation_failed", extraField.errorCode())
        val notJson = client.postJson("/v1/auth/register", "user_id=priya", key).assertContract("POST", "/v1/auth/register")
        assertEquals(400, notJson.status.value)
    }

    @Test fun aRetriedRegistrationIsIdempotent() = withApp { s ->
        val k = key
        val first = client.postJson("/v1/auth/register", registerBody(), k)
        val retry = client.postJson("/v1/auth/register", registerBody(), k).assertContract("POST", "/v1/auth/register")
        assertEquals(201, first.status.value); assertEquals(201, retry.status.value)
        s.db.connection.use { c ->
            val rs = c.createStatement().executeQuery("select count(*) from users"); rs.next(); assertEquals(1, rs.getInt(1))
        }
        val reused = client.postJson("/v1/auth/register", registerBody(email = "else@example.com"), k).assertContract("POST", "/v1/auth/register")
        assertEquals("conflict", reused.errorCode())
    }

    @Test fun loginReturnsASession() = withApp {
        client.register()
        val r = client.postJson("/v1/auth/login", loginBody(remember = false)).assertContract("POST", "/v1/auth/login")
        assertEquals(200, r.status.value)
        assertTrue("\"access_expires_in_seconds\":900" in r.bodyAsText())
    }

    @Test fun unknownUserAndWrongPasswordLookIdentical() = withApp {
        client.register()
        val wrong = client.postJson("/v1/auth/login", loginBody(password = "not the password")).assertContract("POST", "/v1/auth/login")
        val unknown = client.postJson("/v1/auth/login", loginBody(userId = "nobody.here", password = "not the password")).assertContract("POST", "/v1/auth/login")
        assertEquals(401, wrong.status.value); assertEquals(401, unknown.status.value)
        assertEquals(wrong.normalisedBody(), unknown.normalisedBody())
    }

    @Test fun repeatedFailuresLockTheAccountWithBackoff() {
        val clock = MutableClock()
        withApp(testServices(clock)) {
            client.register()
            repeat(5) { assertEquals("invalid_credentials", client.postJson("/v1/auth/login", loginBody(password = "wrong password $it")).errorCode()) }
            // Locked: even the right password is refused until the lock expires.
            val locked = client.postJson("/v1/auth/login", loginBody()).assertContract("POST", "/v1/auth/login")
            assertEquals(429, locked.status.value)
            assertEquals("account_locked", locked.errorCode())
            assertEquals("30", locked.headers[HttpHeaders.RetryAfter])

            clock.advance(Duration.ofSeconds(31))
            assertEquals("invalid_credentials", client.postJson("/v1/auth/login", loginBody(password = "wrong again")).errorCode())
            assertEquals("60", client.postJson("/v1/auth/login", loginBody()).headers[HttpHeaders.RetryAfter], "lock doubles")

            clock.advance(Duration.ofSeconds(61))
            assertEquals(200, client.postJson("/v1/auth/login", loginBody()).status.value)
        }
    }

    @Test fun lockoutAlsoAppliesToUnknownUserIds() = withApp {
        repeat(5) { client.postJson("/v1/auth/login", loginBody(userId = "ghost.user", password = "guess $it")) }
        assertEquals("account_locked", client.postJson("/v1/auth/login", loginBody(userId = "ghost.user", password = "guess 6")).errorCode())
    }

    @Test fun perDeviceLoginLimit() = withApp {
        client.register()
        val codes = (1..21).map { client.postJson("/v1/auth/login", loginBody(userId = "user$it", password = "x".repeat(10))).errorCode() }
        assertEquals("rate_limited", codes.last())
        val limited = client.postJson("/v1/auth/login", loginBody()).assertContract("POST", "/v1/auth/login")
        assertEquals("20", limited.headers["RateLimit-Limit"])
        assertEquals("0", limited.headers["RateLimit-Remaining"])
        assertNotEquals(null, limited.headers[HttpHeaders.RetryAfter])
    }

    @Test fun auditTrailRecordsAuthEventsWithoutSecrets() = withApp { s ->
        client.register()
        client.postJson("/v1/auth/login", loginBody(password = "wrong password"))
        s.db.connection.use { c ->
            val rs = c.createStatement().executeQuery("select action, outcome, coalesce(detail::text,'') || coalesce(target,'') from audit_log order by id")
            val rows = buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getString(3))) }
            assertTrue(rows.any { it.first == "auth.register" && it.second == "success" })
            assertTrue(rows.any { it.first == "auth.login" && it.second == "invalid_credentials" })
            assertFalse(rows.any { PASSWORD in it.third || "wrong password" in it.third || "@" in it.third })
        }
    }
}
