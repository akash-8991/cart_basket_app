package com.smartcart.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartcart.app.feature.task.CartReadyScreen
import com.smartcart.app.security.InMemoryTokenStore
import com.smartcart.app.ui.AppNavHost
import com.smartcart.designsystem.SmartCartTheme
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.data.ApiResult
import com.smartcart.data.mock.MockConfig
import com.smartcart.model.Category
import com.smartcart.model.Objective
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteRequest
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** CR-006 in the app: a cart's link is used once, a used or expired one can be replaced, and links open their cart. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class Cr006UiTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)
    private val wait = 15_000L

    /** A signed-in container with a Blinkit cart that is ready (simulated, no backend). */
    private fun readyCart(): Pair<AppContainer, String> {
        val c = AppContainer(null, TestFixtures.source(), MockConfig(latencyMs = 0), InMemoryTokenStore())
        val taskId = runBlocking {
            c.auth.login("priya.k", "correct horse battery", false)
            val q = (c.comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value
            val t = (c.tasks.create(q, ProviderKey.BLINKIT) as ApiResult.Success).value
            c.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
            t.taskId
        }
        rule.waitUntil(wait) { status(c, taskId) == TaskStatus.CART_READY }
        return c to taskId
    }

    private fun show(c: AppContainer, content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalAppContainer provides c) { SmartCartTheme(darkTheme = false) { content() } }
    }

    private fun status(c: AppContainer, id: String) = runBlocking { c.tasks.observe(id).first()?.status }
    private fun waitForText(t: String) = rule.waitUntil(wait) { rule.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun openCart() = rule.onNode(hasText(s(R.string.ready_open_cart, "Blinkit")) and hasClickAction()).performScrollTo().performClick()

    @Test fun openingTheCartUsesItsLinkOnce() {
        val (c, id) = readyCart()
        show(c) { CartReadyScreen(id, onBack = {}, onManual = { _, _ -> }, onHome = {}) }
        rule.waitUntil(wait) { rule.onAllNodes(hasText(s(R.string.ready_open_cart, "Blinkit")) and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("making the link isn't handing off", TaskStatus.CART_READY, status(c, id))
        openCart()
        waitForText(s(R.string.ready_opening, "Blinkit"))
        waitForText(s(R.string.ready_link_note))
        assertEquals(TaskStatus.HANDED_OFF, status(c, id))
    }

    @Test fun aUsedLinkCanBeReplacedWithANewOne() {
        val (c, id) = readyCart()
        show(c) { CartReadyScreen(id, onBack = {}, onManual = { _, _ -> }, onHome = {}) }
        rule.waitUntil(wait) { rule.onAllNodes(hasText(s(R.string.ready_open_cart, "Blinkit")) and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        // The screen's link is used elsewhere first (say, from the notification on another tap): a newer one replaces it.
        runBlocking { c.tasks.createHandoff(id) }
        openCart()
        waitForText(s(R.string.ready_link_expired_title))
        rule.onNode(hasText(s(R.string.ready_link_new)) and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(wait) { rule.onAllNodes(hasText(s(R.string.ready_open_cart, "Blinkit")) and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        openCart()
        waitForText(s(R.string.ready_opening, "Blinkit"))
    }

    @Test fun aHandoffLinkOpensItsCartAndAnUsedOneFallsBack() {
        val (c, id) = readyCart()
        val token = runBlocking { (c.tasks.createHandoff(id) as ApiResult.Success).value.handoffUrl.substringAfterLast('/') }
        val link = MutableStateFlow<Uri?>(Uri.parse("smartcart://handoff/$token"))
        var fallback: String? = null
        show(c) { AppNavHost(pendingLink = link, consumeLink = { link.value = null }, fallbackTask = { fallback }) }
        waitForText(s(R.string.ready_title))
        assertEquals(TaskStatus.HANDED_OFF, status(c, id))

        // The same link again (it was used): with the notification's task to fall back on, that cart opens.
        fallback = id
        rule.runOnIdle { link.value = Uri.parse("smartcart://handoff/$token") }
        rule.waitUntil(wait) { link.value == null }
        waitForText(s(R.string.ready_title))
    }
}
