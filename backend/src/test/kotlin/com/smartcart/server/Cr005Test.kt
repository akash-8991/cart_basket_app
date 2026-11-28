package com.smartcart.server

import com.smartcart.server.providers.approve
import com.smartcart.server.providers.setFlag
import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.login
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CR-005: the phone reports the prices it read and the progress of the tasks it runs. The server treats both
 * as untrusted readings: it computes every figure, checks every transition, enforces the limits and checks
 * the cart itself.
 */
class Cr005Test {
    private val key get() = UUID.randomUUID().toString()
    private val clock = MutableClock()
    private val quoteBody = """{"category":"grocery","eligible_providers":["blinkit","bigbasket","swiggy_instamart"],"selected_provider":"bigbasket","objective":"lowest_total","device_providers":["blinkit"]}"""
    private val adapter = """"adapter":{"key":"teststore","version":"1"}"""

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.result(p: String) = this["provider_results"]!!.jsonArray.map { it.jsonObject }.single { it["provider"]!!.jsonPrimitive.content == p }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.total() = (this["total_estimate"] as? JsonObject)?.get("amount_minor")?.jsonPrimitive?.long

    private fun offer(line: String, name: String, minor: Long, inStock: Boolean = true) =
        """{"line_id":"$line","found":true,"name":"$name","unit_price":{"amount_minor":$minor,"currency":"INR"},"in_stock":$inStock}"""

    private val testStoreOffers = listOf(offer("l1", "Toned milk 1 L", 2800), offer("l2", "Brown bread 400 g", 4500), offer("l3", "Eggs 12 pack", 8400), offer("l4", "Bananas 6 pcs", 4800))
    private fun zero() = """{"amount_minor":0,"currency":"INR"}"""
    private fun fees(platform: String = zero()) =
        """{"delivery_fee":{"amount_minor":500,"currency":"INR"},"packaging_fee":${zero()},"platform_fee":$platform,"small_cart_fee":${zero()},"taxes":${zero()}}"""

    private fun reading(offers: List<String> = testStoreOffers, fees: String = fees(), provider: String = "blinkit") =
        """{"provider":"$provider","captured_at":"${clock.now}","outcome":"read",$adapter,"offers":[${offers.joinToString(",")}],"fees":$fees,"eta_minutes":null}"""

    private suspend fun HttpClient.quote(t: String) = postJson("/v1/comparison/quotes", quoteBody, key, t).assertContract("POST", "/v1/comparison/quotes").obj()
    private suspend fun HttpClient.observe(t: String, q: String, body: String) =
        postJson("/v1/comparison/quotes/$q/observations", body, key, t).assertContract("POST", "/v1/comparison/quotes/{quoteId}/observations")

    @Test fun thePhonesReadingsAreTurnedIntoTheServersFigures() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        val created = client.quote(t)
        assertEquals("in_progress", created.s("status"), "waiting for the phone")
        assertEquals("not_checked", created.result("blinkit").s("availability"))
        assertEquals("device", created.result("blinkit").s("source"))
        assertEquals("server", created.result("bigbasket").s("source"))
        val q = created.s("quote_id")

        val read = client.observe(t, q, reading()).also { assertEquals(200, it.status.value) }.obj()
        val blinkit = read.result("blinkit")
        assertEquals("available", blinkit.s("availability"))
        assertEquals(23300L + 500L, blinkit.total(), "items read on the phone plus its stated delivery fee")
        assertEquals(1, blinkit["rank"]!!.jsonPrimitive.int)
        assertEquals("complete", read.s("status"))
        val rec = read["recommendation"]!!.jsonObject
        assertEquals("blinkit", rec.s("provider"))
        assertEquals("cheapest", rec.s("label"))
        assertEquals(26400L - 23800L, rec["savings_vs_selected"]!!.jsonObject["amount_minor"]!!.jsonPrimitive.long)
        assertEquals(read, client.getAuthed("/v1/comparison/quotes/$q", t).assertContract("GET", "/v1/comparison/quotes/{quoteId}").obj())

