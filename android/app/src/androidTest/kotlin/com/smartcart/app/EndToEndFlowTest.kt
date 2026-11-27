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
            // Welcome → Create account
            compose.waitForText(s(R.string.welcome_create))
            Device.screenshot("e2e-01-welcome")
            compose.clickable(s(R.string.welcome_create)).performClick()

            // Register
            compose.waitForText(s(R.string.register_intro))
            compose.onNode(hasSetTextAction() and hasText(s(R.string.auth_user_id))).performTextInput("priya.k")
            compose.onNode(hasSetTextAction() and hasText(s(R.string.auth_email))).performTextInput("priya@example.com")
            compose.onNode(hasSetTextAction() and hasText(s(R.string.auth_password))).performTextInput("correct horse battery")
            compose.onNode(hasSetTextAction() and hasText(s(R.string.register_confirm_password))).performTextInput("correct horse battery")
            compose.onNode(hasText(s(R.string.register_terms))).performScrollTo().performClick()
            Device.screenshot("e2e-02-register")
            compose.clickable(s(R.string.register_submit)).performScrollTo().performClick()

            // Home: KPIs then categories; session stored in the real Keystore
            compose.waitForText(s(R.string.home_what_do_you_need))
            assertEquals("priya.k", Device.app.container.let { com.smartcart.app.security.KeystoreTokenStore(Device.context).read()?.userId })
            Device.screenshot("e2e-03-home")
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.category_grocery)))
            compose.clickable(s(R.string.category_grocery)).performClick()

            // Chat → clarification → cart draft
            compose.waitFor(hasContentDescription(s(R.string.chat_send)))
            compose.onNode(hasSetTextAction()).performTextInput("Milk, brown bread, eggs and bananas")
            // Send stays disabled until the chat session exists; tapping it earlier would do nothing.
            compose.waitFor(hasContentDescription(s(R.string.chat_send)) and isEnabled())
            compose.onNode(hasContentDescription(s(R.string.chat_send))).performClick()
            compose.waitForText("Toned milk 1 L")
            compose.clickable("Toned milk 1 L").performClick()
            compose.waitForText(s(R.string.chat_compare_cart))
            // On a landscape tablet the on-screen keyboard leaves less room than the cart card needs; close it,
            // as a user would, so scrolling to the button really brings it on screen.
            scenario.onActivity { hideKeyboard(it) }
            compose.waitForIdle()
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.chat_compare_cart)))
            Device.screenshot("e2e-04-chat")
            compose.clickable(s(R.string.chat_compare_cart)).performClick()
            // CR-004: no saved address yet, so Compare asks for one first, then carries on.
            compose.addHomeAddress()

            // Comparison: complete coverage → "Cheapest"; user picked BigBasket so switching asks first
            compose.waitForText(s(R.string.cmp_use, "Blinkit"), timeoutMs = 30_000)
            compose.waitForText(s(R.string.rec_label_cheapest))
            Device.screenshot("e2e-05-comparison")
            compose.clickable(s(R.string.cmp_use, "Blinkit")).performClick()
            compose.waitForText(s(R.string.cmp_switch_title, "Blinkit"))
            Device.screenshot("e2e-06-switch-dialog")
            compose.onNode(hasText(s(R.string.cmp_use, "Blinkit")) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()

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
