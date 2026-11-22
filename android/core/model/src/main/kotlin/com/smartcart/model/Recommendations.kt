package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReasonCode(val label: String) {
    @SerialName("recent") RECENT("Recent"),
    @SerialName("frequent") FREQUENT("Frequent"),
    @SerialName("regular_interval") REGULAR_INTERVAL("Regular interval"),
    @SerialName("due_soon") DUE_SOON("Due soon"),
    @SerialName("weekday_pattern") WEEKDAY_PATTERN("Weekday pattern"),
    @SerialName("seasonal") SEASONAL("Seasonal"),
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
