package com.smartcart.server.stubs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// ---------------------------------------------------------------- CR-005 request bodies

@Serializable data class MoneyIn(val amountMinor: Long, val currency: String)
@Serializable data class AdapterRef(val key: String, val version: String)
@Serializable data class ObservedOffer(val lineId: String, val found: Boolean, val name: String? = null, val unitPrice: MoneyIn? = null, val inStock: Boolean = false)
@Serializable data class ObservedFees(
    val deliveryFee: MoneyIn? = null,
    val packagingFee: MoneyIn? = null,
    val platformFee: MoneyIn? = null,
    val smallCartFee: MoneyIn? = null,
    val surgeOrBookingFee: MoneyIn? = null,
    val taxes: MoneyIn? = null,
    val discounts: MoneyIn? = null,
) {
    fun named(): List<Pair<String, MoneyIn?>> = listOf(
        "delivery_fee" to deliveryFee, "packaging_fee" to packagingFee, "platform_fee" to platformFee, "small_cart_fee" to smallCartFee,
        "surge_or_booking_fee" to surgeOrBookingFee, "taxes" to taxes, "discounts" to discounts,
    )
}
@Serializable data class PriceObservation(
    val provider: String,
    val capturedAt: String,
    val outcome: String,
    val notReadReason: String? = null,
    val adapter: AdapterRef,
    val offers: List<ObservedOffer> = emptyList(),
    val fees: ObservedFees? = null,
    val etaMinutes: Int? = null,
)
@Serializable data class TaskEventIn(val seq: Int, val at: String, val type: String, val description: String, val actionId: String? = null)
@Serializable data class CheckpointIn(val checkpointId: String, val prompt: String, val risk: String)
@Serializable data class NeedsUserIn(val reason: String, val message: String)
@Serializable data class FailureIn(val code: String, val message: String)
@Serializable data class CartLineIn(
    val lineId: String,
    val requestedName: String,
    val matchedName: String? = null,
    val matchedSize: String? = null,
    val quantity: Int = 1,
    val unitPrice: MoneyIn? = null,
    val lineTotal: MoneyIn? = null,
    val match: String,
    val note: String? = null,
)
@Serializable data class CartIn(
    val verified: Boolean,
    val verifiedAt: String? = null,
    val lines: List<CartLineIn>,
    val totalEstimate: MoneyIn? = null,
    val differsFromQuote: Boolean = false,
)
@Serializable data class TaskProgressReport(
    val reportSeq: Int,
    val status: String,
    val actionsUsed: Int,
    val events: List<TaskEventIn>,
    val checkpoint: CheckpointIn? = null,
    val needsUserAction: NeedsUserIn? = null,
    val failure: FailureIn? = null,
    val cart: CartIn? = null,
    val adapter: AdapterRef,
)

/** CR-005 helpers that aren't the comparison itself (that is [com.smartcart.server.comparison.ComparisonEngine]). */
internal object DeviceReadings {
    private fun JsonObject.str(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    fun notReadNote(reason: String?, displayName: String): String = when (reason) {
        "service_off" -> "SmartCart's accessibility service was off, so $displayName wasn't read"
        "consent_missing" -> "Automation for $displayName isn't allowed, so it wasn't read"
        "app_not_installed" -> "$displayName isn't installed on your phone"
        "package_mismatch" -> "The $displayName app on your phone couldn't be verified"
        "login_required", "otp_required", "password_prompt", "biometric", "captcha" -> "$displayName wanted you to sign in or verify, so prices weren't read"
        "payment_screen" -> "$displayName showed a payment screen, so reading stopped"
        "unknown_screen", "unexpected_dialog" -> "$displayName showed a screen your phone didn't recognise"
        "kill_switch" -> "Automation for $displayName is paused"
        else -> "Your phone couldn't read $displayName"
    }

    /** The items approved for [provider]'s cart: name (with size) and quantity, from the quote it is bound to. */
    fun approvedItems(quote: JsonObject, provider: String): List<Pair<String, Int>> =
        quote["provider_results"]!!.jsonArray.map { it.jsonObject }.first { it.str("provider") == provider }["lines"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it.str("match") != "unavailable" }
            .map { l -> listOfNotNull(l.str("matched_name") ?: l.str("requested_name"), l.str("matched_size")).joinToString(" ").lowercase() to (l["quantity"]?.jsonPrimitive?.content?.toInt() ?: 1) }
}
