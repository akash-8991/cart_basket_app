package com.smartcart.app

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartcart.app.notifications.Notifier
import com.smartcart.automation.teststore.TestStoreAdapter
import com.smartcart.model.CartTask
import com.smartcart.model.TaskStatus
import com.smartcart.model.UserActionReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 4.2 on a real Android: chat → compare → approve → the approved cart is really built in SmartCart Test
 * Store by the accessibility service → cart ready in SmartCart. The person never leaves the approved plan:
 * payment is never touched, and anything only they can do hands the phone back until they tap Resume.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceTaskJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val c get() = Device.app.container
    private val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val seen = CopyOnWriteArrayList<CartTask>()

    private fun s(id: Int, vararg args: Any) = Device.str(id, *args)

    @Before fun setUp() {
        Device.grantNotifications()
        Device.resetApp()
        Device.context.getSystemService(NotificationManager::class.java).cancelAll()
        Automation.turnOn()
        c.testStore!!.enabled.value = true
        watch.launch { c.tasks.activeTask.collect { t -> t?.let(seen::add) } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun tearDown() {
        watch.cancel()
        c.testStore?.enabled?.value = false
        Automation.turnOff()
        scenario.close()
    }

    /** Approves the plan the app shows for Blinkit, which in this build runs in the Test Store. */
    private fun approveInTheTestStore(storeScenario: String): String {
        Automation.scenario(storeScenario)
        compose.registerCompareAndPickBlinkit(scenario, shots = false)
        compose.waitForText(s(R.string.approve_start))
        // The plan says which app will really be opened, and lists exactly the compared items.
        compose.waitForText("Open Test Store")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Add Toned milk 1 L × 2"))
        Device.screenshot("ondevice-01-approve")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.approve_start)))
        compose.clickable(s(R.string.approve_start)).performClick()
        compose.waitUntil(20_000) { seen.isNotEmpty() }
        return seen.first().taskId
    }

    private fun awaitTask(id: String, timeoutMs: Long = 90_000, what: (CartTask) -> Boolean): CartTask = runBlocking {
        withTimeout(timeoutMs) { c.tasks.observe(id).first { it != null && what(it) }!! }
    }

    private fun testStoreShows(viewId: String): Boolean =
        Device.ui.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("${TestStoreAdapter.PACKAGE}:id/$viewId")?.isNotEmpty() == true

    private fun notification(): Notification? =
        Device.context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.notification }.firstOrNull { it.channelId == Notifier.CH_TASKS }

    private fun timeline(t: CartTask) = t.events.joinToString("\n") { it.description }

    @Test fun approvedCartIsBuiltInTheTestStoreAndShownAsReady() = screenshotOnFailure("ondevice-ready") {
        val id = approveInTheTestStore("normal")
        val done = awaitTask(id) { it.status == TaskStatus.CART_READY || it.status.isTerminal }
        assertEquals(timeline(done), TaskStatus.CART_READY, done.status)

        // In the Test Store: the cart is open with the approved items, and payment was never reached.
        Device.screenshot("ondevice-02-teststore-cart")
        assertTrue("the Test Store cart is on screen", testStoreShows("cart_title"))
        assertFalse("the payment screen was never opened", testStoreShows("pay_title"))
        val cart = done.cart!!
        assertTrue(cart.verified)
        assertEquals(listOf(2, 1, 1, 1), cart.lines.map { it.quantity })
        assertEquals(2 * 2800L + 4500 + 8400 + 4800, cart.totalEstimate!!.amountMinor)

        // The cart-ready notification reaches the person while the Test Store is in front.
        compose.waitUntil(10_000) { notification() != null }

        // Back in SmartCart (as tapping the notification would): the cart-ready screen with the verified cart.
        Automation.backToSmartCart()
        compose.waitForText(s(R.string.ready_open_cart, "Blinkit"), timeoutMs = 30_000)
        compose.waitForText(s(R.string.ready_line, "Toned milk 1 L", 2))
        Device.screenshot("ondevice-03-cart-ready")
        compose.onNode(hasText(s(R.string.ready_open_cart, "Blinkit")) and hasClickAction()).performScrollTo().performClick()
        compose.waitForText(s(R.string.ready_opening, "Blinkit"))
        compose.clickable(s(R.string.common_ok)).performClick()
        assertEquals(TaskStatus.HANDED_OFF, awaitTask(id, 10_000) { it.status == TaskStatus.HANDED_OFF }.status)
    }

    @Test fun aLoginHandsThePhoneBackAndResumeFinishesTheCart() = screenshotOnFailure("ondevice-resume") {
        val id = approveInTheTestStore("login")
        val halted = awaitTask(id) { it.status == TaskStatus.NEEDS_USER_ACTION || it.status.isTerminal }
        assertEquals(timeline(halted), TaskStatus.NEEDS_USER_ACTION, halted.status)
        assertEquals(UserActionReason.LOGIN_REQUIRED, halted.needsUserAction!!.reason)
        assertEquals("nothing but opening the app was done", 1, halted.actionsUsed)
        Device.screenshot("ondevice-04-teststore-login")
        compose.waitUntil(10_000) { notification() != null }

        // The person signs in to the store themselves (here: the store is reset to a signed-in session),
        // returns to SmartCart and taps Resume.
        Automation.scenario("normal")
        Automation.backToSmartCart()
        compose.waitForText(s(R.string.task_resume), timeoutMs = 30_000)
        Device.screenshot("ondevice-05-needs-you")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.task_resume)))
        compose.clickable(s(R.string.task_resume)).performClick()

        val done = awaitTask(id) { it.status == TaskStatus.CART_READY || it.status.isTerminal }
        assertEquals(timeline(done), TaskStatus.CART_READY, done.status)
        assertTrue(done.cart!!.verified)
        assertTrue(done.events.any { it.description == "Resumed by you" })
        Automation.backToSmartCart()
        compose.waitForText(s(R.string.ready_open_cart, "Blinkit"), timeoutMs = 30_000)
    }
}
