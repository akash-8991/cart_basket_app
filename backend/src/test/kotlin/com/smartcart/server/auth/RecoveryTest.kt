package com.smartcart.server.auth

import com.smartcart.server.mail.MailTemplate
import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.RecordingMailer
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.login
import com.smartcart.server.support.loginBody
import com.smartcart.server.support.normalisedBody
import com.smartcart.server.support.postJson
import com.smartcart.server.support.register
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecoveryTest {
    private val newPassword = "a much better passphrase"

    private suspend fun HttpResponse.challengeId() = Regex("\"challenge_id\":\"([^\"]+)\"").find(bodyAsText())!!.groupValues[1]
    private fun resetBody(challenge: String, code: String, pw: String = newPassword) = """{"challenge_id":"$challenge","code":"$code","new_password":"$pw"}"""
    private fun forgot(userId: String, email: String) = """{"user_id":"$userId","email":"$email"}"""

    @Test fun forgotPasswordAnswersIdenticallyWhetherOrNotTheAccountExists() {
        val mail = RecordingMailer()
        withApp(testServices(mailer = mail)) {
            client.register()
            val match = client.postJson("/v1/auth/forgot-password", forgot("priya.k", "PRIYA@example.com")).assertContract("POST", "/v1/auth/forgot-password")
            val wrongEmail = client.postJson("/v1/auth/forgot-password", forgot("priya.k", "someone@example.com")).assertContract("POST", "/v1/auth/forgot-password")
            val noUser = client.postJson("/v1/auth/forgot-password", forgot("nobody.here", "priya@example.com")).assertContract("POST", "/v1/auth/forgot-password")
            assertEquals(listOf(202, 202, 202), listOf(match, wrongEmail, noUser).map { it.status.value })
            assertEquals(match.normalisedBody(), wrongEmail.normalisedBody())
            assertEquals(match.normalisedBody(), noUser.normalisedBody())
            assertEquals(1, mail.sent.count { it.template == MailTemplate.PASSWORD_RESET_CODE }, "only the matching account gets a code")
        }
    }

    @Test fun resetWithTheEmailedCodeChangesThePasswordAndSignsOutEverywhere() {
        val mail = RecordingMailer()
        withApp(testServices(mailer = mail)) {
            val old = client.register()
            val challenge = client.postJson("/v1/auth/forgot-password", forgot("priya.k", "priya@example.com")).challengeId()
            val code = mail.sent.single { it.template == MailTemplate.PASSWORD_RESET_CODE }.params.getValue("code")
            val r = client.postJson("/v1/auth/reset-password", resetBody(challenge, code)).assertContract("POST", "/v1/auth/reset-password")
            assertEquals("{\"password_reset\":true,\"all_other_sessions_revoked\":true}", r.bodyAsText())
            assertEquals(401, client.get("/v1/auth/sessions") { bearerAuth(old.tokens.accessToken) }.status.value)
            assertEquals("invalid_credentials", client.postJson("/v1/auth/login", loginBody()).errorCode())
            client.login(password = newPassword)
            // A code works once.
            assertEquals("challenge_expired", client.postJson("/v1/auth/reset-password", resetBody(challenge, code, "yet another passphrase")).errorCode())
        }
    }

    /** Five wrong codes lock a challenge; the right code no longer works afterwards. Same for real and made-up IDs. */
    private fun wrongCodesLockTheChallenge(realAccount: Boolean) {
        val mail = RecordingMailer()
        withApp(testServices(mailer = mail)) {
            client.register()
            val id = if (realAccount) client.postJson("/v1/auth/forgot-password", forgot("priya.k", "priya@example.com")).challengeId()
                     else client.postJson("/v1/auth/forgot-password", forgot("nobody.here", "x@example.com")).challengeId()
            repeat(5) {
                val r = client.postJson("/v1/auth/reset-password", resetBody(id, "000000")).assertContract("POST", "/v1/auth/reset-password")
                assertEquals("validation_failed", r.errorCode())
            }
            val code = if (realAccount) mail.sent.single { it.template == MailTemplate.PASSWORD_RESET_CODE }.params.getValue("code") else "123456"
            assertEquals("challenge_expired", client.postJson("/v1/auth/reset-password", resetBody(id, code)).assertContract("POST", "/v1/auth/reset-password").errorCode())
        }
    }

    @Test fun wrongCodesLockARealChallenge() = wrongCodesLockTheChallenge(realAccount = true)

    @Test fun wrongCodesLockAMadeUpChallengeTheSameWay() = wrongCodesLockTheChallenge(realAccount = false)

    @Test fun challengesExpireAfterFifteenMinutesWhetherRealOrNot() {
        val clock = MutableClock()
        val mail = RecordingMailer()
        withApp(testServices(clock, mail)) {
            client.register()
            val real = client.postJson("/v1/auth/forgot-password", forgot("priya.k", "priya@example.com")).challengeId()
            val fake = client.postJson("/v1/auth/forgot-password", forgot("nobody.here", "x@example.com")).challengeId()
            clock.advance(Duration.ofMinutes(16))
            assertEquals("challenge_expired", client.postJson("/v1/auth/reset-password", resetBody(real, mail.sent.single { it.template == MailTemplate.PASSWORD_RESET_CODE }.params.getValue("code"))).errorCode())
            assertEquals("challenge_expired", client.postJson("/v1/auth/reset-password", resetBody(fake, "123456")).errorCode())
        }
    }

    @Test fun forgedChallengeIdsAreRejected() = withApp {
        val r = client.postJson("/v1/auth/reset-password", resetBody("chl_forged-value-abcdefghijklmnopqrstuvwx", "123456")).assertContract("POST", "/v1/auth/reset-password")
        assertEquals("validation_failed", r.errorCode())
    }

    @Test fun weakNewPasswordIsRefused() {
        val mail = RecordingMailer()
        withApp(testServices(mailer = mail)) {
            client.register()
            val challenge = client.postJson("/v1/auth/forgot-password", forgot("priya.k", "priya@example.com")).challengeId()
            val r = client.postJson("/v1/auth/reset-password", resetBody(challenge, mail.sent.single { it.template == MailTemplate.PASSWORD_RESET_CODE }.params.getValue("code"), "1234567890")).assertContract("POST", "/v1/auth/reset-password")
            assertEquals("weak_password", r.errorCode())
        }
    }

    @Test fun forgotUserIdEmailsAllIdsForTheAddressAndRevealsNothing() {
        val mail = RecordingMailer()
        withApp(testServices(mailer = mail)) {
            client.register()
            client.register(userId = "priya.work", deviceId = "dev-2")
            val known = client.postJson("/v1/auth/forgot-user-id", """{"email":"priya@example.com"}""").assertContract("POST", "/v1/auth/forgot-user-id")
            val unknown = client.postJson("/v1/auth/forgot-user-id", """{"email":"nobody@example.com"}""").assertContract("POST", "/v1/auth/forgot-user-id")
            assertEquals(known.normalisedBody(), unknown.normalisedBody())
            assertTrue("priya" !in known.bodyAsText(), "the user ID is never in the response")
            assertEquals("priya.k, priya.work", mail.sent.single { it.template == MailTemplate.USER_ID_REMINDER }.params.getValue("user_ids"))
        }
    }

    @Test fun recoveryIsRateLimitedPerTarget() = withApp {
        repeat(3) { assertEquals(202, client.postJson("/v1/auth/forgot-password", forgot("priya.k", "a$it@example.com")).status.value) }
        val r = client.postJson("/v1/auth/forgot-password", forgot("priya.k", "b@example.com")).assertContract("POST", "/v1/auth/forgot-password")
        assertEquals(429, r.status.value)
        assertEquals("rate_limited", r.errorCode())
        assertNotNull(r.headers[HttpHeaders.RetryAfter])
        assertEquals("3", r.headers["RateLimit-Limit"])
    }
}
