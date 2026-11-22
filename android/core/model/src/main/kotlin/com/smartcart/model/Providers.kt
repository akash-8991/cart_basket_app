package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CartAutomationCapability {
    @SerialName("supported") SUPPORTED,
    @SerialName("ride_setup_only") RIDE_SETUP_ONLY,
    @SerialName("manual_only") MANUAL_ONLY,
    @SerialName("unsupported") UNSUPPORTED,
}

@Serializable
enum class HistoryCapability {
    @SerialName("supported") SUPPORTED,
    @SerialName("manual_only") MANUAL_ONLY,
    @SerialName("unsupported") UNSUPPORTED,
}

@Serializable
enum class DeepLinkCapability {
    @SerialName("cart") CART,
    @SerialName("app_launch") APP_LAUNCH,
    @SerialName("none") NONE,
}

@Serializable
enum class ComparisonCapability {
    @SerialName("supported") SUPPORTED,
    @SerialName("partial") PARTIAL,
    @SerialName("unsupported") UNSUPPORTED,
}

@Serializable
data class ProviderCapabilities(
    val cartAutomation: CartAutomationCapability,
    val historyImport: HistoryCapability,
    val deepLink: DeepLinkCapability,
    val comparison: ComparisonCapability,
)

@Serializable
enum class ProviderStatus(val label: String) {
    @SerialName("enabled") ENABLED("Available"),
    @SerialName("disabled") DISABLED("Not available"),
    @SerialName("kill_switched") KILL_SWITCHED("Temporarily paused"),
    @SerialName("pending_approval") PENDING_APPROVAL("Coming soon"),
}

@Serializable
data class ProviderConsent(val automation: Boolean, val history: Boolean)

@Serializable
data class Provider(
    val key: ProviderKey,
    val displayName: String,
    val category: Category,
    val capabilities: ProviderCapabilities,
    val status: ProviderStatus,
    val installed: Boolean? = null,
    val consent: ProviderConsent,
    val adapterVersion: String? = null,
) {
    /** Zepto is registry-only (D-005). */
    val hidden: Boolean get() = key == ProviderKey.ZEPTO

    val automationAvailable: Boolean
        get() = status == ProviderStatus.ENABLED &&
            capabilities.cartAutomation in setOf(CartAutomationCapability.SUPPORTED, CartAutomationCapability.RIDE_SETUP_ONLY)
}

@Serializable
data class ProviderList(val providers: List<Provider>)
