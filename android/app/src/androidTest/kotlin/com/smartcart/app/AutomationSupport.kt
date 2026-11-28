package com.smartcart.app

import android.os.ParcelFileDescriptor
import com.smartcart.app.automation.AutomationHost
import com.smartcart.app.automation.SmartCartAutomationService
import com.smartcart.automation.teststore.TestStoreAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Turning SmartCart's automation service on and off, and setting up SmartCart Test Store, for device tests. */
object Automation {
    fun sh(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(Device.ui.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    /**
     * Off first, then on: re-writing an unchanged value doesn't make the system rebind a service it dropped
     * (for example after the app was reinstalled), and the test would wait for a connection that never comes.
     */
    fun turnOn() = runBlocking {
        sh("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { while (AutomationHost.driver.value != null) delay(100) }
        sh("settings put secure enabled_accessibility_services ${Device.context.packageName}/${SmartCartAutomationService::class.java.name}")
        sh("settings put secure accessibility_enabled 1")
        withTimeout(15_000) { while (AutomationHost.driver.value == null) delay(100) }
    }

    fun turnOff() {
        sh("settings delete secure enabled_accessibility_services")
        sh("am force-stop ${TestStoreAdapter.PACKAGE}")
    }

    /** Restarts the Test Store with [name] (normal, otp, login, captcha, dialog, payment_after_add, …). */
    fun scenario(name: String) {
        sh("am force-stop ${TestStoreAdapter.PACKAGE}")
        // -f 32 = FLAG_INCLUDE_STOPPED_PACKAGES, since the store was just force-stopped. `am broadcast` returns
        // only after the receiver has saved the scenario.
        val out = sh("am broadcast -f 32 -n ${TestStoreAdapter.PACKAGE}/.ConfigureReceiver --es scenario $name")
        check("Broadcast completed" in out) { "configuring the Test Store failed: $out" }
    }

    /** Brings SmartCart's existing activity back to the front, as tapping its notification or recents would. */
    fun backToSmartCart() {
        sh("am start -f 0x00020000 -n ${Device.context.packageName}/${MainActivity::class.java.name}") // REORDER_TO_FRONT
    }
}
