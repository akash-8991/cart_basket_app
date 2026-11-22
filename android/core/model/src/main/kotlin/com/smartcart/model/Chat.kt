package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatSession(
    val sessionId: String,
    val category: Category,
    val title: String,
    val createdAt: String,
    val updatedAt: String? = null,
)

@Serializable
data class ChatSessionPage(val sessions: List<ChatSession>, val nextCursor: String? = null)

@Serializable
enum class ChatRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("system") SYSTEM,
}

@Serializable
data class ChatMessage(
    val messageId: String,
    val role: ChatRole,
    val text: String,
    val createdAt: String,
    val turn: AssistantTurn? = null,
)

@Serializable
data class ChatSessionDetail(val session: ChatSession, val messages: List<ChatMessage>)

@Serializable
data class CartLine(
    val lineId: String,
    val name: String,
    val quantity: Int,
    val size: String? = null,
    val brand: String? = null,
    val modifiers: List<String> = emptyList(),
) {
    val label: String get() = listOfNotNull(name, size).joinToString(" ")
}

@Serializable
enum class RideClass {
    @SerialName("bike") BIKE,
    @SerialName("auto") AUTO,
    @SerialName("mini") MINI,
    @SerialName("sedan") SEDAN,
    @SerialName("suv") SUV,
    @SerialName("any") ANY,
}

@Serializable
data class RideRequest(
    val pickupLabel: String,
    val dropLabel: String,
    val rideClass: RideClass,
    val `when`: String? = null,
)

@Serializable
data class CartDraft(
    val category: Category,
    val selectedProvider: ProviderKey? = null,
    val lines: List<CartLine> = emptyList(),
    val ride: RideRequest? = null,
)

@Serializable
data class ClarificationOption(val optionId: String, val label: String)

@Serializable
data class Clarification(
    val clarificationId: String,
    val question: String,
    val options: List<ClarificationOption>,
)

@Serializable
enum class IntentType {
    @SerialName("order") ORDER,
    @SerialName("book_ride") BOOK_RIDE,
    @SerialName("view_summary") VIEW_SUMMARY,
    @SerialName("suggest_purchase") SUGGEST_PURCHASE,
    @SerialName("import_history") IMPORT_HISTORY,
    @SerialName("general") GENERAL,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class Intent(val type: IntentType, val category: Category? = null, val confidence: Double)

@Serializable
enum class NextStep {
    @SerialName("none") NONE,
    @SerialName("clarify") CLARIFY,
    @SerialName("review_cart") REVIEW_CART,
    @SerialName("compare") COMPARE,
    @SerialName("open_dashboard") OPEN_DASHBOARD,
    @SerialName("start_import") START_IMPORT,
}

@Serializable
data class AssistantTurn(
    val messageId: String,
    val replyText: String,
    val intent: Intent,
    val nextStep: NextStep,
    val clarification: Clarification? = null,
    val cartDraft: CartDraft? = null,
    val degraded: Boolean = false,
)
