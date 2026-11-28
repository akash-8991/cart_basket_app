package com.smartcart.server.tasks

import com.smartcart.server.providers.approve
import com.smartcart.server.providers.setFlag
import com.smartcart.server.security.Secrets
import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.postJson
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4.5 exit tests for the threat model's cart-task items:
 * A-06 silent cart mutation, A-07 wrong provider or wrong cart, A-08 handoff link replay.
 */
class TaskThreatsTest {
    private val key get() = UUID.randomUUID().toString()
    private val clock = MutableClock()
    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private val cart = """[{"line_id":"x1","name":"Eggs","quantity":2,"size":"12 pack"},{"line_id":"x2","name":"Bananas","quantity":3,"size":"6 pcs"}]"""
    private fun quoteBody(device: Boolean = false) =
        """{"category":"grocery","cart_lines":$cart,"eligible_providers":["blinkit","bigbasket"],"selected_provider":"bigbasket","objective":"lowest_total"${if (device) ",\"device_providers\":[\"blinkit\"]" else ""}}"""

    private suspend fun HttpClient.unlock(t: String) {
        setFlag("automation.global.enabled", true); setFlag("provider.blinkit.enabled", true); approve("blinkit")
        putJson("/v1/me/consents/automation.blinkit", """{"granted":true,"version":"2026-11"}""", t)
    }

    /** The phone's reading for Blinkit (CR-005); a task for a provider the phone reads needs it first. */
    private suspend fun HttpClient.readOnPhone(t: String, q: String) = postJson("/v1/comparison/quotes/$q/observations", READING.replace("NOW", clock.now.toString()), key, t)

    private val READING = """{"provider":"blinkit","captured_at":"NOW","outcome":"read","adapter":{"key":"teststore","version":"1"},"offers":[{"line_id":"x1","found":true,"name":"Eggs 12 pack","unit_price":{"amount_minor":8400,"currency":"INR"},"in_stock":true},{"line_id":"x2","found":true,"name":"Bananas 6 pcs","unit_price":{"amount_minor":4800,"currency":"INR"},"in_stock":true}],"fees":{"delivery_fee":{"amount_minor":500,"currency":"INR"},"packaging_fee":{"amount_minor":0,"currency":"INR"},"platform_fee":{"amount_minor":0,"currency":"INR"},"small_cart_fee":{"amount_minor":0,"currency":"INR"},"taxes":{"amount_minor":0,"currency":"INR"}}}"""

    private suspend fun HttpClient.quote(t: String, device: Boolean = false) = postJson("/v1/comparison/quotes", quoteBody(device), key, t).obj().s("quote_id")
    private suspend fun HttpClient.confirm(t: String, q: String, p: String = "blinkit") =
        postJson("/v1/comparison/quotes/$q/confirm", """{"provider":"$p","acknowledged_switch":true}""", key, t)
    private suspend fun HttpClient.task(t: String, q: String, p: String = "blinkit") =
        postJson("/v1/cart-tasks", """{"quote_id":"$q","provider":"$p","task_type":"add_to_cart"}""", key, t).assertContract("POST", "/v1/cart-tasks")
    private fun ids(task: JsonObject) = task["planned_actions"]!!.jsonArray.joinToString(",") { "\"${it.jsonObject.s("action_id")}\"" }
    private suspend fun HttpClient.approveTask(t: String, task: JsonObject, ids: String = ids(task), nonce: String = task.s("approval_nonce")) =
        postJson("/v1/cart-tasks/${task.s("task_id")}/approve", """{"approved_action_ids":[$ids],"approval_nonce":"$nonce"}""", key, t)
            .assertContract("POST", "/v1/cart-tasks/{taskId}/approve")
    private suspend fun HttpClient.readyTask(t: String): JsonObject {
        val q = quote(t); confirm(t, q)
        val task = task(t, q).obj()
        approveTask(t, task)
        return getAuthed("/v1/cart-tasks/${task.s("task_id")}", t).obj().also { assertEquals("CART_READY", it.s("status")) }
    }

    // ---------------------------------------------------------------- A-06 silent cart mutation

