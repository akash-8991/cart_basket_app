package com.smartcart.server.stubs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// ---------------------------------------------------------------- CR-005 request bodies

@Serializable data class MoneyIn(val amountMinor: Long, val currency: String)
@Serializable data class AdapterRef(val key: String, val version: String)
@Serializable data class ObservedOffer(val lineId: String, val found: Boolean, val name: String? = null, val unitPrice: MoneyIn? = null, val inStock: Boolean = false)
@Serializable data class ObservedFees(
    val deliveryFee: MoneyIn? = null,
    val packagingFee: MoneyIn? = null,
    val platformFee: MoneyIn? = null,
    val smallCartFee: MoneyIn? = null,
    val surgeOrBookingFee: MoneyIn? = null,
    val taxes: MoneyIn? = null,
    val discounts: MoneyIn? = null,
) {
    fun named(): List<Pair<String, MoneyIn?>> = listOf(
        "delivery_fee" to deliveryFee, "packaging_fee" to packagingFee, "platform_fee" to platformFee, "small_cart_fee" to smallCartFee,
        "surge_or_booking_fee" to surgeOrBookingFee, "taxes" to taxes, "discounts" to discounts,
    )
}
@Serializable data class PriceObservation(
    val provider: String,
    val capturedAt: String,
    val outcome: String,
    val notReadReason: String? = null,
    val adapter: AdapterRef,
    val offers: List<ObservedOffer> = emptyList(),
    val fees: ObservedFees? = null,
    val etaMinutes: Int? = null,
)
@Serializable data class TaskEventIn(val seq: Int, val at: String, val type: String, val description: String, val actionId: String? = null)
@Serializable data class CheckpointIn(val checkpointId: String, val prompt: String, val risk: String)
@Serializable data class NeedsUserIn(val reason: String, val message: String)
@Serializable data class FailureIn(val code: String, val message: String)
@Serializable data class CartLineIn(
    val lineId: String,
    val requestedName: String,
    val matchedName: String? = null,
    val matchedSize: String? = null,
    val quantity: Int = 1,
    val unitPrice: MoneyIn? = null,
    val lineTotal: MoneyIn? = null,
    val match: String,
    val note: String? = null,
)
@Serializable data class CartIn(
    val verified: Boolean,
    val verifiedAt: String? = null,
    val lines: List<CartLineIn>,
    val totalEstimate: MoneyIn? = null,
    val differsFromQuote: Boolean = false,
)
@Serializable data class TaskProgressReport(
    val reportSeq: Int,
    val status: String,
    val actionsUsed: Int,
    val events: List<TaskEventIn>,
    val checkpoint: CheckpointIn? = null,
    val needsUserAction: NeedsUserIn? = null,
    val failure: FailureIn? = null,
    val cart: CartIn? = null,
    val adapter: AdapterRef,
)

/**
 * CR-005 on the server: turns what a phone read into a provider result and recomputes the quote. The phone
 * sends readings only; every total, rank, label and saving is worked out here, under the comparison rules:
 * totals only for complete carts with every fee known, "Cheapest" only with full coverage, unstated fees
 * listed as unavailable, never assumed zero.
 */
