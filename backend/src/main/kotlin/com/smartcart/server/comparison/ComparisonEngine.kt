package com.smartcart.server.comparison

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant

/** One line of the cart the person asked to compare. [size] may already be part of [name]. */
data class RequestedLine(val lineId: String, val name: String, val size: String?, val quantity: Int) {
    val label: String get() = listOfNotNull(name, size?.takeIf { it.isNotBlank() }).joinToString(" ")
}

/** What a provider showed for one line. Prices are in minor units of the quote's currency. */
data class Offer(
    val lineId: String,
    val found: Boolean,
    val name: String? = null,
    val size: String? = null,
    val unitPriceMinor: Long? = null,
    val inStock: Boolean = true,
    val note: String? = null,
)

/**
 * Fees a provider stated. A key missing from [amounts] doesn't apply; a key in [unknown] applies but wasn't
 * shown, so it is listed as unavailable and never counted as zero.
 */
data class Fees(val amounts: Map<String, Long> = emptyMap(), val unknown: Set<String> = emptySet()) {
    companion object {
        /** Order of the cost rows in the contract (discounts are subtracted). */
        val KEYS = listOf("delivery_fee", "packaging_fee", "platform_fee", "small_cart_fee", "surge_or_booking_fee", "taxes")
        /** Fees that simply don't apply when an app doesn't mention them. */
        val OPTIONAL = setOf("surge_or_booking_fee", "discounts")
    }
}

sealed interface Reading {
    val source: String?

    data class Read(
        val capturedAt: Instant,
        val offers: List<Offer>,
        val fees: Fees,
        val etaMinutes: Int?,
        override val source: String?,
        val notes: List<String> = emptyList(),
    ) : Reading

    /** Still being read (by the phone, CR-005). */
    data class Waiting(val note: String, override val source: String?) : Reading

    /** Not read: [availability] is failed, unavailable or not_checked. */
    data class NotRead(val availability: String, val note: String, override val source: String?) : Reading
}

data class EngineInput(
    val quoteId: String,
    val category: String,
    val currency: String,
    val objective: String,
    val selectedProvider: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val assumptions: List<String>,
    val lines: List<RequestedLine>,
    /** In the order the providers are shown. */
    val readings: Map<String, Reading>,
    val providerNames: Map<String, String>,
    val manualOnly: Boolean = false,
)

/**
 * The deterministic full-cart comparison (Phase 4.4, PRD FR-5A and the "Full-cart comparison" steps). Code,
 * never a model, and the same input always gives the same quote:
 *
 * - **Matching** is conservative: a product counts as the requested one only if its name and pack size match;
 *   the same product in another pack size is `different_pack` and makes the comparison incomparable, never
 *   silently substituted; anything else found is `uncertain`; out of stock or not found is `unavailable`.
 * - **Totals** are for complete carts only: item subtotal plus every fee the app states, minus discounts. A fee
 *   the app doesn't show is listed in `unavailable_fields` and `missing_fields`, never counted as zero.
 * - **Labels**: "cheapest" only when every eligible provider was checked, no fee is missing and the prices are
 *   fresh; otherwise "lowest observed estimate". Incomparable, unavailable and in-progress quotes recommend
 *   nothing.
 */
object ComparisonEngine {
    /** Prices older than this when the quote is computed make it stale. */
    val FRESHNESS: Duration = Duration.ofMinutes(30)

