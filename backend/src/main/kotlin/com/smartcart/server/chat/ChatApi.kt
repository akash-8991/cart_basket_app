package com.smartcart.server.chat

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire types for the chat tag of the frozen contract.

@Serializable data class CreateChatSessionRequest(val category: String)

@Serializable data class ChatSession(val sessionId: String, val category: String, val title: String, val createdAt: String, val updatedAt: String?)

@Serializable data class ChatSessionPage(val sessions: List<ChatSession>, val nextCursor: String?)

@Serializable data class ChatSessionDetail(val session: ChatSession, val messages: List<ChatMessage>)

@Serializable data class ChatMessage(val messageId: String, val role: String, val text: String, val createdAt: String, val turn: AssistantTurn?)

@Serializable data class ClarificationAnswer(val clarificationId: String, val optionId: String)

@Serializable data class ChatContext(val installedProviders: List<String>, val locale: String, val currency: String)

@Serializable data class ChatMessageRequest(
    val sessionId: String,
    val text: String,
    val clarificationAnswer: ClarificationAnswer? = null,
    val context: ChatContext,
)

@Serializable data class TurnIntent(val type: String, val category: String?, val confidence: Double)

@Serializable data class ClarificationOption(val optionId: String, val label: String)

@Serializable data class Clarification(val clarificationId: String, val question: String, val options: List<ClarificationOption>)

@Serializable data class CartLine(
    val lineId: String,
    val name: String,
    val quantity: Int,
    val size: String?,
    val brand: String?,
    val modifiers: List<String>,
)

@Serializable data class RideRequest(val pickupLabel: String, val dropLabel: String, val rideClass: String, @SerialName("when") val whenAt: String?)

@Serializable data class CartDraft(val category: String, val selectedProvider: String?, val lines: List<CartLine>, val ride: RideRequest?)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class AssistantTurn(
    val messageId: String,
    val replyText: String,
    val intent: TurnIntent,
    /** Chosen by the orchestrator, never by the model. */
    val nextStep: String,
    val clarification: Clarification?,
    val cartDraft: CartDraft?,
    /** True when a deterministic fallback answered instead of the model. */
    @EncodeDefault val degraded: Boolean = false,
)

/** What Claude may return for intent extraction (contracts/schemas/claude/intent-extraction.output.schema.json). */
@Serializable data class IntentOutput(
    val intentType: String,
    val category: String?,
    val provider: String?,
    val entities: List<Entity>,
    val ride: RideOut?,
    val clarificationNeeded: Boolean,
    val clarificationQuestion: String?,
    val clarificationOptions: List<String>,
    val replyText: String,
    val confidence: Double,
) {
    @Serializable data class Entity(val name: String, val quantity: Int?, val size: String?, val modifiers: List<String>)
    @Serializable data class RideOut(val pickupLabel: String?, val dropLabel: String?, val rideClass: String?)
}

/** The only data the model ever sees: the redacted message, the chat's category and the installed apps. */
data class IntentInput(val text: String, val category: String, val installedProviders: List<String>)
