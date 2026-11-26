package com.smartcart.server

import com.smartcart.server.chat.allowAi
import com.smartcart.server.chat.newChat
import com.smartcart.server.chat.say
import com.smartcart.server.mail.MailTemplate
import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.RecordingMailer
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.login
import com.smartcart.server.support.postJson
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CR-003 (backend): email verification, terms/privacy re-acceptance, consents tied to their text version. */
class Cr003Test {
    private fun RecordingMailer.lastCode() = sent.last { it.template == MailTemplate.EMAIL_VERIFICATION_CODE }.params.getValue("code")
    private fun verify(code: String) = """{"code":"$code"}"""

    @Test fun registrationEmailsACodeThatVerifiesTheAddress() {
        val mail = RecordingMailer()
        withApp(testServices(mailer = mail)) {
            val t = client.register().tokens.accessToken
            assertEquals("priya@example.com", mail.sent.single { it.template == MailTemplate.EMAIL_VERIFICATION_CODE }.to)
            val wrong = client.postJson("/v1/auth/verify-email", verify("000000"), token = t).assertContract("POST", "/v1/auth/verify-email")
            assertEquals("validation_failed", wrong.errorCode())
            val ok = client.postJson("/v1/auth/verify-email", verify(mail.lastCode()), token = t).assertContract("POST", "/v1/auth/verify-email")
            assertTrue("\"email_verified\":true" in ok.bodyAsText(), ok.bodyAsText())
            assertFalse(client.login(deviceId = "dev-2").emailVerificationRequired, "later sign-ins no longer ask")
        }
    }

    @Test fun codesExpireLockAndAreReplacedByResend() {
        val clock = MutableClock()
        val mail = RecordingMailer()
        withApp(testServices(clock, mail)) {
            var t = client.register().tokens.accessToken
            val first = mail.lastCode()
            val resent = client.postJson("/v1/auth/verify-email/resend", "", token = t).assertContract("POST", "/v1/auth/verify-email/resend")
            assertEquals(202, resent.status.value)
            assertEquals("validation_failed", client.postJson("/v1/auth/verify-email", verify(first), token = t).errorCode(), "the old code stopped working")
            repeat(4) { client.postJson("/v1/auth/verify-email", verify("111111"), token = t) }
            assertEquals("challenge_expired", client.postJson("/v1/auth/verify-email", verify(mail.lastCode()), token = t).assertContract("POST", "/v1/auth/verify-email").errorCode(), "locked after 5 wrong")

            client.postJson("/v1/auth/verify-email/resend", "", token = t)
            clock.advance(Duration.ofMinutes(16))
            t = client.login(deviceId = "dev-2").tokens.accessToken
            assertEquals("challenge_expired", client.postJson("/v1/auth/verify-email", verify(mail.lastCode()), token = t).errorCode(), "expired")
        }
    }

    @Test fun resendIsRateLimitedAndNeedsSignIn() = withApp {
        val t = client.register().tokens.accessToken
        repeat(3) { client.postJson("/v1/auth/verify-email/resend", "", token = t) }
        assertEquals("rate_limited", client.postJson("/v1/auth/verify-email/resend", "", token = t).assertContract("POST", "/v1/auth/verify-email/resend").errorCode())
        assertEquals(401, client.post("/v1/auth/verify-email/resend").assertContract("POST", "/v1/auth/verify-email/resend").status.value)
    }

    @Test fun termsAndPrivacyAreAcceptedNeverWithdrawnAndOnlyInTheCurrentVersion() = withApp {
        val t = client.register().tokens.accessToken
        assertEquals(200, client.putJson("/v1/me/consents/terms", """{"granted":true,"version":"2026-11"}""", t).assertContract("PUT", "/v1/me/consents/{consentKey}").status.value)
        assertEquals("validation_failed", client.putJson("/v1/me/consents/terms", """{"granted":false,"version":"2026-11"}""", t).errorCode())
        assertEquals("validation_failed", client.putJson("/v1/me/consents/privacy", """{"granted":true,"version":"2025-01"}""", t).errorCode())
    }

    @Test fun grantsForOutdatedConsentTextsStopCounting() = withApp { s ->
        val t = client.register().tokens.accessToken
        client.allowAi(t)
        client.putJson("/v1/me/consents/automation.blinkit", """{"granted":true,"version":"2026-11"}""", t)
        assertFalse("\"degraded\":true" in client.say(t, client.newChat(t), "Milk and eggs").bodyAsText())

        // The consent texts change: grants given for the old texts no longer count anywhere.
        s.db.connection.use { it.createStatement().execute("update consents set version = '2026-10'") }
        assertTrue("\"degraded\":true" in client.say(t, client.newChat(t), "Milk and eggs").bodyAsText(), "AI consent must be given again")
        val blinkit = Regex("\\{\"key\":\"blinkit\".*?\"consent\":\\{\"automation\":(true|false)").find(client.getAuthed("/v1/providers", t).bodyAsText())!!.groupValues[1]
        assertEquals("false", blinkit)
    }
}
