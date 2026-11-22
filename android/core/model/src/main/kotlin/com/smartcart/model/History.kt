package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ImportStatus(val label: String) {
    @SerialName("awaiting_permission") AWAITING_PERMISSION("Waiting for permission"),
    @SerialName("running") RUNNING("Importing"),
    @SerialName("paused") PAUSED("Paused"),
    @SerialName("awaiting_review") AWAITING_REVIEW("Ready for review"),
    @SerialName("completed") COMPLETED("Completed"),
    @SerialName("cancelled") CANCELLED("Cancelled"),
    @SerialName("failed") FAILED("Failed"),
}

@Serializable
enum class ProviderImportStatus(val label: String) {
    @SerialName("pending") PENDING("Waiting"),
    @SerialName("running") RUNNING("Reading orders"),
    @SerialName("completed") COMPLETED("Done"),
    @SerialName("failed") FAILED("Skipped safely"),
    @SerialName("skipped") SKIPPED("Skipped"),
}

@Serializable
data class ProviderImportProgress(
    val provider: ProviderKey,
    val status: ProviderImportStatus,
    val ordersFound: Int,
    val confidence: Confidence? = null,
    val message: String? = null,
)

@Serializable
data class ImportSession(
    val sessionId: String,
    val status: ImportStatus,
    val uploadMode: PrivacyMode,
    val providers: List<ProviderImportProgress>,
    val reviewSample: List<Order> = emptyList(),
)

@Serializable
enum class OrderStatus(val label: String) {
    PLACED("Placed"),
    CONFIRMED("Confirmed"),
    PREPARING("Preparing"),
    OUT_FOR_DELIVERY("Out for delivery"),
    DELIVERED("Delivered"),
    CANCELLED("Cancelled"),
    REFUNDED("Refunded"),
    PARTIALLY_REFUNDED("Partially refunded"),
    UNKNOWN("Unknown"),
}

@Serializable
data class OrderItem(val name: String, val quantity: Int, val amount: Money? = null)

@Serializable
data class Order(
    val orderId: String,
    val provider: ProviderKey,
    val category: Category,
    val orderedAt: String,
    val status: OrderStatus,
    val total: Money,
    val items: List<OrderItem>,
    val locationLabel: String? = null,
    val extractionConfidence: Confidence,
)

@Serializable
data class OrderPage(val orders: List<Order>, val nextCursor: String? = null)

/** Fields the user may allow during import (FR-6). */
enum class ImportField(val key: String, val label: String) {
    ORDER_DATE("order_date", "Order date and time"),
    ITEMS("items", "Item names"),
    QUANTITY("quantity", "Quantities"),
    AMOUNTS("amounts", "Amounts, fees and discounts"),
    STATUS("status", "Order status"),
    LOCATION_LABEL("location_label", "Location label (e.g. Home)"),
}
