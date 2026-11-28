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
    /** CR-007: upload sessions only; salts the hash that finds duplicates without the provider's order ID. */
    val dedupSalt: String? = null,
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

// ---------------------------------------------------------------- CR-007: what the phone reports from an import

@Serializable
enum class ImportFailureReason {
    @SerialName("login_required") LOGIN_REQUIRED,
    @SerialName("unknown_screen") UNKNOWN_SCREEN,
    @SerialName("package_mismatch") PACKAGE_MISMATCH,
    @SerialName("app_not_installed") APP_NOT_INSTALLED,
    @SerialName("timeout") TIMEOUT,
    @SerialName("stopped") STOPPED,
}

@Serializable
enum class ImportOutcome {
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class UploadedItem(val name: String, val quantity: Int? = null, val amount: Money? = null)

/** One normalised order, carrying only the fields the person chose. */
@Serializable
data class UploadedOrder(
    val sourceRef: String,
    val orderedAt: String? = null,
    val status: OrderStatus? = null,
    val total: Money? = null,
    val items: List<UploadedItem>? = null,
    val locationLabel: String? = null,
    val extractionConfidence: Confidence,
)

/** Body of `POST /v1/history/import/{sessionId}/batches`. */
@Serializable
data class HistoryBatch(
    val batchSeq: Int,
    val provider: ProviderKey,
    val status: ProviderImportStatus,
    val ordersFound: Int,
    val confidence: Confidence? = null,
    val failureReason: ImportFailureReason? = null,
    val adapter: AdapterRef,
    val reviewed: Boolean,
    val orders: List<UploadedOrder> = emptyList(),
    val importOutcome: ImportOutcome? = null,
)
