package com.smartcart.server.comparison

import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.postJson
import com.smartcart.server.support.register
import com.smartcart.server.support.withApp
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 4.4: POST /v1/comparison/quotes runs the engine on the cart that was sent, not a canned quote. */
class ComparisonApiTest {
    private val key get() = UUID.randomUUID().toString()
    private fun body(lines: String, category: String = "grocery") =
        """{"category":"$category","cart_lines":[$lines],"eligible_providers":["blinkit","bigbasket","swiggy_instamart"],"selected_provider":"bigbasket","objective":"lowest_total"}"""
    private fun line(id: String, name: String, size: String?, qty: Int) =
        """{"line_id":"$id","name":"$name","quantity":$qty,"size":${size?.let { "\"$it\"" } ?: "null"},"brand":null,"modifiers":[]}"""
    private fun JsonObject.result(p: String) = this["provider_results"]!!.jsonArray.map { it.jsonObject }.single { it["provider"]!!.jsonPrimitive.content == p }
    private fun JsonObject.total() = (this["total_estimate"] as? JsonObject)?.get("amount_minor")?.jsonPrimitive?.long

    @Test fun theCartSentIsPricedLineByLine() = withApp {
        val t = client.register().tokens.accessToken
        val r = client.postJson("/v1/comparison/quotes", body(line("x1", "Eggs", "12 pack", 2) + "," + line("x2", "Bananas", "6 pcs", 3)), key, t)
            .assertContract("POST", "/v1/comparison/quotes")
        val q = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        // Sample prices (fictional): eggs ₹84 / ₹90 / ₹86, bananas ₹42 / ₹40 / ₹45, plus each app's stated fees.
        assertEquals(2 * 8400L + 3 * 4200 + 200, q.result("blinkit").total())
        assertEquals(2 * 9000L + 3 * 4000 + 3000, q.result("bigbasket").total())
        assertEquals(2 * 8600L + 3 * 4500 + 1500 + 500 - 1000, q.result("swiggy_instamart").total())
        val rec = q["recommendation"]!!.jsonObject
        assertEquals("blinkit", rec["provider"]!!.jsonPrimitive.content)
        assertEquals("cheapest", rec["label"]!!.jsonPrimitive.content)
        assertEquals((2 * 9000L + 3 * 4000 + 3000) - (2 * 8400L + 3 * 4200 + 200), rec["savings_vs_selected"]!!.jsonObject["amount_minor"]!!.jsonPrimitive.long)
    }

    @Test fun choosingWithoutAPickIsNotASwitch() = withApp {
        val t = client.register().tokens.accessToken
        val noPick = body(line("x1", "Eggs", "12 pack", 1)).replace(",\"selected_provider\":\"bigbasket\"", "")
        val q = Json.parseToJsonElement(client.postJson("/v1/comparison/quotes", noPick, key, t).bodyAsText()).jsonObject["quote_id"]!!.jsonPrimitive.content
        val r = client.postJson("/v1/comparison/quotes/$q/confirm", """{"provider":"blinkit","acknowledged_switch":false}""", key, t)
            .assertContract("POST", "/v1/comparison/quotes/{quoteId}/confirm")
        assertEquals(200, r.status.value, "nothing was picked, so there's nothing to switch away from")
    }

    @Test fun anItemNoAppHasMeansNoCompleteCart() = withApp {
        val t = client.register().tokens.accessToken
        val q = Json.parseToJsonElement(
            client.postJson("/v1/comparison/quotes", body(line("x1", "Eggs", "12 pack", 1) + "," + line("x2", "Paneer", "200 g", 1)), key, t)
                .assertContract("POST", "/v1/comparison/quotes").bodyAsText(),
        ).jsonObject
        assertEquals("partial", q.result("blinkit")["availability"]!!.jsonPrimitive.content)
        assertEquals("unavailable", q["status"]!!.jsonPrimitive.content)
        assertEquals("none", q["recommendation"]!!.jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test fun badCartsAreRefusedAndShoppingIsManual() = withApp {
        val t = client.register().tokens.accessToken
        val dup = body(line("x1", "Eggs", "12 pack", 1) + "," + line("x1", "Bananas", "6 pcs", 1))
        assertEquals("validation_failed", client.postJson("/v1/comparison/quotes", dup, key, t).assertContract("POST", "/v1/comparison/quotes").errorCode())
        assertEquals("validation_failed", client.postJson("/v1/comparison/quotes", body(line("x1", "Eggs", "12 pack", 0)), key, t).errorCode())
        val shopping = """{"category":"shopping","cart_lines":[${line("s1", "Phone cover", null, 1)}],"eligible_providers":["amazon","flipkart"],"objective":"lowest_total"}"""
        val q = Json.parseToJsonElement(client.postJson("/v1/comparison/quotes", shopping, key, t).assertContract("POST", "/v1/comparison/quotes").bodyAsText()).jsonObject
        assertEquals("manual_only", q["status"]!!.jsonPrimitive.content)
    }
}
