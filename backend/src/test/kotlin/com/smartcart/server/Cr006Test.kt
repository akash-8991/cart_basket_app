package com.smartcart.server

import com.smartcart.server.providers.approve
import com.smartcart.server.providers.setFlag
import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.TestDb
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.login
import com.smartcart.server.support.postJson
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.testConfig
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** CR-006: a cart handoff link is used once, by its owner, before it expires; every refusal looks the same. */
class Cr006Test {
    private val key get() = UUID.randomUUID().toString()
    private val clock = MutableClock()
    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    /** A cart the server ran to CART_READY for Blinkit; returns its task ID. */
    private suspend fun HttpClient.readyCart(t: String): String {
        setFlag("automation.global.enabled", true); setFlag("provider.blinkit.enabled", true); approve("blinkit")
        putJson("/v1/me/consents/automation.blinkit", """{"granted":true,"version":"2026-11"}""", t)
        val q = postJson("/v1/comparison/quotes", """{"category":"grocery","eligible_providers":["blinkit"],"objective":"lowest_total"}""", key, t).obj().s("quote_id")
        postJson("/v1/comparison/quotes/$q/confirm", """{"provider":"blinkit","acknowledged_switch":false}""", key, t)
        val task = postJson("/v1/cart-tasks", """{"quote_id":"$q","provider":"blinkit","task_type":"add_to_cart"}""", key, t).obj()
        val ids = task["planned_actions"]!!.jsonArray.joinToString(",") { "\"${it.jsonObject.s("action_id")}\"" }
        postJson("/v1/cart-tasks/${task.s("task_id")}/approve", """{"approved_action_ids":[$ids],"approval_nonce":"${task.s("approval_nonce")}"}""", key, t)
        assertEquals("CART_READY", getAuthed("/v1/cart-tasks/${task.s("task_id")}", t).obj().s("status"))
        return task.s("task_id")
    }

    private suspend fun HttpClient.link(t: String, task: String) =
        postJson("/v1/deep-links", """{"task_id":"$task"}""", key, t).assertContract("POST", "/v1/deep-links").obj().s("handoff_url").substringAfterLast('/')

    private suspend fun HttpClient.redeem(t: String, token: String) =
        postJson("/v1/deep-links/redeem", """{"token":"$token"}""", token = t).assertContract("POST", "/v1/deep-links/redeem")

    /** What a refusal says, without the per-request correlation ID. */
    private suspend fun HttpResponse.refusal() = status.value to obj()["error"]!!.jsonObject.let { it.s("code") to it.s("message") }

    @Test fun aLinkWorksOnceAndEveryRefusalLooksTheSame() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        val task = client.readyCart(t)
        val first = client.link(t, task)
        val second = client.link(t, task) // a newer link replaces the older one
        assertEquals("CART_READY", client.getAuthed("/v1/cart-tasks/$task", t).obj().s("status"), "making links isn't handing off")

        val replaced = client.redeem(t, first).refusal()
        val used = client.redeem(t, second).obj()
        assertEquals(task, used.s("task_id"))
        assertEquals("blinkit", used.s("provider"))
        assertEquals("manual", used.s("strategy"), "no partner package is verified yet")
        assertEquals("HANDED_OFF", client.getAuthed("/v1/cart-tasks/$task", t).obj().s("status"), "using the link hands the cart off")

        val replay = client.redeem(t, second).refusal()
        val third = client.link(t, task) // a handed-off cart can still be opened again
        val other = client.register("ravi.s", "ravi@example.com", "dev-9").tokens.accessToken
        val someoneElse = client.redeem(other, third).refusal()
        val forged = client.redeem(t, third.dropLast(1) + (if (third.last() == 'A') 'B' else 'A')).refusal()
        val unknown = client.redeem(t, "A".repeat(44)).refusal()
        clock.advance(Duration.ofMinutes(10))
        val expired = client.redeem(t, third).refusal()

        listOf(replaced, replay, someoneElse, forged, unknown, expired).forEach {
            assertEquals(404 to ("not_found" to replaced.second.second), it, "a refusal reveals nothing about why")
        }
        assertEquals("validation_failed", client.redeem(t, "not a token").errorCode())
    }

    @Test fun linksCanBeMadeForAnHourAfterTheCartIsReady() = withApp(testServices(clock)) {
        val t = client.register().tokens.accessToken
        val task = client.readyCart(t)
        client.link(t, task)
        clock.advance(Duration.ofMinutes(61))
        val t2 = client.login().tokens.accessToken // a fresh session after the clock moved
        assertEquals("conflict", client.postJson("/v1/deep-links", """{"task_id":"$task"}""", key, t2).assertContract("POST", "/v1/deep-links").errorCode())
        assertEquals("EXPIRED", client.getAuthed("/v1/cart-tasks/$task", t2).obj().s("status"))
    }

    @Test fun appLinksAreServedOnlyOnceConfigured() {
        withApp {
            assertEquals(404, client.get("/.well-known/assetlinks.json").status.value)
        }
        val fp = (1..32).joinToString(":") { "AB" }
        withApp(Services(testConfig().copy(appLinks = "com.smartcart.app:$fp"), TestDb.fresh())) {
            val r = client.get("/.well-known/assetlinks.json")
            assertEquals(200, r.status.value)
            val target = Json.parseToJsonElement(r.bodyAsText()).jsonArray.single().jsonObject["target"]!!.jsonObject
            assertEquals("com.smartcart.app", target.s("package_name"))
            assertEquals(fp, target["sha256_cert_fingerprints"]!!.jsonArray.single().jsonPrimitive.content)
        }
        assertFailsWith<IllegalArgumentException> { assetLinks("com.smartcart.app:not-a-fingerprint") }
        assertTrue(assetLinks("a.b:$fp;c.d:$fp").contains("c.d"))
    }
}
