package com.smartcart.data.device

import com.smartcart.automation.Adapter
import com.smartcart.automation.Driver
import com.smartcart.automation.PackageVerifier
import com.smartcart.model.ProviderKey

/**
 * What the phone offers for running cart tasks itself (Phase 4.2): the accessibility driver while the person
 * has the service turned on, the package verifier, and which provider apps have an adapter.
 */
interface OnDeviceAutomation {
    /** The connected driver, or null while the accessibility service is off. */
    fun driver(): Driver?
    val verifier: PackageVerifier
    /** The adapter that automates [provider] on this phone, or null if none is bound. */
    fun adapterFor(provider: ProviderKey): Adapter?
    /** Brings SmartCart back to the front after a price check opened another app. */
    fun returnToSmartCart() {}
}

/** CR-005: where the phone's price readings go when comparisons are worked out on the server. */
interface DeviceReadingSink {
    suspend fun submitObservation(quoteId: String, observation: com.smartcart.model.PriceObservation): com.smartcart.data.ApiResult<com.smartcart.model.ComparisonQuote>
}
