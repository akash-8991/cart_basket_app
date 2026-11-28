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
}
