package com.smartcart.server.admin

import com.smartcart.server.Services
import com.smartcart.server.providers.admin
import com.smartcart.server.support.ADMIN_TOKEN
import com.smartcart.server.support.TestDb
import com.smartcart.server.support.assertErrorEnvelope
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.register
import com.smartcart.server.support.testConfig
import com.smartcart.server.support.withApp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminApiTest {
    private val flip = """{"enabled":true,"reason":"pilot"}"""

    @Test fun needsTheAdminTokenAndANamedOperator() = withApp {
        assertEquals("unauthenticated", client.get("/admin/flags").assertErrorEnvelope().errorCode())
        assertEquals("unauthenticated", client.admin("PUT", "/admin/flags/provider.uber.enabled", flip, token = "x".repeat(40)).errorCode())
        assertEquals("validation_failed", client.admin("PUT", "/admin/flags/provider.uber.enabled", flip, operator = null).errorCode())
        assertEquals("validation_failed", client.admin("PUT", "/admin/flags/provider.uber.enabled", flip, operator = "bad name; drop").errorCode())
    }

    @Test fun userAccessTokensDontOpenTheAdminApi() = withApp {
        val t = client.register().tokens.accessToken
        assertEquals("unauthenticated", client.get("/admin/flags") { bearerAuth(t); header("X-Operator", "priya") }.errorCode())
    }

    @Test fun changesNeedAReasonAndAKnownKey() = withApp {
        assertEquals("validation_failed", client.admin("PUT", "/admin/flags/provider.uber.enabled", """{"enabled":true,"reason":" "}""").errorCode())
        assertEquals("not_found", client.admin("PUT", "/admin/flags/provider.paytm.enabled", flip).errorCode())
        assertEquals("not_found", client.admin("PUT", "/admin/providers/paytm/approval", """{"adapter_version":"1","approved_by":"x","record_url":"u","reason":"r"}""").errorCode())
        assertEquals("not_found", client.admin("DELETE", "/admin/providers/uber/approval", """{"reason":"nothing to revoke"}""").errorCode())
    }

    @Test fun everyChangeIsAuditedWithItsOperatorAndReason() = withApp { s ->
        assertEquals(200, client.admin("PUT", "/admin/flags/provider.uber.kill", """{"enabled":true,"reason":"incident INC-42"}""").status.value)
        val listing = client.get("/admin/flags") { bearerAuth(ADMIN_TOKEN); header("X-Operator", "ops.akash") }.bodyAsText()
        assertTrue("{\"key\":\"provider.uber.kill\",\"enabled\":true" in listing && "\"updated_by\":\"ops.akash\",\"reason\":\"incident INC-42\"" in listing, listing)
        s.db.connection.use { c ->
            val rs = c.createStatement().executeQuery("select actor_user_id, target, outcome, detail->>'reason' from audit_log where action = 'admin.flag_set'")
            rs.next()
            assertEquals(listOf("operator:ops.akash", "provider.uber.kill", "on", "incident INC-42"), (1..4).map { rs.getString(it) })
        }
    }

    @Test fun theAdminApiDoesNotExistWithoutAToken() = withApp(Services(testConfig().copy(adminToken = null), TestDb.fresh())) {
        assertEquals(404, client.get("/admin/flags") { bearerAuth(ADMIN_TOKEN); header("X-Operator", "ops") }.status.value)
    }
}