internal object DeviceReadings {
    private fun JsonObject.str(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.set(k: String, v: JsonElement) = JsonObject(toMutableMap().apply { put(k, v) })
    private fun money(minor: Long, currency: String) = JsonObject(mapOf("amount_minor" to JsonPrimitive(minor), "currency" to JsonPrimitive(currency)))
    private fun MoneyIn?.json(): JsonElement = this?.let { money(it.amountMinor, it.currency) } ?: JsonNull
    private fun texts(vararg s: String) = JsonArray(s.map(::JsonPrimitive))
    private fun JsonElement?.minor(): Long? = (this as? JsonObject)?.get("amount_minor")?.jsonPrimitive?.longOrNull

    /** A provider's column while the phone hasn't reported: the requested lines, no figures. */
    fun pending(result: JsonObject): JsonObject = JsonObject(
        mapOf(
            "provider" to result["provider"]!!,
            "availability" to JsonPrimitive("not_checked"),
            "captured_at" to JsonNull,
            "lines" to JsonArray(result["lines"]!!.jsonArray.map { cleared(it.jsonObject) }),
            "components" to JsonObject(emptyMap()),
            "total_estimate" to JsonNull,
            "eta_minutes" to JsonNull,
            "rank" to JsonNull,
            "unavailable_fields" to JsonArray(emptyList()),
            "notes" to texts("Waiting for prices from your phone"),
            "source" to JsonPrimitive("device"),
        ),
    )

    private fun cleared(line: JsonObject) = JsonObject(line.toMutableMap().apply {
        put("unit_price", JsonNull); put("line_total", JsonNull)
    })

    /** What the phone read, as a provider result. [lines] are the quote's lines for that provider. */
    fun read(provider: String, lines: JsonArray, obs: PriceObservation, currency: String, displayName: String): JsonObject {
        val byLine = obs.offers.associateBy { it.lineId }
        val read = lines.map { el ->
            val l = el.jsonObject
            val o = byLine[l.str("line_id")]
            val qty = l["quantity"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val m = l.toMutableMap()
            when {
                o == null || !o.found || o.unitPrice == null -> {
                    m["matched_name"] = JsonNull; m["matched_size"] = JsonNull; m["unit_price"] = JsonNull; m["line_total"] = JsonNull
                    m["match"] = JsonPrimitive("unavailable"); m["note"] = JsonPrimitive("Not found in $displayName")
                }
                !o.inStock -> {
                    m["matched_name"] = JsonPrimitive(o.name); m["matched_size"] = JsonNull; m["unit_price"] = o.unitPrice.json(); m["line_total"] = JsonNull
                    m["match"] = JsonPrimitive("unavailable"); m["note"] = JsonPrimitive("Out of stock")
                }
                else -> {
                    m["matched_name"] = JsonPrimitive(o.name); m["matched_size"] = JsonNull; m["unit_price"] = o.unitPrice.json()
                    m["line_total"] = money(o.unitPrice.amountMinor * qty, currency)
                    m["match"] = JsonPrimitive("exact"); m["note"] = JsonNull
                }
            }
            JsonObject(m)
        }
        val missing = read.count { it.str("match") == "unavailable" }
        val availability = when (missing) { 0 -> "available"; read.size -> "unavailable"; else -> "partial" }
        val subtotal = read.mapNotNull { it["line_total"].minor() }.sum()
        val fees = (obs.fees ?: ObservedFees()).named()
        // Surge and discounts are optional for groceries: unstated means none. Every other fee must be stated.
        val unknown = fees.filter { (k, v) -> v == null && k !in setOf("surge_or_booking_fee", "discounts") }.map { it.first }
        val total = if (availability == "available" && unknown.isEmpty()) {
            subtotal + fees.filter { it.first != "discounts" }.sumOf { it.second?.amountMinor ?: 0 } - (obs.fees?.discounts?.amountMinor ?: 0)
        } else null
        val components = JsonObject(mapOf("item_subtotal" to money(subtotal, currency)) + fees.associate { (k, v) -> k to v.json() })
        return JsonObject(
            mapOf(
                "provider" to JsonPrimitive(provider),
                "availability" to JsonPrimitive(availability),
                "captured_at" to JsonPrimitive(obs.capturedAt),
                "lines" to JsonArray(read),
                "components" to components,
                "total_estimate" to (total?.let { money(it, currency) } ?: JsonNull),
                "eta_minutes" to (obs.etaMinutes?.let(::JsonPrimitive) ?: JsonNull),
                "rank" to JsonNull,
                "unavailable_fields" to JsonArray((unknown + listOfNotNull("eta_minutes".takeIf { obs.etaMinutes == null })).map(::JsonPrimitive)),
                "notes" to JsonArray(
                    listOfNotNull(
                        "Prices read on your phone",
                        if (missing > 0) "$missing of ${read.size} items aren't available" else null,
                        if (unknown.isNotEmpty() && availability == "available") "$displayName doesn't state every fee, so there's no total" else null,
                    ).map(::JsonPrimitive),
                ),
                "source" to JsonPrimitive("device"),
            ),
        )
    }

    /** A provider the phone couldn't read: it counts as not checked. */
    fun failed(result: JsonObject, note: String): JsonObject = pending(result)
        .set("availability", JsonPrimitive("failed"))
        .set("unavailable_fields", texts("item_subtotal", "delivery_fee", "packaging_fee", "platform_fee", "small_cart_fee", "taxes", "eta_minutes"))
        .set("notes", texts(note))

    fun notReadNote(reason: String?, displayName: String): String = when (reason) {
        "service_off" -> "SmartCart's accessibility service was off, so $displayName wasn't read"
        "consent_missing" -> "Automation for $displayName isn't allowed, so it wasn't read"
        "app_not_installed" -> "$displayName isn't installed on your phone"
        "package_mismatch" -> "The $displayName app on your phone couldn't be verified"
        "login_required", "otp_required", "password_prompt", "biometric", "captcha" -> "$displayName wanted you to sign in or verify, so prices weren't read"
        "payment_screen" -> "$displayName showed a payment screen, so reading stopped"
        "unknown_screen", "unexpected_dialog" -> "$displayName showed a screen your phone didn't recognise"
        "kill_switch" -> "Automation for $displayName is paused"
        else -> "Your phone couldn't read $displayName"
    }

    /**
     * Ranks, coverage, status and recommendation for a quote some of whose providers are read on the phone.
     * [pending] providers keep the quote in progress.
     */
    fun recompute(quote: JsonObject, pending: Set<String>): JsonObject {
        val results = quote["provider_results"]!!.jsonArray.map { it.jsonObject }
        val comparable = results.filter { it.str("availability") == "available" && it["total_estimate"].minor() != null }
            .sortedBy { it["total_estimate"].minor() }
        val ranked = results.map { r -> r.set("rank", comparable.indexOfFirst { it.str("provider") == r.str("provider") }.takeIf { it >= 0 }?.let { JsonPrimitive(it + 1) } ?: JsonNull) }
        val checked = quote["coverage"]!!.jsonObject["checked"]!!.jsonArray
        val failed = results.filter { it.str("availability") == "failed" }.map { it.str("provider")!! }
        val complete = failed.isEmpty() && pending.isEmpty() && checked.isNotEmpty()
        val coverage = JsonObject(mapOf("checked" to checked, "failed" to JsonArray(failed.map(::JsonPrimitive)), "complete" to JsonPrimitive(complete)))
        val status = when {
            pending.isNotEmpty() -> "in_progress"
            complete -> "complete"
            else -> "partial_coverage"
        }
        val best = comparable.firstOrNull()
        val recommendation = when {
            pending.isNotEmpty() -> rec(null, "none", "Still checking prices on your phone.", null)
            quote.str("objective") != "lowest_total" -> quote["recommendation"]!!.jsonObject
            best == null -> rec(null, "none", "No app had every item with a known total.", null)
            else -> {
                val selected = quote.str("selected_provider")?.let { s -> results.firstOrNull { it.str("provider") == s } }
                val bestTotal = best["total_estimate"].minor()!!
                val savings = selected?.takeIf { it.str("provider") != best.str("provider") }?.get("total_estimate").minor()
                    ?.takeIf { it > bestTotal }?.let { money(it - bestTotal, quote.str("currency")!!) }
                rec(
                    best.str("provider"),
                    if (complete) "cheapest" else "lowest_observed_estimate",
                    if (complete) "Lowest estimated complete-cart total across all ${checked.size} apps checked."
                    else "Lowest estimated total among the apps that could be checked.",
                    savings,
                )
            }
        }
        return quote.set("provider_results", JsonArray(ranked)).set("coverage", coverage).set("status", JsonPrimitive(status)).set("recommendation", recommendation)
    }

    private fun rec(provider: String?, label: String, reason: String, savings: JsonElement?) = JsonObject(
        mapOf(
            "provider" to (provider?.let(::JsonPrimitive) ?: JsonNull),
            "label" to JsonPrimitive(label),
            "reason" to JsonPrimitive(reason),
            "savings_vs_selected" to (savings ?: JsonNull),
        ),
    )

    /** The items approved for [provider]'s cart: name (with size) and quantity, from the quote it is bound to. */
    fun approvedItems(quote: JsonObject, provider: String): List<Pair<String, Int>> =
        quote["provider_results"]!!.jsonArray.map { it.jsonObject }.first { it.str("provider") == provider }["lines"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it.str("match") != "unavailable" }
            .map { l -> listOfNotNull(l.str("matched_name") ?: l.str("requested_name"), l.str("matched_size")).joinToString(" ").lowercase() to (l["quantity"]?.jsonPrimitive?.content?.toInt() ?: 1) }
}
