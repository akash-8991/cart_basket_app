package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Objective {
    @SerialName("lowest_total") LOWEST_TOTAL,
    @SerialName("fastest") FASTEST,
    @SerialName("best_value") BEST_VALUE,
    @SerialName("fewest_providers") FEWEST_PROVIDERS,
    @SerialName("preferred_provider") PREFERRED_PROVIDER,
}

@Serializable
enum class ComparisonStatus {
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("complete") COMPLETE,
    @SerialName("partial_coverage") PARTIAL_COVERAGE,
    @SerialName("stale") STALE,
    @SerialName("incomparable") INCOMPARABLE,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("price_changed") PRICE_CHANGED,
    @SerialName("manual_only") MANUAL_ONLY,
}

@Serializable
enum class LineMatch {
    @SerialName("exact") EXACT,
    @SerialName("equivalent") EQUIVALENT,
    @SerialName("different_pack") DIFFERENT_PACK,
    @SerialName("different_class") DIFFERENT_CLASS,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("uncertain") UNCERTAIN,
}

@Serializable
data class MatchedLine(
    val lineId: String,
    val requestedName: String,
    val matchedName: String? = null,
    val matchedSize: String? = null,
    val quantity: Int = 1,
    val unitPrice: Money? = null,
    val lineTotal: Money? = null,
    val match: LineMatch,
    val note: String? = null,
)

@Serializable
data class CostComponents(
    val itemSubtotal: Money? = null,
    val deliveryFee: Money? = null,
    val packagingFee: Money? = null,
    val platformFee: Money? = null,
    val smallCartFee: Money? = null,
    val surgeOrBookingFee: Money? = null,
    val taxes: Money? = null,
    val discounts: Money? = null,
) {
    /** Display rows in a fixed order; values come from the server unchanged. */
    fun rows(): List<CostRow> = listOf(
        CostRow("item_subtotal", itemSubtotal),
        CostRow("delivery_fee", deliveryFee),
        CostRow("packaging_fee", packagingFee),
        CostRow("platform_fee", platformFee),
        CostRow("small_cart_fee", smallCartFee),
        CostRow("surge_or_booking_fee", surgeOrBookingFee),
        CostRow("taxes", taxes),
        CostRow("discounts", discounts, subtract = true),
    )
}

/** A display row; [key] matches the contract field name used in `unavailable_fields`. Labels live in app resources. */
data class CostRow(val key: String, val amount: Money?, val subtract: Boolean = false)

@Serializable
enum class Availability {
    @SerialName("available") AVAILABLE,
    @SerialName("partial") PARTIAL,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("not_checked") NOT_CHECKED,
    @SerialName("failed") FAILED,
}

@Serializable
data class ProviderResult(
    val provider: ProviderKey,
    val availability: Availability,
    val capturedAt: String? = null,
    val lines: List<MatchedLine>,
    val components: CostComponents,
    val totalEstimate: Money? = null,
    val etaMinutes: Int? = null,
    val rank: Int? = null,
    val unavailableFields: List<String>,
    val notes: List<String>,
)

@Serializable
enum class RecommendationLabel {
    @SerialName("cheapest") CHEAPEST,
    @SerialName("lowest_observed_estimate") LOWEST_OBSERVED_ESTIMATE,
    @SerialName("fastest") FASTEST,
    @SerialName("best_value") BEST_VALUE,
    @SerialName("preferred") PREFERRED,
    @SerialName("none") NONE,
}

@Serializable
data class QuoteRecommendation(
    val provider: ProviderKey? = null,
    val label: RecommendationLabel,
    val reason: String,
    val savingsVsSelected: Money? = null,
)

@Serializable
data class Coverage(val checked: List<ProviderKey>, val failed: List<ProviderKey>, val complete: Boolean)

@Serializable
data class ComparisonQuote(
    val quoteId: String,
    val category: Category,
    val status: ComparisonStatus,
    val currency: String,
    val createdAt: String,
    val expiresAt: String,
    val objective: Objective,
    val selectedProvider: ProviderKey? = null,
    val recommendation: QuoteRecommendation,
    val coverage: Coverage,
    val providerResults: List<ProviderResult>,
    val missingFields: List<String>,
    val assumptions: List<String>,
    val confidence: Confidence,
    val requiresUserConfirmation: Boolean,
    val previousTotalEstimate: Money? = null,
) {
    /** UI guard mirroring the contract rule: never show "Cheapest" without full coverage (audit §10.2). */
    val badgeLabel: RecommendationLabel
        get() = if (recommendation.label == RecommendationLabel.CHEAPEST && (!coverage.complete || missingFields.isNotEmpty())) {
            RecommendationLabel.LOWEST_OBSERVED_ESTIMATE
        } else {
            recommendation.label
        }

    val rankedResults: List<ProviderResult>
        get() = providerResults.sortedWith(compareBy(nullsLast()) { it.rank })

    fun needsSwitchConfirmation(chosen: ProviderKey): Boolean = selectedProvider != null && chosen != selectedProvider
}

@Serializable
data class QuoteRequest(
    val category: Category,
    val cartLines: List<CartLine> = emptyList(),
    val ride: RideRequest? = null,
    val eligibleProviders: List<ProviderKey>,
    val selectedProvider: ProviderKey? = null,
    val objective: Objective,
    val chatSessionId: String? = null,
)

@Serializable
data class QuoteConfirmation(val quoteId: String, val confirmedProvider: ProviderKey, val confirmedAt: String)
