package com.smartcart.data.device

import com.smartcart.automation.FeeReading
import com.smartcart.automation.ItemRequest
import com.smartcart.automation.OfferReading
import com.smartcart.model.Availability
import com.smartcart.model.ComparisonQuote
import com.smartcart.model.ComparisonStatus
import com.smartcart.model.CostComponents
import com.smartcart.model.Coverage
import com.smartcart.model.LineMatch
import com.smartcart.model.MatchedLine
import com.smartcart.model.Money
import com.smartcart.model.Objective
import com.smartcart.model.ProviderKey
import com.smartcart.model.ProviderResult
import com.smartcart.model.QuoteRecommendation
import com.smartcart.model.RecommendationLabel

/**
 * Turns what the phone read in a provider app into the contract's [ProviderResult], and puts it into a quote,
 * when there is no backend (Phase 4.3). The rules are the comparison rules (PRD FR-5A, the server's engine in
 * 4.4): only complete carts get a total, made of the items and the fees the app states; a fee it doesn't state
 * is listed as unavailable, never assumed to be zero, and rules out "Cheapest", as does missing coverage.
 */
object CapturedQuote {
    /** The item to look up for a quote line: the matched product name and size, as tasks use them. */
    fun item(line: MatchedLine) = ItemRequest(listOfNotNull(line.matchedName ?: line.requestedName, line.matchedSize).joinToString(" "), line.quantity)

    private fun money(minor: Long?, currency: String) = minor?.let { Money(it, currency) }

    fun result(
        provider: ProviderKey,
        appName: String,
        lines: List<MatchedLine>,
        offers: List<OfferReading>,
        fees: FeeReading?,
        capturedAt: String,
        currency: String,
    ): ProviderResult {
        val read = lines.map { l ->
            val o = offers.firstOrNull { it.item == item(l) }
            when {
                o == null || !o.found || o.unitPriceMinor == null ->
                    l.copy(matchedName = null, matchedSize = null, unitPrice = null, lineTotal = null, match = LineMatch.UNAVAILABLE, note = "Not found in $appName")
                !o.inStock ->
                    l.copy(matchedName = o.name, matchedSize = null, unitPrice = money(o.unitPriceMinor, currency), lineTotal = null, match = LineMatch.UNAVAILABLE, note = "Out of stock")
                else ->
                    l.copy(matchedName = o.name, matchedSize = null, unitPrice = money(o.unitPriceMinor, currency), lineTotal = money(o.unitPriceMinor!! * l.quantity, currency), match = LineMatch.EXACT, note = null)
            }
        }
        val available = read.count { it.match != LineMatch.UNAVAILABLE }
        val availability = when (available) {
            read.size -> Availability.AVAILABLE
            0 -> Availability.UNAVAILABLE
            else -> Availability.PARTIAL
        }
        val subtotal = read.mapNotNull { it.lineTotal?.amountMinor }.sum()
        val components = CostComponents(
            itemSubtotal = Money(subtotal, currency),
            deliveryFee = money(fees?.deliveryFeeMinor, currency),
            packagingFee = money(fees?.packagingFeeMinor, currency),
            platformFee = money(fees?.platformFeeMinor, currency),
            smallCartFee = money(fees?.smallCartFeeMinor, currency),
            taxes = money(fees?.taxesMinor, currency),
        )
        val unknownFees = listOf(
            "delivery_fee" to components.deliveryFee, "packaging_fee" to components.packagingFee, "platform_fee" to components.platformFee,
            "small_cart_fee" to components.smallCartFee, "taxes" to components.taxes,
        ).filter { it.second == null }.map { it.first }
        // A total is only given for the whole cart: items plus the fees the app states.
        val total = if (availability == Availability.AVAILABLE) {
            subtotal + listOfNotNull(components.deliveryFee, components.packagingFee, components.platformFee, components.smallCartFee, components.taxes).sumOf { it.amountMinor }
        } else null
        val missing = read.count { it.match == LineMatch.UNAVAILABLE }
        return ProviderResult(
            provider = provider,
            availability = availability,
            capturedAt = capturedAt,
            lines = read,
            components = components,
            totalEstimate = money(total, currency),
            etaMinutes = null,
            rank = null,
            unavailableFields = unknownFees + "eta_minutes",
            notes = listOfNotNull(
                "Prices read on this phone from $appName",
                if (missing > 0) "$missing of ${read.size} items aren't available" else null,
                if (unknownFees.isNotEmpty() && availability == Availability.AVAILABLE) "$appName doesn't show every fee" else null,
            ),
        )
    }

