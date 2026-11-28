package com.smartcart.app

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.smartcart.data.mock.MockConfig
import com.smartcart.model.StopReason
import kotlinx.coroutines.runBlocking

/** Helpers for tests that run on a real device or emulator. */
object Device {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val app: SmartCartApp get() = ApplicationProvider.getApplicationContext()
    val api: Int get() = Build.VERSION.SDK_INT
    val fontScale: Float get() = context.resources.configuration.fontScale

    fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /** Android 13+ asks for notification permission at runtime; grant it up front so no system dialog appears. */
    /**
     * The instrumentation's UiAutomation. Always taken with this flag: the default one switches off every other
     * accessibility service, including SmartCart's own automation service (Phase 4).
     */
    val ui: android.app.UiAutomation get() = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    fun grantNotifications() {
        if (api >= 33) ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Puts the real app container into a known state: signed out, no active task, given mock scenario. */
    fun resetApp(cfg: MockConfig = MockConfig(latencyMs = 150)) = runBlocking {
        val c = app.container
        c.tasks.stopAll(StopReason.USER_CANCEL)
        c.testStore?.enabled?.value = false // grocery tasks simulated unless a test turns the Test Store on
        c.account.setAutomationPaused(false)
        c.auth.logout()
        // Saved addresses (CR-004) from earlier runs: start every test with none.
        context.getSharedPreferences("addresses", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        c.addresses.refresh()
        c.mock.update { cfg }
    }

    /** Saves a full-screen capture; AGP copies it into build/outputs/connected_android_test_additional_output. */
    fun screenshot(name: String, waitForIdle: Boolean = true) {
        if (waitForIdle) instrumentation.waitForIdleSync()
        val bmp: Bitmap = ui.takeScreenshot() ?: return
        val scale = if (fontScale > 1.01f) "-font${fontScale}" else ""
        TestStorage().openOutputFile("screenshots/api$api$scale-$name.png").use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Runs [block]; if it throws, saves a FAILED-<name> screenshot of the screen at that moment, then rethrows. */
inline fun screenshotOnFailure(name: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        runCatching { Device.screenshot("FAILED-$name", waitForIdle = false) }
        throw t
    }
}

/** Fills in and saves the add-address form (CR-004) that Compare opens when no address is saved. */
fun ComposeTestRule.addHomeAddress() {
    waitForText(Device.str(R.string.addr_new_title))
    fun field(label: Int, value: String) = onNode(androidx.compose.ui.test.hasSetTextAction() and hasText(Device.str(label))).performScrollTo().performTextInput(value)
    onNode(hasText(Device.str(R.string.addr_label_home)) and hasClickAction()).performScrollTo().performClick()
    field(R.string.addr_flat, "Flat 4B, Lake View Apartments")
    field(R.string.addr_street, "12 MG Road")
    field(R.string.addr_city, "Bengaluru")
    field(R.string.addr_pin, "560001")
    Device.screenshot("address-form")
    clickable(Device.str(R.string.addr_save)).performScrollTo().performClick()
}

/** Hides the soft keyboard without Espresso, which needs window focus and can time out. */
fun hideKeyboard(activity: android.app.Activity) {
    val imm = activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
    imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
}

fun ComposeTestRule.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 20_000) =
    waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 20_000) =
    waitFor(hasText(text, substring = substring), timeoutMs)

fun ComposeTestRule.clickable(text: String): SemanticsNodeInteraction = onNode(hasText(text) and hasClickAction())

fun ComposeTestRule.waitForDescription(text: String, timeoutMs: Long = 20_000) = waitFor(hasContentDescription(text), timeoutMs)

/**
 * The shared start of the app journeys: register → home → grocery chat → clarification → full-cart comparison
 * → pick Blinkit (cheapest) and confirm the switch. Ends on the way to the approval screen.
 */
fun ComposeTestRule.registerCompareAndPickBlinkit(scenario: ActivityScenario<MainActivity>, shots: Boolean) {
    // Welcome → Create account
    waitForText(Device.str(R.string.welcome_create))
    if (shots) Device.screenshot("e2e-01-welcome")
    clickable(Device.str(R.string.welcome_create)).performClick()

    // Register
    waitForText(Device.str(R.string.register_intro))
    onNode(hasSetTextAction() and hasText(Device.str(R.string.auth_user_id))).performTextInput("priya.k")
    onNode(hasSetTextAction() and hasText(Device.str(R.string.auth_email))).performTextInput("priya@example.com")
    onNode(hasSetTextAction() and hasText(Device.str(R.string.auth_password))).performTextInput("correct horse battery")
    onNode(hasSetTextAction() and hasText(Device.str(R.string.register_confirm_password))).performTextInput("correct horse battery")
    onNode(hasText(Device.str(R.string.register_terms))).performScrollTo().performClick()
    if (shots) Device.screenshot("e2e-02-register")
    clickable(Device.str(R.string.register_submit)).performScrollTo().performClick()

    // Home: KPIs then categories; session stored in the real Keystore
    waitForText(Device.str(R.string.home_what_do_you_need))
    assertEquals("priya.k", Device.app.container.let { com.smartcart.app.security.KeystoreTokenStore(Device.context).read()?.userId })
    if (shots) Device.screenshot("e2e-03-home")
    onNode(hasScrollToNodeAction()).performScrollToNode(hasText(Device.str(R.string.category_grocery)))
    clickable(Device.str(R.string.category_grocery)).performClick()

    // Chat → clarification → cart draft
    waitFor(hasContentDescription(Device.str(R.string.chat_send)))
    onNode(hasSetTextAction()).performTextInput("Milk, brown bread, eggs and bananas")
    // Send stays disabled until the chat session exists; tapping it earlier would do nothing.
    waitFor(hasContentDescription(Device.str(R.string.chat_send)) and isEnabled())
    onNode(hasContentDescription(Device.str(R.string.chat_send))).performClick()
    waitForText("Toned milk 1 L")
    clickable("Toned milk 1 L").performClick()
    waitForText(Device.str(R.string.chat_compare_cart))
    // On a landscape tablet the on-screen keyboard leaves less room than the cart card needs; close it,
    // as a user would, so scrolling to the button really brings it on screen.
    scenario.onActivity { hideKeyboard(it) }
    waitForIdle()
    onNode(hasScrollToNodeAction()).performScrollToNode(hasText(Device.str(R.string.chat_compare_cart)))
    if (shots) Device.screenshot("e2e-04-chat")
    clickable(Device.str(R.string.chat_compare_cart)).performClick()
    // CR-004: no saved address yet, so Compare asks for one first, then carries on.
    addHomeAddress()

    // Comparison: complete coverage → "Cheapest"; user picked BigBasket so switching asks first
    waitForText(Device.str(R.string.cmp_use, "Blinkit"), timeoutMs = 30_000)
    waitForText(Device.str(R.string.rec_label_cheapest))
    if (shots) Device.screenshot("e2e-05-comparison")
    clickable(Device.str(R.string.cmp_use, "Blinkit")).performClick()
    waitForText(Device.str(R.string.cmp_switch_title, "Blinkit"))
    if (shots) Device.screenshot("e2e-06-switch-dialog")
    onNode(hasText(Device.str(R.string.cmp_use, "Blinkit")) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
}
