package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ImportStatus {
    @SerialName("awaiting_permission") AWAITING_PERMISSION,
    @SerialName("running") RUNNING,
    @SerialName("paused") PAUSED,
    @SerialName("awaiting_review") AWAITING_REVIEW,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED,
}

@Serializable
enum class ProviderImportStatus {
    @SerialName("pending") PENDING,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("skipped") SKIPPED,
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
enum class OrderStatus {
    PLACED,
    CONFIRMED,
    PREPARING,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    UNKNOWN,
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
enum class ImportField(val key: String) {
    ORDER_DATE("order_date"),
    ITEMS("items"),
    QUANTITY("quantity"),
    AMOUNTS("amounts"),
    STATUS("status"),
    LOCATION_LABEL("location_label"),
}
