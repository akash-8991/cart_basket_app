package com.smartcart.app

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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
    fun grantNotifications() {
        if (api >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Puts the real app container into a known state: signed out, no active task, given mock scenario. */
    fun resetApp(cfg: MockConfig = MockConfig(latencyMs = 150)) = runBlocking {
        val c = app.container
        c.tasks.stopAll(StopReason.USER_CANCEL)
        c.account.setAutomationPaused(false)
        c.auth.logout()
        c.mock.update { cfg }
    }

    /** Saves a full-screen capture; AGP copies it into build/outputs/connected_android_test_additional_output. */
    fun screenshot(name: String, waitForIdle: Boolean = true) {
        if (waitForIdle) instrumentation.waitForIdleSync()
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
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
