package com.smartcart.app

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import com.smartcart.app.automation.AndroidPackageVerifier
import com.smartcart.app.automation.AutomationHost
import com.smartcart.app.automation.SmartCartAutomationService
import com.smartcart.automation.ExecutablePlan
import com.smartcart.automation.ItemRequest
import com.smartcart.automation.RunState
import com.smartcart.automation.RunnerTiming
import com.smartcart.automation.TaskRunner
import com.smartcart.automation.teststore.TestStoreAdapter
import com.smartcart.model.FailureCode
import com.smartcart.model.TaskLimits
import com.smartcart.model.TaskStatus
import com.smartcart.model.UserActionReason
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4.1 on a real Android: the AccessibilityService drives SmartCart Test Store (D-027). A 2-item cart is
 * built and verified, and each hazard the store can produce halts the task in the right state without the
 * hazard being touched.
 */
@RunWith(AndroidJUnit4::class)
class AutomationRuntimeDeviceTest {
    private val ui: UiAutomation = Device.ui
    private val ctx = Device.context
    private val verifier = AndroidPackageVerifier(ctx)
    private val items = listOf(ItemRequest("Toned milk 1 L", 2), ItemRequest("Brown bread 400 g", 1))
    private val plan = ExecutablePlan.forCart("Test Store", items)
    private val limits = TaskLimits(maxActions = 25, maxRuntimeSeconds = 180, inactivityTimeoutSeconds = 30)

    @Before fun turnOnTheService() = Automation.turnOn()

    @After fun turnItOff() = Automation.turnOff()

    private fun scenario(name: String) = Automation.scenario(name)

    private fun runner(): TaskRunner = TaskRunner(
        driver = assertNotNull("service connected", AutomationHost.driver.value).let { AutomationHost.driver.value!! },
        verifier = verifier,
        // Built with the same debug key as this app, so that is the certificate to trust.
        adapter = TestStoreAdapter(verifier.signingDigests(ctx.packageName)),
        plan = plan,
        limits = limits,
        timing = RunnerTiming(stepWaitMillis = 6_000),
    )

    private fun run(name: String): RunState {
        scenario(name)
        val end = runBlocking { runner().run(plan.actions.map { it.action.actionId }.toSet()) }
        screenshot("automation-$name")
        return end
    }

    private fun screenshot(name: String) {
        Device.instrumentation.waitForIdleSync()
        val bmp: Bitmap = ui.takeScreenshot() ?: return
        TestStorage().openOutputFile("screenshots/api${Device.api}-$name.png").use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun buildsAndVerifiesARealCart() {
        val r = run("normal")
        assertEquals(r.events.joinToString("\n") { it.description }, TaskStatus.CART_READY, r.status)
        val cart = r.cart!!
        assertTrue(cart.verified)
        assertEquals(listOf(2, 1), cart.lines.map { it.quantity })
        assertEquals(2 * 2800L + 4500L, cart.totalEstimate!!.amountMinor)
        assertTrue(r.actionsUsed <= limits.maxActions)
    }

    @Test fun theStoreMustBeGenuinelySigned() {
        scenario("normal")
        val r = runBlocking {
            TaskRunner(AutomationHost.driver.value!!, verifier, TestStoreAdapter(setOf("0".repeat(64))), plan, limits)
                .run(plan.actions.map { it.action.actionId }.toSet())
        }
        assertEquals(FailureCode.PACKAGE_MISMATCH, r.failure!!.code)
    }

    @Test fun otpLoginAndCaptchaHandThePhoneBack() {
        mapOf("otp" to UserActionReason.OTP_REQUIRED, "login" to UserActionReason.LOGIN_REQUIRED, "captcha" to UserActionReason.CAPTCHA).forEach { (s, reason) ->
            val r = run(s)
            assertEquals(s, TaskStatus.NEEDS_USER_ACTION, r.status)
            assertEquals(s, reason, r.needsUserAction!!.reason)
            assertEquals("$s: only the launch was spent", 1, r.actionsUsed)
        }
    }

    @Test fun aPaymentScreenIsNeverTouched() {
        val r = run("payment_after_add")
        assertEquals(TaskStatus.BLOCKED_POLICY, r.status)
        // launch, type, search, add — then the payment screen appeared and nothing more was done.
        assertEquals(4, r.actionsUsed)
    }

    @Test fun anUnexpectedDialogNeedsThePerson() {
        val r = run("dialog")
        assertEquals(TaskStatus.NEEDS_USER_ACTION, r.status)
        assertEquals(UserActionReason.UNEXPECTED_DIALOG, r.needsUserAction!!.reason)
    }

    @Test fun aRedesignedScreenHalts() {
        assertEquals(FailureCode.UNKNOWN_SCREEN, run("redesign").failure?.code)
    }

    @Test fun anotherAppInFrontHalts() {
        assertEquals(FailureCode.PACKAGE_MISMATCH, run("leave_app").failure?.code)
    }

    @Test fun outOfStockAndCartDrift() {
        assertEquals(UserActionReason.ITEM_UNAVAILABLE, run("out_of_stock").needsUserAction?.reason)
        val drift = run("cart_drift")
        assertEquals(FailureCode.CART_VERIFICATION_FAILED, drift.failure?.code)
        assertFalse(drift.cart!!.verified)
    }

    @Test fun theEmergencyStopEndsTheTask() = runBlocking {
        scenario("normal")
        val r = runner()
        val job = async(kotlinx.coroutines.Dispatchers.Default) { r.run(plan.actions.map { it.action.actionId }.toSet()) }
        withTimeout(20_000) { while (r.state.value.actionsUsed < 3) delay(20) }
        r.stop()
        val end = job.await()
        assertEquals(TaskStatus.CANCELLED, end.status)
        assertTrue(end.actionsUsed <= 4)
    }
}
