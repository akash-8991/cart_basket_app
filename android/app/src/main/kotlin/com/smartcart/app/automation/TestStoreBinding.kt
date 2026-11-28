package com.smartcart.app.automation

import android.content.Context
import com.smartcart.automation.Adapter
import com.smartcart.automation.Driver
import com.smartcart.automation.PackageVerifier
import com.smartcart.automation.teststore.TestStoreAdapter
import com.smartcart.data.device.OnDeviceAutomation
import com.smartcart.model.Category
import com.smartcart.model.ProviderKey
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Debug builds only (Phase 4.2, D-028): while [enabled] is on (Settings → Developer), grocery providers' cart
 * tasks really run, in SmartCart Test Store, through the accessibility service. Real partner adapters come
 * only from the device spike (slice 4.8); no partner app is touched.
 *
 * The Test Store is built with the same debug key as SmartCart, so that certificate is the one trusted.
 */
class TestStoreBinding(private val context: Context) : OnDeviceAutomation {
    val enabled = MutableStateFlow(false)

    override fun driver(): Driver? = AutomationHost.driver.value
    override val verifier: PackageVerifier = AndroidPackageVerifier(context)

    override fun adapterFor(provider: ProviderKey): Adapter? =
        if (enabled.value && provider.category == Category.GROCERY) TestStoreAdapter(verifier.signingDigests(context.packageName)) else null
}
