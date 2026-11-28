package com.smartcart.server.comparison

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant

/**
 * Development only (D-020, D-031): what the server "sees" in each provider until real readings exist. The
 * products, fees and delivery times are taken from the frozen contract fixtures — fictional figures, not real
 * provider data (PRD Part B rule 8). Any cart is then priced by [ComparisonEngine]; nothing is copied from a
 * canned quote. In production every reading comes from the user's phone (CR-005).
 */
object SampleCatalog {
    private data class Product(val name: String, val size: String?, val unitPriceMinor: Long)
    private data class Shop(val products: List<Product>, val fees: Fees, val eta: Int?)

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(path: String): JsonObject =
        json.parseToJsonElement(SampleCatalog::class.java.getResourceAsStream("/contracts/fixtures/$path")!!.readAllBytes().decodeToString()).jsonObject

    private fun JsonObject.str(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private val shops: Map<String, Shop> by lazy {
        listOf("comparison/grocery.complete.json", "comparison/food.complete.json", "comparison/travel.complete.json").flatMap { file ->
            fixture(file)["provider_results"]!!.jsonArray.map { it.jsonObject }.map { r ->
                val products = r["lines"]!!.jsonArray.map { it.jsonObject }.filter { it.str("match") != "unavailable" }.map { l ->
                    Product(l.str("matched_name")!!, l.str("matched_size"), l["unit_price"]!!.jsonObject["amount_minor"]!!.jsonPrimitive.long)
                }
                val c = r["components"]!!.jsonObject
                val unknown = r["unavailable_fields"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                val amounts = (Fees.KEYS + "discounts").mapNotNull { k -> (c[k] as? JsonObject)?.let { k to it["amount_minor"]!!.jsonPrimitive.long } }.toMap()
                r.str("provider")!! to Shop(products, Fees(amounts, unknown), r.str("eta_minutes")?.toInt())
            }
        }.toMap()
    }

    /** The sample cart for a category, used when a development request sends no lines. */
    fun sampleLines(category: String): List<RequestedLine> {
        val file = when (category) { "food" -> "comparison/food.complete.json"; "travel" -> "comparison/travel.complete.json"; else -> "comparison/grocery.complete.json" }
        return fixture(file)["provider_results"]!!.jsonArray.first().jsonObject["lines"]!!.jsonArray.map { it.jsonObject }.map { l ->
            RequestedLine(l.str("line_id")!!, l.str("requested_name")!!, null, l["quantity"]!!.jsonPrimitive.content.toInt())
        }
    }

    /** The provider's reading for [lines], or null if the sample catalogue doesn't cover that provider. */
    fun read(provider: String, lines: List<RequestedLine>, at: Instant): Reading? {
        val shop = shops[provider] ?: return null
        val offers = lines.map { line ->
            val want = ComparisonEngine.split(line.label)
            val keyed = shop.products.associateBy { ComparisonEngine.split(listOfNotNull(it.name, it.size).joinToString(" ")) }
            // The requested pack if the shop has it; otherwise the same product in another pack (never substituted silently).
            val p = keyed[want] ?: shop.products.firstOrNull { ComparisonEngine.split(listOfNotNull(it.name, it.size).joinToString(" ")).first == want.first }
            if (p == null) Offer(line.lineId, found = false)
            else Offer(line.lineId, found = true, name = p.name, size = p.size, unitPriceMinor = p.unitPriceMinor, inStock = true)
        }
        return Reading.Read(at, offers, shop.fees, shop.eta, source = "server")
    }
}
