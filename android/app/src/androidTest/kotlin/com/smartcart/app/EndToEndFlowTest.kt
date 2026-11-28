package com.smartcart.app

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartcart.app.notifications.Notifier
import com.smartcart.model.TaskStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full journey through the real app (MainActivity, real navigation, Keystore session storage,
 * real notifications), backed by contract fixtures: register → home → grocery chat →
 * clarification → full-cart comparison → switch confirmation → approval → automation → cart ready → handoff.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        Device.grantNotifications()
        Device.resetApp()
        Device.context.getSystemService(NotificationManager::class.java).cancelAll()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun tearDown() = scenario.close()

    private fun s(id: Int, vararg args: Any) = Device.str(id, *args)

    @Test
    fun registerCompareApproveAndHandOff() = screenshotOnFailure("registerCompareApproveAndHandOff") {
            compose.registerCompareAndPickBlinkit(scenario, shots = true)

            // Approval: every step listed with its risk level
            compose.waitForText(s(R.string.approve_start))
            Device.screenshot("e2e-07-approve")
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.approve_start)))
            compose.clickable(s(R.string.approve_start)).performClick()

            // Automation runs to a verified cart, then the handoff screen
            compose.waitForText(s(R.string.ready_open_cart, "Blinkit"), timeoutMs = 30_000)
            Device.screenshot("e2e-08-cart-ready")
            val task = Device.app.container.tasks.let { repo ->
                kotlinx.coroutines.runBlocking { repo.activeTask.value }
            }
            assertEquals("no task is active once the cart is ready", null, task)

            // Cart-ready notification: posted, and generic on the lock screen
            compose.waitUntil(10_000) { cartReadyNotification() != null }
            val n = cartReadyNotification()!!
            assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
            assertEquals(s(R.string.notif_cart_ready_generic_title), n.publicVersion.extras.getString(Notification.EXTRA_TITLE))

            compose.onNode(hasText(s(R.string.ready_open_cart, "Blinkit")) and hasClickAction()).performScrollTo().performClick()
            compose.waitForText(s(R.string.ready_opening, "Blinkit"))
            Device.screenshot("e2e-09-handoff")
            compose.clickable(s(R.string.common_ok)).performClick()
            compose.waitForText(s(R.string.home_what_do_you_need))

            val statuses = kotlinx.coroutines.runBlocking { Device.app.container.tasks.activeTask.value }
            assertTrue(statuses == null || statuses.status != TaskStatus.RUNNING)
    }

    private fun cartReadyNotification(): Notification? =
        Device.context.getSystemService(NotificationManager::class.java).activeNotifications
            .map { it.notification }
            .firstOrNull { it.channelId == Notifier.CH_TASKS }
}
