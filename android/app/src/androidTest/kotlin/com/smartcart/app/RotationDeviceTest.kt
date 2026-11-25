package com.smartcart.app

import android.app.Activity
import android.app.UiAutomation
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.smartcart.data.ApiResult
import com.smartcart.data.mock.MockConfig
import com.smartcart.data.mock.TaskScenario
import com.smartcart.model.Category
import com.smartcart.model.Objective
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteRequest
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Activity recreation on rotation: user input and the emergency Stop control must survive. */
@RunWith(AndroidJUnit4::class)
class RotationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        Device.instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        Device.instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        scenario?.close()
    }

    /**
     * Rotates the display the way a user turning the phone does (not via requestedOrientation, which
     * races activity recreation on API 36 emulators), then waits — with a timeout — for the recreated
     * activity to report the new orientation.
     */
    private fun rotate(landscape: Boolean) {
        Device.instrumentation.uiAutomation.setRotation(if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
        val want = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.waitUntil(15_000) { resumedActivity()?.resources?.configuration?.orientation == want }
    }

    private fun resumedActivity(): Activity? {
        var a: Activity? = null
        Device.instrumentation.runOnMainSync {
            a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return a
    }

    @Test
    fun typedUserIdSurvivesRotation() {
        Device.resetApp()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForText(Device.str(R.string.welcome_sign_in))
        compose.clickable(Device.str(R.string.welcome_sign_in)).performClick()
        compose.waitForText(Device.str(R.string.login_remember))
        compose.onNode(hasSetTextAction() and hasText(Device.str(R.string.auth_user_id))).performTextInput("priya.k")
        // Landscape on some versions shows a full-screen keyboard that hides the app; close it first.
        androidx.test.espresso.Espresso.closeSoftKeyboard()

        rotate(landscape = true)
        compose.waitFor(hasSetTextAction() and hasText("priya.k", substring = true))
        Device.screenshot("rotation-login-landscape")
        rotate(landscape = false)
        compose.waitFor(hasSetTextAction() and hasText("priya.k", substring = true))
    }

    @Test
    fun emergencyStopSurvivesRotationAndStopsTheTask() {
        Device.grantNotifications()
        Device.resetApp(MockConfig(latencyMs = 150, task = TaskScenario.CHECKPOINT))
        val c = Device.app.container
        val taskId = runBlocking {
            c.auth.login("priya.k", "correct horse battery", remember = true)
            val q = (c.comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value
            val t = (c.tasks.create(q, ProviderKey.BLINKIT) as ApiResult.Success).value
            c.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
            t.taskId
        }
        compose.waitUntil(20_000) { c.tasks.activeTask.value?.status == TaskStatus.CHECKPOINT }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val stop = Device.str(R.string.task_bar_stop_cd)
        compose.waitForDescription(stop)

        rotate(landscape = true)
        compose.waitForDescription(stop)
        Device.screenshot("rotation-stop-bar-landscape")
        compose.onNode(hasContentDescription(stop)).assertIsDisplayed().performClick()

        compose.waitUntil(10_000) { c.tasks.activeTask.value == null }
        assertEquals(TaskStatus.CANCELLED, runBlocking { c.tasks.observe(taskId).first() }?.status)
    }
}