    private val SIZE = Regex("""\s*(\d+(?:\.\d+)?)\s*(ml|l|ltr|litre|liter|g|gm|gms|kg|pcs|pc|pack|packs|pieces|units?)\b""", RegexOption.IGNORE_CASE)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9.]+"), " ")
        .replace(Regex("""(\d)\s+(ml|l|ltr|litre|liter|g|gm|gms|kg|pcs|pc|pack|packs|pieces|units?)\b"""), "$1$2").trim()

    /** "Toned milk 1 L" → ("toned milk", "1l"): the product's name and pack size, normalised. */
    internal fun split(label: String): Pair<String, String?> {
        val m = SIZE.findAll(label).lastOrNull() ?: return norm(label) to null
        return norm(label.removeRange(m.range)) to norm(m.value)
    }

    private data class Matched(val match: String, val note: String?)

    private fun match(line: RequestedLine, o: Offer?): Matched {
        if (o == null || !o.found || o.unitPriceMinor == null) return Matched("unavailable", o?.note ?: "Not found")
        if (!o.inStock) return Matched("unavailable", o.note ?: "Out of stock")
        val want = split(line.label)
        val got = split(listOfNotNull(o.name, o.size).joinToString(" "))
        return when {
            want == got -> Matched("exact", o.note)
            want.first == got.first && want.second != got.second -> Matched("different_pack", o.note ?: "Different pack size")
            got.first.contains(want.first) && want.second == got.second -> Matched("equivalent", o.note)
            else -> Matched("uncertain", o.note ?: "May not be the item you asked for")
        }
    }

    private fun money(minor: Long?, currency: String): JsonElement =
        minor?.let { JsonObject(mapOf("amount_minor" to JsonPrimitive(it), "currency" to JsonPrimitive(currency))) } ?: JsonNull

    private class Result(val provider: String, val json: JsonObject, val total: Long?, val eta: Int?, val read: Reading, val lineMatches: List<String>, val unknownFees: Set<String>)

    private fun result(input: EngineInput, provider: String, reading: Reading): Result {
        val c = input.currency
        fun base(availability: String, note: String, source: String?) = JsonObject(
            mapOf(
                "provider" to JsonPrimitive(provider), "availability" to JsonPrimitive(availability), "captured_at" to JsonNull,
                "lines" to JsonArray(emptyList()), "components" to JsonObject(emptyMap()), "total_estimate" to JsonNull,
                "eta_minutes" to JsonNull, "rank" to JsonNull, "unavailable_fields" to JsonArray(emptyList()),
                "notes" to JsonArray(listOf(JsonPrimitive(note))), "source" to (source?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
        return when (reading) {
            is Reading.Waiting -> Result(provider, base("not_checked", reading.note, reading.source), null, null, reading, emptyList(), emptySet())
            is Reading.NotRead -> Result(provider, base(reading.availability, reading.note, reading.source), null, null, reading, emptyList(), emptySet())
            is Reading.Read -> {
                val byLine = reading.offers.associateBy { it.lineId }
                val matched = input.lines.map { it to match(it, byLine[it.lineId]) }
                val lines = matched.map { (l, m) ->
                    val o = byLine[l.lineId]
                    val available = m.match != "unavailable"
                    JsonObject(
                        mapOf(
                            "line_id" to JsonPrimitive(l.lineId),
                            "requested_name" to JsonPrimitive(l.label),
                            "matched_name" to (if (available) o?.name?.let(::JsonPrimitive) ?: JsonNull else JsonNull),
                            "matched_size" to (if (available) o?.size?.let(::JsonPrimitive) ?: JsonNull else JsonNull),
                            "quantity" to JsonPrimitive(l.quantity),
                            "unit_price" to (if (available) money(o?.unitPriceMinor, c) else JsonNull),
                            "line_total" to (if (available) money(o?.unitPriceMinor?.times(l.quantity), c) else JsonNull),
                            "match" to JsonPrimitive(m.match),
                            "note" to (m.note?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    )
                }
                val unavailable = matched.count { it.second.match == "unavailable" }
                val availability = when (unavailable) { 0 -> "available"; matched.size -> "unavailable"; else -> "partial" }
                val subtotal = matched.filter { it.second.match != "unavailable" }.sumOf { (l, _) -> byLine.getValue(l.lineId).unitPriceMinor!! * l.quantity }
                val fees = reading.fees
                val unknown = (Fees.KEYS + "discounts").filter { it in fees.unknown }
                val components = buildMap<String, JsonElement> {
                    put("item_subtotal", money(subtotal, c))
                    Fees.KEYS.forEach { put(it, money(fees.amounts[it], c)) }
                    put("discounts", money(fees.amounts["discounts"], c))
                }
                // Complete carts only; known fees added, discounts taken off, unknown fees listed rather than guessed.
                val total = if (availability == "available") {
                    subtotal + Fees.KEYS.sumOf { fees.amounts[it] ?: 0 } - (fees.amounts["discounts"] ?: 0)
                } else null
                val unavailableFields = unknown + listOfNotNull("eta_minutes".takeIf { reading.etaMinutes == null && reading.source == "device" })
                val notes = reading.notes + listOfNotNull(
                    if (unavailable in 1 until matched.size) "$unavailable of ${matched.size} items aren't available" else null,
                )
                val json = JsonObject(
                    mapOf(
                        "provider" to JsonPrimitive(provider),
                        "availability" to JsonPrimitive(availability),
                        "captured_at" to JsonPrimitive(reading.capturedAt.toString()),
                        "lines" to JsonArray(lines),
                        "components" to JsonObject(components),
                        "total_estimate" to money(total, c),
                        "eta_minutes" to (reading.etaMinutes?.let(::JsonPrimitive) ?: JsonNull),
                        "rank" to JsonNull,
                        "unavailable_fields" to JsonArray(unavailableFields.map(::JsonPrimitive)),
                        "notes" to JsonArray(notes.map(::JsonPrimitive)),
                        "source" to (reading.source?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                )
                Result(provider, json, total, reading.etaMinutes, reading, matched.map { it.second.match }, unknown.toSet())
            }
        }
    }

    fun compute(input: EngineInput, now: Instant): JsonObject {
        val results = input.readings.map { (p, r) -> result(input, p, r) }
        val name = { p: String -> input.providerNames[p] ?: p }

        val waiting = results.filter { it.read is Reading.Waiting }
        val checked = results.filter { it.read is Reading.Read || (it.read as? Reading.NotRead)?.availability == "unavailable" }.map { it.provider }
        val failed = results.filter { (it.read as? Reading.NotRead)?.availability == "failed" }.map { it.provider }
        val complete = failed.isEmpty() && checked.size == results.size && results.isNotEmpty()
        val missingFees = (Fees.KEYS + "discounts").filter { k -> results.any { k in it.unknownFees } }
        val stale = results.mapNotNull { (it.read as? Reading.Read)?.capturedAt }.any { Duration.between(it, now) > FRESHNESS }
        val incomparable = results.any { r -> r.lineMatches.any { it == "different_pack" || it == "different_class" } }

        val comparable = results.filter { it.total != null }
        val fastest = input.objective == "fastest"
        val ordered = comparable.sortedWith(
            if (fastest) compareBy<Result>({ it.eta ?: Int.MAX_VALUE }, { it.total }) else compareBy({ it.total }),
        )
        val ranks = ordered.withIndex().associate { (i, r) -> r.provider to i + 1 }

        val status = when {
            input.manualOnly -> "manual_only"
            waiting.isNotEmpty() -> "in_progress"
            comparable.isEmpty() && failed.isEmpty() -> "unavailable"
            incomparable -> "incomparable"
            stale -> "stale"
            !complete -> "partial_coverage"
            else -> "complete"
        }

        val best = ordered.firstOrNull()
        val selected = input.selectedProvider?.let { s -> results.firstOrNull { it.provider == s } }
        val (label, reason, pick) = when (status) {
            "manual_only" -> Triple("none", "These apps are compared by you for now: open each one and check the cart.", null)
            "in_progress" -> Triple(
                "none",
                if (waiting.any { it.read.source == "device" }) "Still checking prices on your phone." else "Checking prices in your apps.",
                null,
            )
            "unavailable" -> Triple("none", "None of the ${input.category} apps can deliver this cart right now.", null)
            "incomparable" -> Triple("none", "Pack sizes differ between apps, so totals are not directly comparable. Choose which option you prefer.", null)
            else -> when {
                best == null -> Triple("none", "No app had every item with a known total.", null)
                input.objective == "preferred_provider" && selected?.total != null ->
                    Triple("preferred", "Your preferred app, ${name(selected.provider)}, has every item.", selected)
                fastest && best.eta != null -> Triple("fastest", "Fastest estimated delivery among the apps that have every item.", best)
                status == "complete" && missingFees.isEmpty() ->
                    Triple("cheapest", "Lowest estimated complete-cart total across all ${results.size} apps checked.", best)
                else -> Triple("lowest_observed_estimate", observedReason(stale, failed.map(name), missingFees.isNotEmpty()), best)
            }
        }
        val savings = if (pick != null && selected != null && selected.provider != pick.provider && selected.total != null && pick.total != null && selected.total > pick.total) {
            money(selected.total - pick.total, input.currency)
        } else JsonNull

        val equivocal = results.any { r -> r.lineMatches.any { it == "equivalent" || it == "uncertain" } }
        val confidence = when {
            status == "unavailable" -> "high"
            status in setOf("in_progress", "incomparable", "stale", "manual_only") -> "low"
            // Fares move minute to minute (surge), so a ride comparison is never more than an estimate.
            status == "partial_coverage" || missingFees.isNotEmpty() || equivocal || input.category == "travel" -> "medium"
            else -> "high"
        }

        return JsonObject(
            mapOf(
                "quote_id" to JsonPrimitive(input.quoteId),
                "category" to JsonPrimitive(input.category),
                "status" to JsonPrimitive(status),
                "currency" to JsonPrimitive(input.currency),
                "created_at" to JsonPrimitive(input.createdAt.toString()),
                "expires_at" to JsonPrimitive(input.expiresAt.toString()),
                "objective" to JsonPrimitive(input.objective),
                "selected_provider" to (input.selectedProvider?.let(::JsonPrimitive) ?: JsonNull),
                "recommendation" to JsonObject(
                    mapOf(
                        "provider" to (pick?.provider?.let(::JsonPrimitive) ?: JsonNull),
                        "label" to JsonPrimitive(label),
                        "reason" to JsonPrimitive(reason),
                        "savings_vs_selected" to savings,
                    ),
                ),
                "coverage" to JsonObject(
                    mapOf(
                        "checked" to JsonArray(checked.map(::JsonPrimitive)),
                        "failed" to JsonArray(failed.map(::JsonPrimitive)),
                        "complete" to JsonPrimitive(complete),
                    ),
                ),
                "provider_results" to JsonArray(results.map { r -> JsonObject(r.json + ("rank" to (ranks[r.provider]?.let(::JsonPrimitive) ?: JsonNull))) }),
                "missing_fields" to JsonArray(missingFees.map(::JsonPrimitive)),
                "assumptions" to JsonArray(input.assumptions.map(::JsonPrimitive)),
                "confidence" to JsonPrimitive(confidence),
                "requires_user_confirmation" to JsonPrimitive(true),
                "previous_total_estimate" to JsonNull,
            ),
        )
    }

    private fun observedReason(stale: Boolean, failed: List<String>, missingFee: Boolean): String = listOfNotNull(
        "Lowest observed estimate.",
        if (stale) "Prices were checked more than ${FRESHNESS.toMinutes()} minutes ago and may have changed." else null,
        if (failed.isNotEmpty()) "${failed.joinToString()} could not be checked." else null,
        if (missingFee) "Some fees were not visible." else null,
    ).joinToString(" ")
}
