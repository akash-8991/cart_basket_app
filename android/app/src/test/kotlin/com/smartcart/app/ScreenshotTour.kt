package com.smartcart.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.graphics.Canvas
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartcart.app.feature.auth.LoginScreen
import com.smartcart.app.feature.auth.WelcomeScreen
import com.smartcart.app.feature.comparison.ComparisonScreen
import com.smartcart.app.feature.home.HomeScreen
import com.smartcart.app.feature.task.ApproveScreen
import com.smartcart.app.feature.task.AutomationConsentScreen
import com.smartcart.app.feature.task.CartReadyScreen
import com.smartcart.app.feature.task.TaskScreen
import com.smartcart.app.security.InMemoryTokenStore
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.data.ApiResult
import com.smartcart.data.FileFixtureSource
import com.smartcart.data.mock.ComparisonScenario
import com.smartcart.data.mock.MockConfig
import com.smartcart.data.mock.TaskScenario
import com.smartcart.designsystem.SmartCartTheme
import com.smartcart.model.Category
import com.smartcart.model.Objective
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteRequest
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders key screens to PNG for design review and the freeze evidence pack
 * (android/app/build/screenshots). Not an assertion test.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTour {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun container(cfg: MockConfig) = AppContainer(null, FileFixtureSource(File(System.getProperty("fixturesDir"))), cfg, InMemoryTokenStore())

    private fun wait(text: String) = rule.waitUntil(8_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun snap(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bmp)) }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun quote(c: AppContainer, cat: Category = Category.GROCERY) = runBlocking {
        (c.comparisons.create(QuoteRequest(cat, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value
    }

    @Test
    fun tour() {
        var screen by mutableStateOf<@Composable () -> Unit>({})
        var dark by mutableStateOf(false)
        var c by mutableStateOf(container(MockConfig(latencyMs = 0)))
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides c) {
                SmartCartTheme(darkTheme = dark) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { screen() } }
            }
        }

        screen = { WelcomeScreen({}, {}, {}) }; wait("Create account"); snap("01-welcome")
        screen = { LoginScreen({}, {}, {}, {}) }; wait("Keep me signed in"); snap("02-login")
        screen = { HomeScreen({}, {}, {}, {}, {}, {}, {}) }; wait("Total orders"); snap("03-home")
        dark = true; snap("03b-home-dark"); dark = false

        val q1 = quote(c)
        screen = { ComparisonScreen(q1, {}, { _, _ -> }, {}, { _, _ -> }, {}) }; wait("Use Blinkit"); snap("04-comparison-complete")

        c = container(MockConfig(latencyMs = 0, comparison = ComparisonScenario.PARTIAL_COVERAGE))
        val q2 = quote(c)
        screen = { ComparisonScreen(q2, {}, { _, _ -> }, {}, { _, _ -> }, {}) }; wait("Partial comparison"); snap("05-comparison-partial")

        c = container(MockConfig(latencyMs = 0, comparison = ComparisonScenario.INCOMPARABLE))
        val q3 = quote(c)
        screen = { ComparisonScreen(q3, {}, { _, _ -> }, {}, { _, _ -> }, {}) }; wait("like-for-like"); snap("06-comparison-incomparable")

        c = container(MockConfig(latencyMs = 0))
        val q4 = quote(c)
        screen = { AutomationConsentScreen(ProviderKey.BIGBASKET, q4, {}, {}, {}) }; wait("It will never"); snap("07-consent")

        c = container(MockConfig(latencyMs = 0, task = TaskScenario.CHECKPOINT))
        val q5 = quote(c)
        val task = runBlocking { (c.tasks.create(q5, ProviderKey.BLINKIT) as ApiResult.Success).value }
        screen = { ApproveScreen(task.taskId, {}, {}) }; wait("Approve and start"); snap("08-approve")
        runBlocking { c.tasks.approve(task.taskId, task.plannedActions.map { it.actionId }) }
        rule.waitUntil(8_000) { c.tasks.activeTask.value?.status == TaskStatus.CHECKPOINT }
        screen = { TaskScreen(task.taskId, {}, {}, { _, _, _ -> }, {}) }; wait("Yes, continue"); snap("09-task-checkpoint")

        c = container(MockConfig(latencyMs = 0))
        val q6 = quote(c)
        val t2 = runBlocking { (c.tasks.create(q6, ProviderKey.BLINKIT) as ApiResult.Success).value }
        runBlocking { c.tasks.approve(t2.taskId, t2.plannedActions.map { it.actionId }) }
        rule.waitUntil(8_000) { runBlocking { c.tasks.observe(t2.taskId).first() }?.status == TaskStatus.CART_READY }
        screen = { CartReadyScreen(t2.taskId, {}, { _, _ -> }, {}) }; wait("Open cart in Blinkit"); snap("10-cart-ready")
    }
}
