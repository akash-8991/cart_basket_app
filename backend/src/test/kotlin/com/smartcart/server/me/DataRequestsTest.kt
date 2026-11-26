package com.smartcart.server.me

import com.smartcart.server.mail.MailTemplate
import com.smartcart.server.support.PASSWORD
import com.smartcart.server.support.RecordingMailer
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.assertErrorEnvelope
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.loginBody
import com.smartcart.server.support.postJson
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.statement.bodyAsText
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataRequestsTest {
    private val key get() = UUID.randomUUID().toString()
    private fun stepUp(pw: String = PASSWORD) = """{"password":"$pw"}"""
    private fun delete(scope: String, pw: String = PASSWORD) = """{"scope":"$scope","password":"$pw"}"""

    private fun count(s: com.smartcart.server.Services, sql: String): Int = s.db.connection.use { c ->
        c.createStatement().executeQuery(sql).use { it.next(); it.getInt(1) }
    }

    @Test fun exportNeedsThePasswordThenEmailsTheData() {
        val mail = RecordingMailer()
        withApp(testServices(mailer = mail)) { s ->
            val t = client.register().tokens.accessToken
            client.putJson("/v1/me/consents/automation.blinkit", """{"granted":true,"version":"2026-11"}""", t)
            val wrong = client.postJson("/v1/me/data/export", stepUp("not my password"), key, t).assertContract("POST", "/v1/me/data/export")
            assertEquals("invalid_credentials", wrong.errorCode())

            val ok = client.postJson("/v1/me/data/export", stepUp(), key, t).assertContract("POST", "/v1/me/data/export")
            assertEquals(202, ok.status.value)
            assertTrue("\"status\":\"queued\"" in ok.bodyAsText() && "\"sla_days\":7" in ok.bodyAsText())

            assertEquals(1, s.dataRequests.processPending())
            val sent = mail.sent.single { it.template == MailTemplate.DATA_EXPORT }
            assertEquals("priya@example.com", sent.to)
            val export = sent.params.getValue("export_json")
            assertTrue("\"user_id\":\"priya.k\"" in export && "automation.blinkit" in export && "consent_history" in export)
            assertTrue(PASSWORD !in export && "argon2" !in export, "no credentials in the export")
            assertTrue("data_exported" in client.getAuthed("/v1/me/activity", t).bodyAsText())
            assertEquals(1, count(s, "select count(*) from data_requests where status = 'completed'"))
        }
    }

    @Test fun exportRequestsAreIdempotentAndRateLimited() = withApp { s ->
        val t = client.register().tokens.accessToken
        val k = key
        val a = client.postJson("/v1/me/data/export", stepUp(), k, t).bodyAsText()
        val b = client.postJson("/v1/me/data/export", stepUp(), k, t).bodyAsText()
        assertEquals(a, b, "a retry returns the original request")
        assertEquals(1, count(s, "select count(*) from data_requests"))

        repeat(2) { assertEquals(202, client.postJson("/v1/me/data/export", stepUp(), key, t).status.value) }
        val limited = client.postJson("/v1/me/data/export", stepUp(), key, t).assertContract("POST", "/v1/me/data/export")
        assertEquals("rate_limited", limited.errorCode())
        assertEquals("validation_failed", client.postJson("/v1/me/data/export", stepUp(), null, t).assertErrorEnvelope().errorCode())
    }

    @Test fun repeatedWrongStepUpPasswordsLockStepUp() = withApp {
        val t = client.register().tokens.accessToken
        repeat(5) { client.postJson("/v1/me/data/export", stepUp("guess number $it"), key, t) }
        assertEquals("rate_limited", client.postJson("/v1/me/data/export", stepUp(), key, t).errorCode())
    }

    @Test fun deletingTheAccountSignsOutAtOnceThenErasesEverything() {
        val mail = RecordingMailer()
        withApp(testServices(mailer = mail)) { s ->
            val a = client.register()
            val t = a.tokens.accessToken
            client.putJson("/v1/me/consents/ai_processing", """{"granted":true,"version":"2026-11"}""", t)
            val r = client.postJson("/v1/me/data/delete", delete("account"), key, t).assertContract("POST", "/v1/me/data/delete")
            assertEquals(202, r.status.value)
            assertTrue("\"sla_days\":30" in r.bodyAsText())

            // Immediately: every session is gone and the account can't be used or recovered.
            assertEquals(401, client.getAuthed("/v1/me", t).status.value)
            assertEquals("invalid_credentials", client.postJson("/v1/auth/login", loginBody()).errorCode())
            client.postJson("/v1/auth/forgot-password", """{"user_id":"priya.k","email":"priya@example.com"}""")
            assertTrue(mail.sent.none { it.template == MailTemplate.PASSWORD_RESET_CODE })

            assertEquals(1, s.dataRequests.processPending())
            for (table in listOf("users", "sessions", "refresh_tokens", "consents", "consent_events", "activity_events", "data_requests")) {
                assertEquals(0, count(s, "select count(*) from $table"), "$table is empty")
            }
            assertTrue(count(s, "select count(*) from audit_log where actor_user_id = 'priya.k'") > 0, "the audit trail survives")
            assertEquals(MailTemplate.ACCOUNT_DELETED, mail.sent.last().template)
            assertEquals(201, client.postJson("/v1/auth/register", com.smartcart.server.support.registerBody(), key).status.value, "the user ID is free again")
        }
    }

    @Test fun deletingChatHistoryKeepsTheAccount() = withApp { s ->
        val t = client.register().tokens.accessToken
        var erased: String? = null
        s.dataRequests.onErase("chat") { erased = it }
        assertEquals(202, client.postJson("/v1/me/data/delete", delete("chat"), key, t).assertContract("POST", "/v1/me/data/delete").status.value)
        s.dataRequests.processPending()
        assertEquals("priya.k", erased)
        val activity = client.getAuthed("/v1/me/activity", t).bodyAsText()
        assertTrue("\"type\":\"data_deleted\",\"summary\":\"Your chat history was deleted.\"" in activity, activity)
    }

    @Test fun deletionValidatesScopeAndPassword() = withApp {
        val t = client.register().tokens.accessToken
        assertEquals("validation_failed", client.postJson("/v1/me/data/delete", delete("everything"), key, t).assertErrorEnvelope().errorCode())
        assertEquals("invalid_credentials", client.postJson("/v1/me/data/delete", delete("account", "wrong password"), key, t).assertContract("POST", "/v1/me/data/delete").errorCode())
        assertEquals(200, client.getAuthed("/v1/me", t).status.value, "a failed request changes nothing")
    }
}