    @Test fun a06_nothingStartsWithoutTheWholePlanApprovedOnce() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        client.unlock(t)
        val q = client.quote(t, device = true); client.readOnPhone(t, q); client.confirm(t, q)
        val task = client.task(t, q).obj()
        val id = task.s("task_id")
        val report = """{"report_seq":1,"status":"RUNNING","actions_used":1,"events":[],"adapter":{"key":"teststore","version":"1"}}"""
        assertEquals("task_state_conflict", client.postJson("/v1/cart-tasks/$id/progress", report, token = t).errorCode(), "no progress before approval")
        val firstStep = "\"${task["planned_actions"]!!.jsonArray.first().jsonObject.s("action_id")}\""
        assertEquals("task_state_conflict", client.approveTask(t, task, ids = firstStep).errorCode(), "part of the plan")
        assertEquals("task_state_conflict", client.approveTask(t, task, nonce = "apn_forged").errorCode(), "a nonce we didn't issue")
        assertEquals("APPROVED", client.approveTask(t, task).obj().s("status"))
        assertEquals("task_state_conflict", client.approveTask(t, task).errorCode(), "the nonce works once")
    }

    @Test fun a06_aKillSwitchOrStopEndsATaskAndNothingMovesItAfter() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        client.unlock(t)
        val q = client.quote(t, device = true); client.readOnPhone(t, q); client.confirm(t, q)
        val task = client.task(t, q).obj()
        client.approveTask(t, task)
        val id = task.s("task_id")
        fun r(seq: Int, ev: Int) = """{"report_seq":$seq,"status":"RUNNING","actions_used":$seq,"events":[{"seq":$ev,"at":"${clock.now}","type":"state_changed","description":"Started","action_id":null}],"adapter":{"key":"teststore","version":"1"}}"""
        client.postJson("/v1/cart-tasks/$id/progress", r(1, 2), token = t).assertContract("POST", "/v1/cart-tasks/{taskId}/progress")
        client.setFlag("provider.blinkit.kill", true)
        val killed = client.getAuthed("/v1/cart-tasks/$id", t).obj()
        assertEquals("FAILED_SAFE", killed.s("status"))
        assertEquals("provider_kill_switch", killed["failure"]!!.jsonObject.s("code"))
        val after = client.postJson("/v1/cart-tasks/$id/progress", r(2, 4), token = t).obj()
        assertEquals("FAILED_SAFE", after.s("status"), "the phone is told to halt")
        assertFalse(after["events"]!!.jsonArray.any { it.jsonObject.s("seq") == "4" }, "nothing after the stop is recorded")
    }

    // ---------------------------------------------------------------- A-07 wrong provider or wrong cart

    @Test fun a07_aTaskIsBoundToTheSnapshotAndTheProviderThePersonConfirmed() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        client.unlock(t)
        val q = client.quote(t)
        assertEquals("conflict", client.task(t, q).errorCode(), "nothing confirmed yet")
        client.confirm(t, q, "bigbasket")
        assertEquals("conflict", client.task(t, q, "blinkit").errorCode(), "confirmed for another app")
        client.confirm(t, q, "blinkit")
        val task = client.task(t, q).obj()
        assertEquals(q, task.s("quote_id"))
        assertEquals(
            listOf("Add Eggs 12 pack × 2", "Add Bananas 6 pcs × 3"),
            task["planned_actions"]!!.jsonArray.map { it.jsonObject }.filter { it.s("type") == "select_item" }.map { it.s("description") },
            "the plan is exactly the snapshot's cart for Blinkit",
        )
    }

    @Test fun a07_aCartThatDiffersFromTheApprovedOneIsNeverReady() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        client.unlock(t)
        val q = client.quote(t, device = true)
        client.readOnPhone(t, q)
        client.confirm(t, q)
        val task = client.task(t, q).obj()
        client.approveTask(t, task)
        val id = task.s("task_id")
        val wrong = """{"report_seq":1,"status":"RUNNING","actions_used":8,"events":[{"seq":2,"at":"${clock.now}","type":"state_changed","description":"Started","action_id":null}],"adapter":{"key":"teststore","version":"1"}}"""
        client.postJson("/v1/cart-tasks/$id/progress", wrong, token = t)
        val oneEggTray = """{"report_seq":2,"status":"CART_READY","actions_used":9,"events":[],"cart":{"verified":true,"lines":[{"line_id":"x1","requested_name":"Eggs 12 pack","matched_name":"Eggs 12 pack","quantity":1,"match":"exact"},{"line_id":"x2","requested_name":"Bananas 6 pcs","matched_name":"Bananas 6 pcs","quantity":3,"match":"exact"}]},"adapter":{"key":"teststore","version":"1"}}"""
        val r = client.postJson("/v1/cart-tasks/$id/progress", oneEggTray, token = t).assertContract("POST", "/v1/cart-tasks/{taskId}/progress").obj()
        assertEquals("FAILED_SAFE", r.s("status"))
        assertEquals("cart_verification_failed", r["failure"]!!.jsonObject.s("code"))
    }

    // ---------------------------------------------------------------- A-08 handoff link replay

    @Test fun a08_theHandoffLinkIsOpaqueShortLivedAndIssuedOncePerCart() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        client.unlock(t)
        val task = client.readyTask(t)
        val id = task.s("task_id")
        val h = client.postJson("/v1/deep-links", """{"task_id":"$id"}""", key, t).assertContract("POST", "/v1/deep-links").obj()
        val url = h.s("handoff_url")
        assertTrue(Regex("^smartcart://handoff/[A-Za-z0-9_-]{16,128}$").matches(url), "matches contracts/schemas/deep-link.schema.json: $url")
        listOf(id, task.s("quote_id"), "priya", "blinkit").forEach { assertFalse(it in url, "no $it in the link") }
        val ttl = Duration.between(clock.now, Instant.parse(h.s("expires_at")))
        assertTrue(ttl <= Duration.ofMinutes(10) && !ttl.isNegative, "at most 10 minutes: $ttl")
        assertEquals("HANDED_OFF", client.getAuthed("/v1/cart-tasks/$id", t).obj().s("status"))
        assertEquals("conflict", client.postJson("/v1/deep-links", """{"task_id":"$id"}""", key, t).assertContract("POST", "/v1/deep-links").errorCode(), "one link per cart")
        val other = client.register("ravi.s", "ravi@example.com", "dev-9").tokens.accessToken
        assertEquals("not_found", client.postJson("/v1/deep-links", """{"task_id":"$id"}""", key, other).errorCode(), "someone else's task")
    }

    @Test fun a08_aTokenIsUsableOnceByItsOwnerForItsAppBeforeItExpires() {
        val tokens = HandoffTokens(Secrets.random(), clock)
        val (token, expires) = tokens.issue("priya", "tsk_1", "blinkit", "com.example.partner")
        assertEquals(clock.now.plus(Duration.ofMinutes(10)), expires)
        assertTrue(tokens.redeem("ravi", token, "com.example.partner") is HandoffTokens.Redeemed.Refused, "someone else")
        assertTrue(tokens.redeem("priya", token, "com.other.app") is HandoffTokens.Redeemed.Refused, "another app")
        val forged = token.dropLast(1) + (if (token.last() == 'A') 'B' else 'A')
        assertTrue(tokens.redeem("priya", forged, "com.example.partner") is HandoffTokens.Redeemed.Refused, "tampered")
        assertTrue(tokens.redeem("priya", "not-a-token", "com.example.partner") is HandoffTokens.Redeemed.Refused, "malformed")
        assertEquals(HandoffTokens.Redeemed.Ok("tsk_1", "blinkit", "com.example.partner"), tokens.redeem("priya", token, "com.example.partner"))
        assertTrue(tokens.redeem("priya", token, "com.example.partner") is HandoffTokens.Redeemed.Refused, "replay")

        val (late, _) = tokens.issue("priya", "tsk_2", "blinkit", null)
        clock.advance(Duration.ofMinutes(10))
        assertTrue(tokens.redeem("priya", late, null) is HandoffTokens.Redeemed.Refused, "expired")
    }
}