    /** A provider the phone couldn't read: it counts as not checked. */
    fun failed(provider: ProviderKey, lines: List<MatchedLine>, reason: String, capturedAt: String) = ProviderResult(
        provider = provider,
        availability = Availability.FAILED,
        capturedAt = capturedAt,
        lines = lines.map { it.copy(unitPrice = null, lineTotal = null) },
        components = CostComponents(),
        totalEstimate = null,
        rank = null,
        unavailableFields = listOf("item_subtotal", "delivery_fee", "packaging_fee", "platform_fee", "small_cart_fee", "taxes", "eta_minutes"),
        notes = listOf(reason),
    )

    /** [quote] with [result] in place of that provider's result, and ranks, coverage, status and recommendation redone. */
    fun merge(quote: ComparisonQuote, result: ProviderResult): ComparisonQuote {
        val p = result.provider
        val results = quote.providerResults.map { if (it.provider == p) result else it }
        val failed = (quote.coverage.failed - p) + if (result.availability == Availability.FAILED) listOf(p) else emptyList()
        val coverage = Coverage(checked = quote.coverage.checked, failed = failed, complete = failed.isEmpty() && quote.coverage.checked.isNotEmpty())
        val comparable = results.filter { it.availability == Availability.AVAILABLE && it.totalEstimate != null }.sortedBy { it.totalEstimate!!.amountMinor }
        val fees = setOf("delivery_fee", "packaging_fee", "platform_fee", "small_cart_fee", "surge_or_booking_fee", "taxes", "discounts")
        val missing = results.flatMap { r -> r.unavailableFields.filter { it in fees } }.distinct()
        val ranked = results.map { r -> r.copy(rank = comparable.indexOfFirst { it.provider == r.provider }.takeIf { it >= 0 }?.plus(1)) }

        // Only quotes in a normal comparable state are re-ranked; the other fixture states keep their meaning.
        if (quote.status !in setOf(ComparisonStatus.COMPLETE, ComparisonStatus.PARTIAL_COVERAGE)) return quote.copy(providerResults = ranked, coverage = coverage, missingFields = missing)
        val status = if (coverage.complete) ComparisonStatus.COMPLETE else ComparisonStatus.PARTIAL_COVERAGE
        val best = comparable.firstOrNull()
        val recommendation = when {
            quote.objective != Objective.LOWEST_TOTAL -> quote.recommendation
            best == null -> QuoteRecommendation(null, RecommendationLabel.NONE, "No app had every item with a known total.")
            else -> {
                val selected = quote.selectedProvider?.let { s -> results.firstOrNull { it.provider == s }?.totalEstimate }
                QuoteRecommendation(
                    provider = best.provider,
                    label = if (coverage.complete && missing.isEmpty()) RecommendationLabel.CHEAPEST else RecommendationLabel.LOWEST_OBSERVED_ESTIMATE,
                    reason = when {
                        !coverage.complete -> "Lowest estimated total among the apps that could be checked; ${failed.joinToString { it.displayName }} couldn't be checked."
                        missing.isNotEmpty() -> "Lowest observed estimate. Some fees were not visible."
                        else -> "Lowest estimated complete-cart total across all ${coverage.checked.size} apps checked."
                    },
                    savingsVsSelected = selected?.takeIf { quote.selectedProvider != best.provider && it.amountMinor > best.totalEstimate!!.amountMinor }
                        ?.let { Money(it.amountMinor - best.totalEstimate!!.amountMinor, it.currency) },
                )
            }
        }
        return quote.copy(providerResults = ranked, coverage = coverage, status = status, recommendation = recommendation, missingFields = missing)
    }
}