        assertEquals("conflict", client.observe(t, q, reading(testStoreOffers.take(1))).errorCode(), "one reading per provider")
        assertEquals("conflict", client.observe(t, q, reading(provider = "bigbasket")).errorCode(), "only providers the phone said it would read")
    }

    @Test fun readingsAreCheckedAgainstTheQuote() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        val q = client.quote(t).s("quote_id")
        assertEquals("validation_failed", client.observe(t, q, reading(listOf(offer("l9", "Paneer", 9000)))).errorCode(), "a line that isn't in the quote")
        val dollars = reading(listOf(offer("l1", "Toned milk 1 L", 2800).replace("INR", "USD")))
        assertEquals("validation_failed", client.observe(t, q, dollars).errorCode(), "another currency")
        val noReason = """{"provider":"blinkit","captured_at":"${clock.now}","outcome":"not_read",$adapter}"""
        assertEquals("validation_failed", client.observe(t, q, noReason).errorCode())
        assertEquals("not_found", client.observe(t, "qt_nope", reading()).errorCode())

        val other = client.register("ravi.s", "ravi@example.com", "dev-9").tokens.accessToken
        assertEquals("not_found", client.observe(other, q, reading()).errorCode(), "one user's readings never touch another's quote (A-15)")

        clock.advance(Duration.ofMinutes(6))
        assertEquals("quote_expired", client.observe(t, q, reading()).errorCode())
    }

    @Test fun unstatedFeesUnreadAppsAndSilenceNeverEarnCheapest() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        val unstated = client.observe(t, client.quote(t).s("quote_id"), reading(fees = fees(platform = "null"))).obj()
        assertEquals(null, unstated.result("blinkit").total(), "a fee the app doesn't state isn't assumed to be zero")
        assertTrue("platform_fee" in unstated.result("blinkit")["unavailable_fields"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("swiggy_instamart", unstated["recommendation"]!!.jsonObject.s("provider"))

        val login = """{"provider":"blinkit","captured_at":"${clock.now}","outcome":"not_read","not_read_reason":"login_required",$adapter}"""
        val notRead = client.observe(t, client.quote(t).s("quote_id"), login).obj()
        assertEquals("failed", notRead.result("blinkit").s("availability"))
        assertEquals("partial_coverage", notRead.s("status"))
        assertEquals("lowest_observed_estimate", notRead["recommendation"]!!.jsonObject.s("label"))

        val q = client.quote(t).s("quote_id")
        clock.advance(Duration.ofSeconds(121))
        val silent = client.getAuthed("/v1/comparison/quotes/$q", t).assertContract("GET", "/v1/comparison/quotes/{quoteId}").obj()
        assertEquals("failed", silent.result("blinkit").s("availability"), "no reading in time")
        assertEquals("partial_coverage", silent.s("status"))
    }

    // ---------------------------------------------------------------- task progress

    private suspend fun HttpClient.deviceTask(t: String, cart: Boolean = true): String {
        setFlag("automation.global.enabled", true); setFlag("provider.blinkit.enabled", true); approve("blinkit")
        putJson("/v1/me/consents/automation.blinkit", """{"granted":true,"version":"2026-11"}""", t)
        val q = quote(t).s("quote_id")
        if (cart) observe(t, q, reading())
        postJson("/v1/comparison/quotes/$q/confirm", """{"provider":"blinkit","acknowledged_switch":true}""", key, t)
        val task = postJson("/v1/cart-tasks", """{"quote_id":"$q","provider":"blinkit","task_type":"add_to_cart"}""", key, t).obj()
        val ids = task["planned_actions"]!!.jsonArray.joinToString(",") { "\"${it.jsonObject.s("action_id")}\"" }
        val approved = postJson("/v1/cart-tasks/${task.s("task_id")}/approve", """{"approved_action_ids":[$ids],"approval_nonce":"${task.s("approval_nonce")}"}""", key, t).obj()
        assertEquals("APPROVED", approved.s("status"), "a device-run task waits for the phone")
        return task.s("task_id")
    }

    private fun event(seq: Int, type: String, text: String) = """{"seq":$seq,"at":"${clock.now}","type":"$type","description":"$text","action_id":null}"""

    private fun report(seq: Int, status: String, used: Int, events: List<String>, extra: String = "") =
        """{"report_seq":$seq,"status":"$status","actions_used":$used,"events":[${events.joinToString(",")}]$extra,$adapter}"""

    private fun cartOf(vararg lines: Pair<String, Int>) = ""","cart":{"verified":true,"lines":[${lines.withIndex().joinToString(",") { (i, l) ->
        """{"line_id":"l${i + 1}","requested_name":"${l.first}","matched_name":"${l.first}","quantity":${l.second},"line_total":{"amount_minor":${1000 * l.second},"currency":"INR"},"match":"exact"}"""
    }}]}"""

    private val approvedCart = cartOf("Toned milk 1 L" to 2, "Brown bread 400 g" to 1, "Eggs 12 pack" to 1, "Bananas 6 pcs" to 1)

    private suspend fun HttpClient.progress(t: String, task: String, body: String) =
        postJson("/v1/cart-tasks/$task/progress", body, token = t).assertContract("POST", "/v1/cart-tasks/{taskId}/progress")

    @Test fun thePhonesProgressIsAppliedAlongTheStateMachine() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        val task = client.deviceTask(t)
        assertEquals("APPROVED", client.getAuthed("/v1/cart-tasks/$task", t).obj().s("status"), "not simulated: only the phone moves it")

        val running = client.progress(t, task, report(1, "RUNNING", 1, listOf(event(2, "state_changed", "Started in Test Store"))))
        assertEquals(200, running.status.value)
        assertEquals(running.bodyAsText(), client.progress(t, task, report(1, "RUNNING", 1, listOf(event(2, "state_changed", "Started in Test Store")))).bodyAsText(), "a repeated report gets the same answer")

        val needs = ""","needs_user_action":{"reason":"login_required","message":"Test Store wants you to sign in."}"""
        assertEquals("NEEDS_USER_ACTION", client.progress(t, task, report(2, "NEEDS_USER_ACTION", 1, listOf(event(3, "halt", "Test Store wants you to sign in.")), needs)).obj().s("status"))
        client.progress(t, task, report(3, "RUNNING", 1, listOf(event(4, "state_changed", "Resumed by you"))))
        val ready = client.progress(t, task, report(4, "CART_READY", 12, listOf(event(5, "cart_verified", "Cart checked"), event(6, "state_changed", "Your cart is ready")), approvedCart)).obj()
        assertEquals("CART_READY", ready.s("status"))
        val cart = ready["cart"]!!.jsonObject
        assertEquals("true", cart.s("verified"))
        assertEquals("false", cart.s("differs_from_quote"))
        assertEquals((1..6).toList(), ready["events"]!!.jsonArray.map { it.jsonObject["seq"]!!.jsonPrimitive.int })

        val handoff = client.postJson("/v1/deep-links", """{"task_id":"$task"}""", key, t).assertContract("POST", "/v1/deep-links")
        assertEquals(201, handoff.status.value)
        assertEquals("HANDED_OFF", client.getAuthed("/v1/cart-tasks/$task", t).obj().s("status"))
    }

    @Test fun reportsAreRefusedWhenTheyDontFit() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        val task = client.deviceTask(t)
        val otherSession = client.login(deviceId = "dev-2").tokens.accessToken
        assertEquals("forbidden", client.progress(otherSession, task, report(1, "RUNNING", 1, listOf(event(2, "state_changed", "Started")))).errorCode(), "only the session that approved")
        assertEquals("task_state_conflict", client.progress(t, task, report(1, "CART_READY", 1, emptyList(), approvedCart)).errorCode(), "APPROVED can't jump to CART_READY")
        assertEquals("validation_failed", client.progress(t, task, report(1, "RUNNING", 1, listOf(event(5, "state_changed", "Started")))).errorCode(), "events continue the timeline")
        assertEquals("validation_failed", client.progress(t, task, report(1, "RUNNING", 1, listOf(event(2, "state_changed", "x".repeat(201))))).errorCode(), "text is length-limited")
        client.progress(t, task, report(2, "RUNNING", 1, listOf(event(2, "state_changed", "Started"))))
        assertEquals("task_state_conflict", client.progress(t, task, report(1, "RUNNING", 1, emptyList())).errorCode(), "an older report")
        assertEquals("validation_failed", client.progress(t, task, report(3, "CHECKPOINT", 2, emptyList())).errorCode(), "a checkpoint needs its prompt")
        assertEquals("not_found", client.progress(t, "tsk_nope", report(1, "RUNNING", 1, emptyList())).errorCode())
    }

    @Test fun theServerEnforcesLimitsAndChecksTheCartItself() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        val tooMany = client.deviceTask(t)
        val over = client.progress(t, tooMany, report(1, "RUNNING", 26, listOf(event(2, "state_changed", "Started")))).obj()
        assertEquals("FAILED_SAFE", over.s("status"))
        assertEquals("max_actions_reached", over["failure"]!!.jsonObject.s("code"))

        val wrongCart = client.deviceTask(t)
        client.progress(t, wrongCart, report(1, "RUNNING", 1, listOf(event(2, "state_changed", "Started"))))
        val drift = cartOf("Toned milk 1 L" to 2, "Brown bread 400 g" to 1, "Eggs 12 pack" to 1, "Bananas 6 pcs" to 1, "Toned milk 500 ml" to 1)
        val claimed = client.progress(t, wrongCart, report(2, "CART_READY", 12, listOf(event(3, "cart_verified", "Cart checked")), drift)).obj()
        assertEquals("FAILED_SAFE", claimed.s("status"), "the phone said ready; the cart says otherwise")
        assertEquals("cart_verification_failed", claimed["failure"]!!.jsonObject.s("code"))
        assertEquals("false", claimed["cart"]!!.jsonObject.s("verified"))

        val slow = client.deviceTask(t)
        client.progress(t, slow, report(1, "RUNNING", 1, listOf(event(2, "state_changed", "Started"))))
        clock.advance(Duration.ofSeconds(181))
        val late = client.progress(t, slow, report(2, "RUNNING", 2, emptyList())).obj()
        assertEquals("runtime_exceeded", late["failure"]!!.jsonObject.s("code"))
    }

    @Test fun aStopOrCheckpointAnswerReachesThePhone() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        val task = client.deviceTask(t)
        client.progress(t, task, report(1, "RUNNING", 1, listOf(event(2, "state_changed", "Started"))))
        val cp = ""","checkpoint":{"checkpoint_id":"cp_a3","prompt":"Add milk. Continue?","risk":"high"}"""
        client.progress(t, task, report(2, "CHECKPOINT", 3, listOf(event(3, "checkpoint_raised", "Add milk. Continue?")), cp))
        val answered = client.postJson("/v1/cart-tasks/$task/checkpoint", """{"checkpoint_id":"cp_a3","decision":"approve"}""", key, t)
            .assertContract("POST", "/v1/cart-tasks/{taskId}/checkpoint").obj()
        assertEquals("RUNNING", answered.s("status"))
        assertEquals(4, answered["events"]!!.jsonArray.size, "the phone's timeline is kept")

        client.postJson("/v1/cart-tasks/$task/stop", """{"reason":"emergency_stop"}""", token = t).assertContract("POST", "/v1/cart-tasks/{taskId}/stop")
        val afterStop = client.progress(t, task, report(3, "RUNNING", 4, listOf(event(6, "action_succeeded", "Added milk")))).obj()
        assertEquals("CANCELLED", afterStop.s("status"), "the answer tells the phone to halt")
        assertFalse(afterStop["events"]!!.jsonArray.any { it.jsonObject.s("description") == "Added milk" }, "nothing after the stop is applied")
    }
}
