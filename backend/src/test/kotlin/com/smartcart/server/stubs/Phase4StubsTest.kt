package com.smartcart.server.stubs

import com.smartcart.server.Services
import com.smartcart.server.providers.approve
import com.smartcart.server.providers.setFlag
import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.TestDb
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.assertErrorEnvelope
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.postJson
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.testConfig
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase4StubsTest {
    private val key get() = UUID.randomUUID().toString()
    private suspend fun HttpResponse.field(name: String) = Regex("\"$name\":\"([^\"]+)\"").find(bodyAsText())!!.groupValues[1]
    private val quoteBody = """{"category":"grocery","eligible_providers":["blinkit","bigbasket","swiggy_instamart"],"selected_provider":"bigbasket","objective":"lowest_total"}"""

    /** Everything Phase 4 automation must have before a task can start. */
    private suspend fun HttpClient.unlockBlinkit(token: String) {
        setFlag("automation.global.enabled", true)
        setFlag("provider.blinkit.enabled", true)
        approve("blinkit")
        putJson("/v1/me/consents/automation.blinkit", """{"granted":true,"version":"2026-11"}""", token)
    }

    private suspend fun HttpClient.confirmedQuote(t: String): String {
        val q = postJson("/v1/comparison/quotes", quoteBody, key, t).field("quote_id")
        postJson("/v1/comparison/quotes/$q/confirm", """{"provider":"blinkit","acknowledged_switch":true}""", key, t)
        return q
    }

    private suspend fun HttpClient.newTask(t: String, q: String) =
        postJson("/v1/cart-tasks", """{"quote_id":"$q","provider":"blinkit","task_type":"add_to_cart"}""", key, t).assertContract("POST", "/v1/cart-tasks")

    private fun approval(ids: String = "\"a1\",\"a2\",\"a3\",\"a4\",\"a5\",\"a6\",\"a7\"", nonce: String = UUID.randomUUID().toString()) =
        """{"approved_action_ids":[$ids],"approval_nonce":"$nonce"}"""

    private suspend fun HttpClient.plannedIds(t: String, task: String): String =
        Regex("\"action_id\":\"([^\"]+)\"").findAll(getAuthed("/v1/cart-tasks/$task", t).bodyAsText()).joinToString(",") { "\"${it.groupValues[1]}\"" }

    @Test fun compareConfirmApproveRunAndHandOff() = withApp {
        val t = client.register().tokens.accessToken
        client.unlockBlinkit(t)
        val created = client.postJson("/v1/comparison/quotes", quoteBody, key, t).assertContract("POST", "/v1/comparison/quotes")
        assertEquals(201, created.status.value)
        assertEquals("phase4", created.headers["X-SmartCart-Stub"])
        val q = created.field("quote_id")
        assertTrue(Instant.parse(created.field("expires_at")).isAfter(Instant.now().minusSeconds(3600 * 24 * 365)), "re-dated, not the fixture's date")
        client.getAuthed("/v1/comparison/quotes/$q", t).assertContract("GET", "/v1/comparison/quotes/{quoteId}")

        // The user picked BigBasket; switching to Blinkit needs an explicit acknowledgement.
        val unacknowledged = client.postJson("/v1/comparison/quotes/$q/confirm", """{"provider":"blinkit","acknowledged_switch":false}""", key, t)
            .assertContract("POST", "/v1/comparison/quotes/{quoteId}/confirm")
        assertEquals("conflict", unacknowledged.errorCode())
        client.postJson("/v1/comparison/quotes/$q/confirm", """{"provider":"blinkit","acknowledged_switch":true}""", key, t)
            .assertContract("POST", "/v1/comparison/quotes/{quoteId}/confirm").also { assertEquals(200, it.status.value) }

        val task = client.newTask(t, q).also { assertTrue("\"status\":\"AWAITING_APPROVAL\"" in it.bodyAsText()) }.field("task_id")
        val ids = client.plannedIds(t, task)
        val partial = client.postJson("/v1/cart-tasks/$task/approve", approval(ids.substringBefore(",")), key, t).assertContract("POST", "/v1/cart-tasks/{taskId}/approve")
        assertEquals("task_state_conflict", partial.errorCode(), "partial approval is refused")
        val running = client.postJson("/v1/cart-tasks/$task/approve", approval(ids), key, t).assertContract("POST", "/v1/cart-tasks/{taskId}/approve")
        assertTrue("\"status\":\"RUNNING\"" in running.bodyAsText())
        val ready = client.getAuthed("/v1/cart-tasks/$task", t).assertContract("GET", "/v1/cart-tasks/{taskId}")
        assertTrue("\"status\":\"CART_READY\"" in ready.bodyAsText() && "\"task_id\":\"$task\"" in ready.bodyAsText())

        val handoff = client.postJson("/v1/deep-links", """{"task_id":"$task"}""", key, t).assertContract("POST", "/v1/deep-links")
        val url = handoff.field("handoff_url")
        assertTrue(url.startsWith("smartcart://handoff/") && task !in url && q !in url, "opaque: $url")
        assertTrue("\"status\":\"HANDED_OFF\"" in client.getAuthed("/v1/cart-tasks/$task", t).bodyAsText())
        assertEquals("conflict", client.postJson("/v1/deep-links", """{"task_id":"$task"}""", key, t).assertContract("POST", "/v1/deep-links").errorCode(), "one handoff per cart")
    }

    @Test fun tasksNeedTheKillSwitchesOffTheProviderApprovedAndConsent() = withApp {
        val t = client.register().tokens.accessToken
        val q = client.confirmedQuote(t)
        assertEquals("provider_disabled", client.newTask(t, q).errorCode())
        client.setFlag("automation.global.enabled", true); client.setFlag("provider.blinkit.enabled", true)
        assertEquals("provider_disabled", client.newTask(t, q).errorCode(), "no approval record yet")
        client.approve("blinkit")
        assertEquals("consent_required", client.newTask(t, q).errorCode())
        client.putJson("/v1/me/consents/automation.blinkit", """{"granted":true,"version":"2026-11"}""", t)
        assertEquals(201, client.newTask(t, q).status.value)
        client.setFlag("provider.blinkit.kill", true)
        assertEquals("provider_disabled", client.newTask(t, q).errorCode(), "kill switch")
    }

    @Test fun anUnconfirmedQuoteCantStartATask() = withApp {
        val t = client.register().tokens.accessToken
        client.unlockBlinkit(t)
        val q = client.postJson("/v1/comparison/quotes", quoteBody, key, t).field("quote_id")
        assertEquals("conflict", client.newTask(t, q).errorCode())
    }

    @Test fun stopIsFinal() = withApp {
        val t = client.register().tokens.accessToken
        client.unlockBlinkit(t)
        val task = client.newTask(t, client.confirmedQuote(t)).field("task_id")
        val ids = client.plannedIds(t, task)
        client.postJson("/v1/cart-tasks/$task/approve", approval(ids), key, t)
        val stopped = client.postJson("/v1/cart-tasks/$task/stop", """{"reason":"emergency_stop"}""", token = t).assertContract("POST", "/v1/cart-tasks/{taskId}/stop")
        assertTrue("\"status\":\"CANCELLED\"" in stopped.bodyAsText())
        assertTrue("\"status\":\"CANCELLED\"" in client.getAuthed("/v1/cart-tasks/$task", t).bodyAsText(), "a stopped task never resumes")
        assertEquals("task_state_conflict", client.postJson("/v1/cart-tasks/$task/approve", approval(ids), key, t).errorCode())
        val cp = client.postJson("/v1/cart-tasks/$task/checkpoint", """{"checkpoint_id":"cp_01","decision":"approve"}""", key, t).assertContract("POST", "/v1/cart-tasks/{taskId}/checkpoint")
        assertEquals("task_state_conflict", cp.errorCode())
    }

    @Test fun aReusedApprovalNonceWithDifferentActionsIsRefused() = withApp {
        val t = client.register().tokens.accessToken
        client.unlockBlinkit(t)
        val task = client.newTask(t, client.confirmedQuote(t)).field("task_id")
        val ids = client.plannedIds(t, task)
        assertEquals("task_state_conflict", client.postJson("/v1/cart-tasks/$task/approve", approval(ids.substringBefore(","), "n-1"), key, t).errorCode())
        assertEquals("task_state_conflict", client.postJson("/v1/cart-tasks/$task/approve", approval(ids, "n-1"), key, t).errorCode())
    }

    @Test fun unapprovedTasksAndQuotesExpire() {
        val clock = MutableClock(Instant.now())
        withApp(testServices(clock)) {
            val t = client.register().tokens.accessToken
            client.unlockBlinkit(t)
            val task = client.newTask(t, client.confirmedQuote(t)).field("task_id")
            val ids = client.plannedIds(t, task)
            val staleQuote = client.postJson("/v1/comparison/quotes", quoteBody, key, t).field("quote_id")
            clock.advance(Duration.ofMinutes(31))
            val t2 = com.smartcart.server.support.loginBody().let { client.postJson("/v1/auth/login", it) }.field("access_token")
            assertTrue("\"status\":\"EXPIRED\"" in client.getAuthed("/v1/cart-tasks/$task", t2).bodyAsText())
            assertEquals("task_expired", client.postJson("/v1/cart-tasks/$task/approve", approval(ids), key, t2).assertContract("POST", "/v1/cart-tasks/{taskId}/approve").errorCode())
            val expired = client.postJson("/v1/comparison/quotes/$staleQuote/confirm", """{"provider":"bigbasket","acknowledged_switch":false}""", key, t2)
                .assertContract("POST", "/v1/comparison/quotes/{quoteId}/confirm")
            assertEquals("quote_expired", expired.errorCode())
        }
    }

    @Test fun usersOnlySeeTheirOwnQuotesTasksAndImports() = withApp {
        val priya = client.register().tokens.accessToken
        val ravi = client.register(userId = "ravi.s", email = "ravi@example.com", deviceId = "dev-9").tokens.accessToken
        client.unlockBlinkit(priya)
        val q = client.confirmedQuote(priya)
        val task = client.newTask(priya, q).field("task_id")
        assertEquals(404, client.getAuthed("/v1/comparison/quotes/$q", ravi).assertContract("GET", "/v1/comparison/quotes/{quoteId}").status.value)
        assertEquals(404, client.getAuthed("/v1/cart-tasks/$task", ravi).assertContract("GET", "/v1/cart-tasks/{taskId}").status.value)
        client.setFlag("history.import.enabled", true)
        client.putJson("/v1/me/consents/history.blinkit", """{"granted":true,"version":"2026-11"}""", priya)
        val imp = client.postJson("/v1/history/import", """{"providers":["blinkit"],"fields":["order_date","items"],"upload_mode":"device_only"}""", key, priya).field("session_id")
        assertEquals(404, client.getAuthed("/v1/history/import/$imp", ravi).assertContract("GET", "/v1/history/import/{sessionId}").status.value)
    }

    @Test fun historyImportNeedsTheFlagAndConsent() = withApp {
        val t = client.register().tokens.accessToken
        val body = """{"providers":["blinkit"],"fields":["order_date","items","amounts"],"upload_mode":"normalized_upload"}"""
        assertEquals("provider_disabled", client.postJson("/v1/history/import", body, key, t).assertContract("POST", "/v1/history/import").errorCode())
        client.setFlag("history.import.enabled", true)
        assertEquals("consent_required", client.postJson("/v1/history/import", body, key, t).errorCode())
        client.putJson("/v1/me/consents/history.blinkit", """{"granted":true,"version":"2026-11"}""", t)
        val started = client.postJson("/v1/history/import", body, key, t).assertContract("POST", "/v1/history/import")
        assertEquals(201, started.status.value)
        val review = client.getAuthed("/v1/history/import/${started.field("session_id")}", t).assertContract("GET", "/v1/history/import/{sessionId}")
        assertTrue("\"status\":\"awaiting_review\"" in review.bodyAsText(), review.bodyAsText())
    }

    @Test fun ordersCanBeListedFilteredAndDeleted() = withApp {
        val t = client.register().tokens.accessToken
        val all = client.getAuthed("/v1/history/orders", t).assertContract("GET", "/v1/history/orders").bodyAsText()
        val first = Regex("\"order_id\":\"([^\"]+)\"").find(all)!!.groupValues[1]
        assertFalse("\"provider\":\"blinkit\"" in client.getAuthed("/v1/history/orders?provider=zomato", t).bodyAsText())
        assertEquals(204, client.delete("/v1/history/orders/$first") { bearerAuth(t) }.assertContract("DELETE", "/v1/history/orders/{orderId}").status.value)
        assertEquals(404, client.delete("/v1/history/orders/$first") { bearerAuth(t) }.assertContract("DELETE", "/v1/history/orders/{orderId}").status.value)
        assertFalse(first in client.getAuthed("/v1/history/orders", t).bodyAsText())
    }

    @Test fun dashboardAndRecommendations() = withApp {
        val t = client.register().tokens.accessToken
        client.getAuthed("/v1/dashboard/summary?month=2026-11", t).assertContract("GET", "/v1/dashboard/summary")
        client.getAuthed("/v1/dashboard/monthly?from=2026-06&to=2026-11", t).assertContract("GET", "/v1/dashboard/monthly")
        val recs = client.getAuthed("/v1/recommendations?category=grocery", t).assertContract("GET", "/v1/recommendations").bodyAsText()
        assertFalse("\"category\":\"food\"" in recs)
        assertEquals(204, client.postJson("/v1/recommendations/rec_1/feedback", """{"useful":true}""", token = t).assertContract("POST", "/v1/recommendations/{recommendationId}/feedback").status.value)
        assertEquals("validation_failed", client.getAuthed("/v1/dashboard/summary?month=November", t).assertErrorEnvelope().errorCode())
    }

    @Test fun productionHasNoStubs() = withApp(Services(testConfig(stubsPhase4 = false), TestDb.fresh())) {
        val t = client.register().tokens.accessToken
        for ((method, path) in listOf("GET" to "/v1/dashboard/summary", "GET" to "/v1/recommendations", "GET" to "/v1/history/orders", "GET" to "/v1/cart-tasks/tsk_x")) {
            val r = client.getAuthed(path, t).assertErrorEnvelope()
            assertEquals("service_unavailable", r.errorCode(), "$method $path")
            assertEquals(null, r.headers["X-SmartCart-Stub"])
        }
        assertEquals("service_unavailable", client.postJson("/v1/comparison/quotes", quoteBody, key, t).errorCode())
    }
}
