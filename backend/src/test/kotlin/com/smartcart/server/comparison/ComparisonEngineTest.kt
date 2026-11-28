package com.smartcart.server.comparison

import com.smartcart.server.support.Contract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 4.4: the comparison engine. Golden tests feed each frozen comparison fixture back to the engine as the
 * raw readings it was made from (lines found, prices, stated fees, ETAs) and check the engine reproduces the
 * fixture's figures and judgements: totals, ranks, availability, line matches, coverage, status, missing
 * fields, the recommendation and its saving, and confidence. Wording (reasons, notes) isn't compared.
 */
class ComparisonEngineTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun fixture(path: String) = json.parseToJsonElement(javaClass.getResourceAsStream("/contracts/fixtures/$path")!!.readAllBytes().decodeToString()).jsonObject
    private fun JsonObject.str(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonElement?.minor() = (this as? JsonObject)?.get("amount_minor")?.jsonPrimitive?.long
    private val names = mapOf("blinkit" to "Blinkit", "bigbasket" to "BigBasket", "swiggy_instamart" to "Swiggy Instamart", "zomato" to "Zomato", "swiggy" to "Swiggy", "uber" to "Uber", "ola" to "Ola", "rapido" to "Rapido")

    /** The fixture quote, turned back into what the engine is given. */
    private fun inputOf(q: JsonObject): EngineInput {
        val results = q["provider_results"]!!.jsonArray.map { it.jsonObject }
        val lines = results.firstNotNullOfOrNull { r -> r["lines"]!!.jsonArray.takeIf { it.isNotEmpty() } }?.map { it.jsonObject }.orEmpty()
            .map { RequestedLine(it.str("line_id")!!, it.str("requested_name")!!, null, it["quantity"]!!.jsonPrimitive.content.toInt()) }
        val readings = results.associate { r ->
            val note = r["notes"]!!.jsonArray.firstOrNull()?.jsonPrimitive?.content ?: ""
            r.str("provider")!! to when (val a = r.str("availability")!!) {
                "not_checked" -> Reading.Waiting(note, null)
                "failed", "unavailable" -> Reading.NotRead(a, note, null)
                else -> {
                    val c = r["components"]!!.jsonObject
                    val unknown = r["unavailable_fields"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    Reading.Read(
                        capturedAt = Instant.parse(r.str("captured_at")),
                        offers = r["lines"]!!.jsonArray.map { it.jsonObject }.map { l ->
                            val found = l.str("match") != "unavailable"
                            Offer(l.str("line_id")!!, found, l.str("matched_name"), l.str("matched_size"), l["unit_price"].minor(), inStock = found, note = l.str("note"))
                        },
                        fees = Fees((Fees.KEYS + "discounts").mapNotNull { k -> c[k].minor()?.let { k to it } }.toMap(), unknown),
                        etaMinutes = r.str("eta_minutes")?.toInt(),
                        source = null,
                    )
                }
            }
        }
        return EngineInput(
            quoteId = q.str("quote_id")!!, category = q.str("category")!!, currency = q.str("currency")!!, objective = q.str("objective")!!,
            selectedProvider = q.str("selected_provider"), createdAt = Instant.parse(q.str("created_at")), expiresAt = Instant.parse(q.str("expires_at")),
            assumptions = q["assumptions"]!!.jsonArray.map { it.jsonPrimitive.content }, lines = lines, readings = readings, providerNames = names,
        )
    }

    /** The judgements a quote makes, without its wording. */
    private fun essence(q: JsonObject): Map<String, Any?> {
        val rec = q["recommendation"]!!.jsonObject
        return mapOf(
            "status" to q.str("status"),
            "coverage" to q["coverage"].toString(),
            "missing_fields" to q["missing_fields"].toString(),
            "confidence" to q.str("confidence"),
            "recommendation" to listOf(rec.str("provider"), rec.str("label"), rec["savings_vs_selected"].minor()),
            "results" to q["provider_results"]!!.jsonArray.map { it.jsonObject }.map { r ->
                listOf(
                    r.str("provider"), r.str("availability"), r["total_estimate"].minor(), r.str("rank"), r["unavailable_fields"].toString(),
                    r["lines"]!!.jsonArray.map { it.jsonObject }.map { l -> listOf(l.str("match"), l["line_total"].minor()) },
                    r["components"]!!.jsonObject["item_subtotal"].minor(),
                )
            },
        )
    }

    private fun golden(file: String, now: Instant = Instant.parse("2026-11-22T05:00:00Z")) {
        val expected = fixture(file)
        val actual = ComparisonEngine.compute(inputOf(expected), now)
        val problems = Contract.componentSchema("ComparisonQuote").validate(Contract.json.readTree(actual.toString()))
        if (problems.isNotEmpty()) fail("$file: engine output breaks the contract: $problems")
        assertEquals(essence(expected), essence(actual), file)
    }

    @Test fun completeGroceryCart() = golden("comparison/grocery.complete.json")
    @Test fun oneAppFailedAndOneFeeNotVisible() = golden("comparison/grocery.partial-coverage.json")
    @Test fun pricesTooOldAreStale() = golden("comparison/grocery.stale.json")
    @Test fun differentPackSizesAreIncomparable() = golden("comparison/grocery.incomparable.json")
    @Test fun nobodyCanDeliver() = golden("comparison/grocery.unavailable.json")
    @Test fun stillChecking() = golden("comparison/grocery.in-progress.json")
    @Test fun food() = golden("comparison/food.complete.json")
    @Test fun ride() = golden("comparison/travel.complete.json")
    @Test fun waitingForThePhone() = golden("comparison/grocery.device-pending.json")

    // ---------------------------------------------------------------- rules beyond the fixtures

    private val lines = listOf(RequestedLine("l1", "Toned milk", "1 L", 2), RequestedLine("l2", "Brown bread", "400 g", 1))
    private val t0 = Instant.parse("2026-11-28T10:00:00Z")
    private fun read(vararg offers: Offer, fees: Fees = Fees(mapOf("delivery_fee" to 500L)), eta: Int? = 10) = Reading.Read(t0, offers.toList(), fees, eta, "server")
    private fun input(vararg readings: Pair<String, Reading>, objective: String = "lowest_total", selected: String? = null) = EngineInput(
        "qt", "grocery", "INR", objective, selected, t0, t0.plusSeconds(300), emptyList(), lines, linkedMapOf(*readings), names,
    )
    private fun JsonObject.result(p: String) = this["provider_results"]!!.jsonArray.map { it.jsonObject }.single { it.str("provider") == p }

    @Test fun matchingIsConservative() {
        val q = ComparisonEngine.compute(
            input(
                "blinkit" to read(Offer("l1", true, "Toned Milk", "1l", 2800), Offer("l2", true, "Brown bread 400 g", null, 4500)),
                "bigbasket" to read(Offer("l1", true, "Amul Toned milk", "1 L", 2900), Offer("l2", true, "White bread", "400 g", 4000)),
                "swiggy_instamart" to read(Offer("l1", true, "Toned milk", "500 ml", 1500), Offer("l2", true, "Brown bread", "400 g", 4400, inStock = false)),
            ),
            t0,
        )
        assertEquals(listOf("exact", "exact"), q.result("blinkit")["lines"]!!.jsonArray.map { it.jsonObject.str("match") }, "case, spacing and units don't matter")
        assertEquals(listOf("equivalent", "uncertain"), q.result("bigbasket")["lines"]!!.jsonArray.map { it.jsonObject.str("match") })
        assertEquals(listOf("different_pack", "unavailable"), q.result("swiggy_instamart")["lines"]!!.jsonArray.map { it.jsonObject.str("match") })
        assertEquals("incomparable", q.str("status"), "a different pack is never substituted silently")
        assertEquals("none", q["recommendation"]!!.jsonObject.str("label"))
    }

    @Test fun totalsAreForCompleteCartsWithStatedFeesAndDiscounts() {
        val fees = Fees(mapOf("delivery_fee" to 1500L, "platform_fee" to 500L, "discounts" to 1000L), unknown = setOf("packaging_fee"))
        val q = ComparisonEngine.compute(
            input(
                "blinkit" to read(Offer("l1", true, "Toned milk", "1 L", 2800), Offer("l2", true, "Brown bread", "400 g", 4500), fees = fees),
                "bigbasket" to read(Offer("l1", true, "Toned milk", "1 L", 2700), Offer("l2", false)),
            ),
            t0,
        )
        val blinkit = q.result("blinkit")
        assertEquals(5600L + 4500 + 1500 + 500 - 1000, blinkit["total_estimate"].minor(), "known fees in, discount out")
        assertTrue("packaging_fee" in blinkit["unavailable_fields"].toString(), "an unshown fee is listed, not counted as zero")
        assertEquals(null, q.result("bigbasket")["total_estimate"].minor(), "no total for an incomplete cart")
        assertEquals(null, q.result("bigbasket").str("rank"))
        assertEquals("lowest_observed_estimate", q["recommendation"]!!.jsonObject.str("label"), "not \"cheapest\" while a fee is missing")
        assertEquals("""["packaging_fee"]""", q["missing_fields"].toString())
    }

    @Test fun objectivesPickDifferently() {
        val fast = read(Offer("l1", true, "Toned milk", "1 L", 3000), Offer("l2", true, "Brown bread", "400 g", 4600), eta = 8)
        val cheap = read(Offer("l1", true, "Toned milk", "1 L", 2700), Offer("l2", true, "Brown bread", "400 g", 4400), eta = 40)
        val byTotal = ComparisonEngine.compute(input("blinkit" to fast, "bigbasket" to cheap, selected = "blinkit"), t0)
        assertEquals(listOf("bigbasket", "cheapest"), byTotal["recommendation"]!!.jsonObject.let { listOf(it.str("provider"), it.str("label")) })
        assertEquals((6000L + 4600 + 500) - (5400L + 4400 + 500), byTotal["recommendation"]!!.jsonObject["savings_vs_selected"].minor())
        val byTime = ComparisonEngine.compute(input("blinkit" to fast, "bigbasket" to cheap, objective = "fastest"), t0)
        assertEquals(listOf("blinkit", "fastest"), byTime["recommendation"]!!.jsonObject.let { listOf(it.str("provider"), it.str("label")) })
        assertEquals("1", byTime.result("blinkit").str("rank"))
        val preferred = ComparisonEngine.compute(input("blinkit" to fast, "bigbasket" to cheap, objective = "preferred_provider", selected = "blinkit"), t0)
        assertEquals(listOf("blinkit", "preferred"), preferred["recommendation"]!!.jsonObject.let { listOf(it.str("provider"), it.str("label")) })
    }

    @Test fun freshnessAndTheSameInputAlwaysGiveTheSameQuote() {
        val i = input("blinkit" to read(Offer("l1", true, "Toned milk", "1 L", 2800), Offer("l2", true, "Brown bread", "400 g", 4500)))
        assertEquals(ComparisonEngine.compute(i, t0), ComparisonEngine.compute(i, t0), "deterministic")
        assertEquals("complete", ComparisonEngine.compute(i, t0.plus(Duration.ofMinutes(30))).str("status"))
        assertEquals("stale", ComparisonEngine.compute(i, t0.plus(Duration.ofMinutes(31))).str("status"))
    }

    @Test fun theSampleCatalogPricesAnyCartThroughTheEngine() {
        val cart = listOf(RequestedLine("x1", "Eggs", "12 pack", 2), RequestedLine("x2", "Paneer", "200 g", 1))
        val r = SampleCatalog.read("blinkit", cart, t0) as Reading.Read
        assertEquals(listOf(true, false), r.offers.map { it.found })
        assertEquals(null, SampleCatalog.read("zepto", cart, t0), "a provider the samples don't cover isn't invented")
    }
}
