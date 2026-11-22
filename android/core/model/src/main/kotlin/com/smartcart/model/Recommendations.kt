package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReasonCode {
    @SerialName("recent") RECENT,
    @SerialName("frequent") FREQUENT,
    @SerialName("regular_interval") REGULAR_INTERVAL,
    @SerialName("due_soon") DUE_SOON,
    @SerialName("weekday_pattern") WEEKDAY_PATTERN,
    @SerialName("seasonal") SEASONAL,
}

@Serializable
data class Recommendation(
    val recommendationId: String,
    val label: String,
    val category: Category,
    val confidence: Confidence,
    val reasonCodes: List<ReasonCode>,
    val explanation: String,
    val usualProvider: ProviderKey? = null,
    val prefillLines: List<CartLine> = emptyList(),
)

@Serializable
data class RecommendationList(val recommendations: List<Recommendation>, val disclaimer: String)
