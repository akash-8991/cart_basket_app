package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Money in minor units (D-011). The client only formats money; it never computes comparison totals. */
@Serializable
data class Money(val amountMinor: Long, val currency: String) {
    fun format(locale: Locale = Locale.forLanguageTag("en-IN")): String {
        if (currency == "INR") return formatInr()
        val fmt = NumberFormat.getCurrencyInstance(locale)
        fmt.currency = Currency.getInstance(currency)
        val major = BigDecimal.valueOf(amountMinor, fmt.currency.defaultFractionDigits.coerceAtLeast(0))
        val whole = major.stripTrailingZeros().scale() <= 0
        fmt.minimumFractionDigits = if (whole) 0 else 2
        fmt.maximumFractionDigits = if (whole) 0 else 2
        return fmt.format(major)
    }

    /**
     * Indian digit grouping (1,23,456.50). Done by hand because java.text.DecimalFormat on the JVM
     * has no secondary grouping size, so output would differ between Android (ICU) and tests.
     */
    private fun formatInr(): String {
        val negative = amountMinor < 0
        val abs = kotlin.math.abs(amountMinor)
        val rupees = (abs / 100).toString()
        val paise = (abs % 100).toInt()
        val grouped = if (rupees.length <= 3) rupees else {
            val head = rupees.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + rupees.takeLast(3)
        }
        val fraction = if (paise == 0) "" else "." + paise.toString().padStart(2, '0')
        return (if (negative) "-" else "") + "₹" + grouped + fraction
    }

    companion object {
        fun inr(rupees: Long) = Money(rupees * 100, "INR")
    }
}

@Serializable
enum class Category {
    @SerialName("travel") TRAVEL,
    @SerialName("food") FOOD,
    @SerialName("grocery") GROCERY,
    @SerialName("shopping") SHOPPING;

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String): Category? = entries.firstOrNull { it.key == key }
    }
}

@Serializable
enum class ProviderKey(val displayName: String, val category: Category) {
    @SerialName("uber") UBER("Uber", Category.TRAVEL),
    @SerialName("ola") OLA("Ola", Category.TRAVEL),
    @SerialName("rapido") RAPIDO("Rapido", Category.TRAVEL),
    @SerialName("zomato") ZOMATO("Zomato", Category.FOOD),
    @SerialName("swiggy") SWIGGY("Swiggy", Category.FOOD),
    @SerialName("blinkit") BLINKIT("Blinkit", Category.GROCERY),
    @SerialName("bigbasket") BIGBASKET("BigBasket", Category.GROCERY),
    @SerialName("swiggy_instamart") SWIGGY_INSTAMART("Swiggy Instamart", Category.GROCERY),
    @SerialName("amazon") AMAZON("Amazon", Category.SHOPPING),
    @SerialName("flipkart") FLIPKART("Flipkart", Category.SHOPPING),
    @SerialName("zepto") ZEPTO("Zepto", Category.GROCERY);

    val key: String get() = name.lowercase()
}

@Serializable
enum class Confidence {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW,
}

@Serializable
data class ErrorEnvelope(val error: ApiError)

@Serializable
data class ApiError(
    val code: ErrorCode,
    val message: String,
    val correlationId: String,
    val retryAfterSeconds: Int? = null,
    val fieldErrors: List<FieldError> = emptyList(),
)

@Serializable
data class FieldError(val field: String, val code: String)

@Serializable
enum class ErrorCode {
    @SerialName("validation_failed") VALIDATION_FAILED,
    @SerialName("unauthenticated") UNAUTHENTICATED,
    @SerialName("session_expired") SESSION_EXPIRED,
    @SerialName("forbidden") FORBIDDEN,
    @SerialName("not_found") NOT_FOUND,
    @SerialName("conflict") CONFLICT,
    @SerialName("user_id_unavailable") USER_ID_UNAVAILABLE,
    @SerialName("weak_password") WEAK_PASSWORD,
    @SerialName("invalid_credentials") INVALID_CREDENTIALS,
    @SerialName("account_locked") ACCOUNT_LOCKED,
    @SerialName("challenge_expired") CHALLENGE_EXPIRED,
    @SerialName("rate_limited") RATE_LIMITED,
    @SerialName("quote_expired") QUOTE_EXPIRED,
    @SerialName("price_changed") PRICE_CHANGED,
    @SerialName("provider_disabled") PROVIDER_DISABLED,
    @SerialName("consent_required") CONSENT_REQUIRED,
    @SerialName("task_expired") TASK_EXPIRED,
    @SerialName("task_state_conflict") TASK_STATE_CONFLICT,
    @SerialName("policy_blocked") POLICY_BLOCKED,
    @SerialName("ai_unavailable") AI_UNAVAILABLE,
    @SerialName("service_unavailable") SERVICE_UNAVAILABLE,
    @SerialName("internal_error") INTERNAL_ERROR,
    /** Client-side only: no connectivity. Never sent by the server. */
    OFFLINE,
}
