package com.smartcart.server

import com.smartcart.server.providers.setFlag
import com.smartcart.server.support.RecordingMailer
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.login
import com.smartcart.server.support.postJson
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.registerBody
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CR-002: additive contract fields and operations. */
class Cr002Test {
    private val key get() = UUID.randomUUID().toString()

    @Test fun configIsPublicAndExposesOnlyTheAllowList() = withApp {
        client.setFlag("home.category.shopping", true)
        client.setFlag("provider.blinkit.kill", true)
        val r = client.get("/v1/config").assertContract("GET", "/v1/config")
        val body = r.bodyAsText()
        assertEquals(200, r.status.value)
        assertTrue("\"home.category.shopping\":true" in body && "\"chat.ai.enabled\":true" in body, body)
        assertFalse("provider." in body || "kill" in body || "automation.global" in body, "provider flags and kill switches stay server-side: $body")
        assertTrue("\"policy_versions\":{\"terms\":\"2026-11\",\"privacy\":\"2026-11\",\"consent\":\"2026-11\"}" in body, body)
    }

    @Test fun theDeviceModelLabelsTheSession() = withApp {
        val body = registerBody().replace("\"os_version\":\"15\"}", "\"os_version\":\"15\",\"device_model\":\"Pixel 8\"}")
        val r = client.postJson("/v1/auth/register", body, key).assertContract("POST", "/v1/auth/register")
        val token = Regex("\"access_token\":\"([^\"]+)\"").find(r.bodyAsText())!!.groupValues[1]
        client.login(deviceId = "dev-2")
        val sessions = client.getAuthed("/v1/auth/sessions", token).assertContract("GET", "/v1/auth/sessions").bodyAsText()
        assertTrue("\"device_label\":\"Pixel 8\"" in sessions, sessions)
        assertTrue("\"device_label\":\"Android 15\"" in sessions, "a device without a model keeps the OS label")
    }

    @Test fun registrationCanChooseAShortSession() = withApp {
        val r = client.postJson("/v1/auth/register", registerBody().dropLast(1) + ",\"remember_device\":false}", key).assertContract("POST", "/v1/auth/register")
        val expires = Instant.parse(Regex("\"session_expires_at\":\"([^\"]+)\"").find(r.bodyAsText())!!.groupValues[1])
        assertTrue(Duration.between(Instant.parse("2026-11-26T05:00:00Z"), expires) <= Duration.ofHours(12))
    }

    @Test fun privacyModeIsStoredAudittedAndExported() {
        val mail = RecordingMailer()
        withApp(testServices(mailer = mail)) { s ->
            val t = client.register().tokens.accessToken
            val r = client.putJson("/v1/me/privacy-mode", """{"privacy_mode":"normalized_upload"}""", t).assertContract("PUT", "/v1/me/privacy-mode")
            assertTrue("\"privacy_mode\":\"normalized_upload\"" in r.bodyAsText())
            assertTrue("\"privacy_mode\":\"normalized_upload\"" in client.getAuthed("/v1/me", t).bodyAsText())
            assertEquals("validation_failed", client.putJson("/v1/me/privacy-mode", """{"privacy_mode":"everything"}""", t).assertContract("PUT", "/v1/me/privacy-mode").errorCode())
            client.postJson("/v1/me/data/export", """{"password":"correct horse battery"}""", key, t)
            s.dataRequests.processPending()
            assertTrue("\"privacy_mode\":\"normalized_upload\"" in mail.sent.last().params.getValue("export_json"))
            s.db.connection.use { c ->
                val rs = c.createStatement().executeQuery("select count(*) from audit_log where action = 'privacy.mode_set'")
                rs.next(); assertEquals(1, rs.getInt(1))
            }
        }
    }

    @Test fun activityCarriesStableKeysWithoutPersonalData() = withApp {
        val phone = client.register()
        val tablet = client.login(deviceId = "dev-2")
        client.putJson("/v1/me/consents/automation.blinkit", """{"granted":true,"version":"2026-11"}""", phone.tokens.accessToken)
        client.putJson("/v1/me/consents/ai_processing", """{"granted":true,"version":"2026-11"}""", phone.tokens.accessToken)
        client.delete("/v1/auth/sessions/${tablet.sessionId}") { bearerAuth(phone.tokens.accessToken) }
        val body = client.getAuthed("/v1/me/activity", phone.tokens.accessToken).assertContract("GET", "/v1/me/activity").bodyAsText()
        assertTrue("\"summary_key\":\"consent_granted.automation\",\"summary_args\":{\"provider_name\":\"Blinkit\"}" in body, body)
        assertTrue("\"summary_key\":\"consent_granted.ai_processing\",\"summary_args\":null" in body, body)
        assertTrue("\"summary_key\":\"session_revoked.one\",\"summary_args\":{\"device_label\":\"Android 15\"}" in body, body)
        assertFalse("priya" in body || "@" in body, "no personal data in summaries or arguments")
    }

    @Test fun zeptoIsMarkedHidden() = withApp {
        val body = client.getAuthed("/v1/providers", client.register().tokens.accessToken).assertContract("GET", "/v1/providers").bodyAsText()
        assertTrue(Regex("\\{\"key\":\"zepto\".*?\"hidden\":true\\}").containsMatchIn(body), body)
        assertEquals(10, Regex("\"hidden\":false").findAll(body).count())
    }
}
