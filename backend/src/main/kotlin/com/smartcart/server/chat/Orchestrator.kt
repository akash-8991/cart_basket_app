package com.smartcart.server.chat

import com.smartcart.server.flags.Flags
import com.smartcart.server.providers.ProviderRegistry
import kotlinx.serialization.Serializable

/** Masks contact and payment details before chat text is stored or sent to a model (data-classification.md). */
object Redactor {
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val card = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    private val phone = Regex("(?<!\\d)(?:\\+?91[ -]?)?[6-9]\\d{4}[ -]?\\d{5}(?!\\d)")

    fun redact(text: String): String = text
        .replace(email, "[email]")
        .replace(card, "[number]")
        .replace(phone, "[phone]")
}

/** A clarification the assistant is waiting on, kept in the session state between turns. */
@Serializable
data class PendingClarification(
    val clarificationId: String,
    val options: List<ClarificationOption>,
    val entities: List<IntentOutput.Entity>,
    val provider: String?,
)

data class Decision(
    val intent: TurnIntent,
    val nextStep: String,
    val reply: String,
    val clarification: Clarification? = null,
    val draft: CartDraft? = null,
    val degraded: Boolean = false,
    val pending: PendingClarification? = null,
)

/**
 * Deterministic orchestration (plan rule 2: Claude advises, code decides). Whatever the model said, this
 * code picks the next step, keeps the chat inside its category, and only builds drafts from validated
 * entities. It also answers on its own when the model isn't used (the fallback).
 */
class Orchestrator(private val flags: Flags, private val newId: (String) -> String) {

    private fun reviewStep() = if (flags.isOn("comparison.enabled")) "compare" else "review_cart"

    fun fromModel(category: String, out: IntentOutput): Decision {
        val intentCategory = out.category ?: category
        val intent = TurnIntent(out.intentType, intentCategory, out.confidence)
        if (out.intentType in setOf("order", "book_ride") && intentCategory != category) {
            return Decision(intent, "none", "This chat is for ${label(category)}. Start a ${label(intentCategory)} chat for that.")
        }
        val provider = out.provider?.takeIf { ProviderRegistry.find(it)?.category == category }
        return when (out.intentType) {
            "order" -> when {
                out.entities.isEmpty() -> Decision(intent, "none", out.replyText)
                out.clarificationNeeded -> clarify(intent, out.clarificationQuestion!!, out.clarificationOptions, out.replyText, out.entities, provider)
                else -> Decision(intent, reviewStep(), out.replyText, draft = draft(category, provider, out.entities))
            }
            "book_ride" -> {
                val ride = out.ride
                if (ride?.pickupLabel.isNullOrBlank() || ride?.dropLabel.isNullOrBlank()) {
                    Decision(intent, "clarify", out.replyText, clarification = Clarification(newId("clr"), out.clarificationQuestion ?: "Where should the ride start and end?", emptyList()))
                } else {
                    Decision(intent, reviewStep(), out.replyText, draft = CartDraft(category, provider, emptyList(), RideRequest(ride!!.pickupLabel!!, ride.dropLabel!!, ride.rideClass ?: "any", null)))
                }
            }
            "view_summary" -> Decision(intent, "open_dashboard", out.replyText)
            "import_history" ->
                if (flags.isOn("history.import.enabled")) Decision(intent, "start_import", out.replyText)
                else Decision(intent, "none", "Importing order history isn't available yet.")
            else -> Decision(intent, "none", out.replyText)
        }
    }

    /** The user tapped an option: resolved here, without asking the model again. */
    fun answer(category: String, pending: PendingClarification, optionId: String): Decision? {
        val chosen = pending.options.firstOrNull { it.optionId == optionId } ?: return null
        val picked = RuleBasedIntentModel.parseItem(chosen.label)
        val words = picked.name.lowercase().split(' ').toSet()
        val i = pending.entities.indexOfFirst { e -> e.name.lowercase().split(' ').any { it in words } }
        val entities = if (i >= 0) pending.entities.toMutableList().also { it[i] = picked.copy(quantity = it[i].quantity) } else pending.entities + picked
        return Decision(
            TurnIntent("order", category, 0.95), reviewStep(), "Here is your cart.",
            draft = draft(category, pending.provider, entities),
        )
    }

    /**
     * No model (off, no consent, over budget, failed or rejected): read the list exactly as typed, one
     * item per line or comma, or ask for it in that form.
     */
    fun fallback(category: String, text: String): Decision {
        val unknown = TurnIntent("unknown", null, 0.0)
        if (category == "travel") {
            val m = Regex("""from\s+(.+?)\s+to\s+(.+?)(?:[.,!?]|$)""", RegexOption.IGNORE_CASE).find(text)
            return if (m != null) {
                Decision(TurnIntent("book_ride", "travel", 0.5), reviewStep(), "I read your ride as written. Check it below.", degraded = true,
                    draft = CartDraft("travel", null, emptyList(), RideRequest(m.groupValues[1].trim(), m.groupValues[2].trim(), "any", null)))
            } else Decision(unknown, "none", "The assistant is temporarily limited. Tell me the ride as \"from Home to Airport\".", degraded = true)
        }
        val looksLikeList = Regex("(?m)^\\s*\\d{1,2}\\s*[x×*]").containsMatchIn(text) || "\n" in text || "," in text
        val items = if (looksLikeList) RuleBasedIntentModel.splitItems(text).map(RuleBasedIntentModel::parseItem) else emptyList()
        return if (items.isNotEmpty()) {
            Decision(TurnIntent("order", category, 0.5), reviewStep(), "I couldn't use the assistant, so I read your list as written. Check the items below.",
                draft = draft(category, null, items), degraded = true)
        } else {
            Decision(unknown, "none", DEGRADED_REPLY, degraded = true)
        }
    }

    private fun clarify(intent: TurnIntent, question: String, options: List<String>, reply: String, entities: List<IntentOutput.Entity>, provider: String?): Decision {
        val c = Clarification(newId("clr"), question, options.take(6).mapIndexed { i, l -> ClarificationOption("o${i + 1}", l) })
        return Decision(intent, "clarify", reply, clarification = c, pending = PendingClarification(c.clarificationId, c.options, entities, provider))
    }

    private fun draft(category: String, provider: String?, entities: List<IntentOutput.Entity>) = CartDraft(
        category, provider,
        entities.mapIndexed { i, e -> CartLine("l${i + 1}", e.name, e.quantity ?: 1, e.size, null, e.modifiers) },
        null,
    )

    private fun label(c: String) = when (c) { "food" -> "food ordering"; else -> c }

    companion object {
        const val DEGRADED_REPLY = "The assistant is temporarily limited. Tell me the items and quantities one per line, for example \"2 x toned milk 1 L\"."
    }
}
