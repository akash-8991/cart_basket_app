package com.smartcart.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartcart.app.feature.auth.ForgotUserIdScreen
import com.smartcart.app.feature.auth.LoginScreen
import com.smartcart.app.feature.comparison.ComparisonScreen
import com.smartcart.app.feature.home.HomeScreen
import com.smartcart.app.security.InMemoryTokenStore
import com.smartcart.app.ui.common.ActiveTaskBar
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.data.ApiResult
import com.smartcart.data.FileFixtureSource
import com.smartcart.data.FlagKeys
import com.smartcart.data.mock.ComparisonScenario
import com.smartcart.data.mock.LoginScenario
import com.smartcart.data.mock.MockConfig
import com.smartcart.data.mock.TaskScenario
import com.smartcart.designsystem.SmartCartTheme
import com.smartcart.model.CartTask
import com.smartcart.model.Category
import com.smartcart.model.Objective
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteRequest
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** PRD v1.3 §16 acceptance criteria that can be verified against mocks. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class UiAcceptanceTest {
    @get:Rule val rule = createComposeRule()

    private fun container(cfg: MockConfig = MockConfig(latencyMs = 0)) =
        AppContainer(null, FileFixtureSource(File(System.getProperty("fixturesDir"))), cfg, InMemoryTokenStore())

    private fun show(c: AppContainer, content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalAppContainer provides c) { SmartCartTheme(darkTheme = false) { content() } }
    }

    private fun waitForText(text: String, substring: Boolean = false) = rule.waitUntil(5_000) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun SemanticsNodeInteraction.top() = fetchSemanticsNode().boundsInRoot.top

    @Composable
    private fun Home() = HomeScreen({}, {}, {}, {}, {}, {}, {})

    @Test
    fun homeShowsKpisFirstThenCategoryCards() {
        val c = container()
        show(c) { Home() }
        waitForText("Total orders")
        rule.onNodeWithText("Total orders", substring = true, useUnmergedTree = true).assertIsDisplayed()
        val kpiTop = rule.onNodeWithText("Total orders", useUnmergedTree = true).top()
        listOf("Travel", "Food ordering", "Grocery").forEach { title ->
            val cardTop = rule.onNodeWithText(title, useUnmergedTree = true).top()
            assertTrue("$title card must be below KPIs", cardTop > kpiTop)
        }
        val travel = rule.onNodeWithText("Travel", useUnmergedTree = true).top()
        val food = rule.onNodeWithText("Food ordering", useUnmergedTree = true).top()
        val grocery = rule.onNodeWithText("Grocery", useUnmergedTree = true).top()
        assertTrue("order is Travel, Food, Grocery", travel < food && food < grocery)
    }

    @Test
    fun shoppingCategoryIsBehindFeatureFlag() {
        val c = container()
        show(c) { Home() }
        waitForText("Grocery")
        assertEquals(0, rule.onAllNodesWithText("Shopping", useUnmergedTree = true).fetchSemanticsNodes().size)
        c.flags.set(FlagKeys.HOME_SHOPPING, true)
        waitForText("Shopping")
    }

    @Test
    fun interactiveElementsOnHomeMeetTouchTargetSize() {
        val c = container()
        show(c) { Home() }
        waitForText("Total orders")
        val min = with(rule.density) { 48.dp.toPx() } - 0.5f
        val small = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.touchBoundsInRoot.height < min }
        assertTrue("touch targets under 48dp: " + small.map { it.config }, small.isEmpty())
    }

    @Test
    fun partialComparisonNeverClaimsCheapest() {
        val c = container(MockConfig(latencyMs = 0, comparison = ComparisonScenario.PARTIAL_COVERAGE))
        val id = runBlocking { (c.comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value }
        show(c) { ComparisonScreen(id, {}, { _, _ -> }, {}, { _, _ -> }, {}) }
        waitForText("Partial comparison")
        rule.onNodeWithText("Lowest observed estimate").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Cheapest").fetchSemanticsNodes().size)
        rule.onNodeWithText("Final price may change", substring = true).assertExists()
    }

    @Test
    fun switchingFromSelectedAppAsksForConfirmation() {
        val c = container()
        val id = runBlocking { (c.comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value }
        var approvedTask: String? = null
        show(c) { ComparisonScreen(id, {}, { _, _ -> }, { approvedTask = it }, { _, _ -> }, {}) }
        waitForText("Use Blinkit")
        rule.onNodeWithText("Cheapest").assertIsDisplayed()
        rule.onNodeWithText("Use Blinkit").performClick()
        waitForText("Switch to Blinkit?")
        assertEquals("no task before the switch is confirmed", null, approvedTask)
        rule.onNodeWithText("Use Blinkit", useUnmergedTree = true).let { } // dialog button shares the label
        rule.onAllNodesWithText("Use Blinkit").fetchSemanticsNodes().let { assertTrue(it.size >= 2) }
        rule.onNodeWithText("Keep BigBasket").performClick()
        rule.waitForIdle()
        assertEquals(null, approvedTask)
    }

    @Test
    fun emergencyStopIsVisibleAndStopsTheActiveTask() {
        val c = container(MockConfig(latencyMs = 0, task = TaskScenario.CHECKPOINT))
        val task: CartTask = runBlocking {
            val q = (c.comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value
            val t = (c.tasks.create(q, ProviderKey.BLINKIT) as ApiResult.Success).value
            c.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
            t
        }
        rule.waitUntil(5_000) { c.tasks.activeTask.value?.status == TaskStatus.CHECKPOINT }
        show(c) {
            val active = androidx.compose.runtime.produceState<CartTask?>(c.tasks.activeTask.value) { c.tasks.activeTask.collect { value = it } }
            ActiveTaskBar(active.value, onOpen = {}, onStop = { id -> runBlocking { c.tasks.stop(id, com.smartcart.model.StopReason.EMERGENCY_STOP) } })
        }
        rule.onNodeWithContentDescription("Emergency stop automation").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { c.tasks.activeTask.value == null }
        val final = runBlocking { c.tasks.observe(task.taskId).first() }
        assertEquals(TaskStatus.CANCELLED, final?.status)
    }

    @Test
    fun invalidLoginShowsGenericMessage() {
        val c = container(MockConfig(latencyMs = 0, login = LoginScenario.INVALID_CREDENTIALS))
        show(c) { LoginScreen({}, {}, {}, {}) }
        rule.onNode(hasText("User ID") and androidx.compose.ui.test.hasSetTextAction()).performTextInput("priya.k")
        rule.onNode(hasText("Password") and androidx.compose.ui.test.hasSetTextAction()).performTextInput("wrong-password-123")
        rule.onAllNodesWithText("Sign in").fetchSemanticsNodes()
        rule.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitForText("User ID or password is incorrect.")
    }

    @Test
    fun forgotUserIdNeverRevealsTheUserId() {
        val c = container()
        show(c) { ForgotUserIdScreen({}, {}) }
        rule.onNode(hasText("Registered email") and androidx.compose.ui.test.hasSetTextAction()).performTextInput("someone@example.com")
        rule.onNodeWithText("Email my user ID").performClick()
        waitForText("Check your email")
        assertEquals(0, rule.onAllNodesWithText("priya.k", substring = true).fetchSemanticsNodes().size)
    }
}
