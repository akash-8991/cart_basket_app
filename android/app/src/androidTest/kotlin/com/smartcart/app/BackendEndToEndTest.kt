package com.smartcart.app

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
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcart.app.security.InMemoryTokenStore
import com.smartcart.data.ApiResult
import com.smartcart.model.AccountState
import com.smartcart.model.ProviderKey
import com.smartcart.model.ProviderStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The real app against a real local backend (Phase 3.7 exit check). Runs only with
 * `-Pandroid.testInstrumentationRunnerArguments.backendUrl=http://10.0.2.2:<port>`; see
 * tools/backend-integration.sh, which starts the backend and enables Blinkit through the operator API.
 *
 * Auth, account, providers and chat go over HTTP; comparison and the automation runtime are still the
 * on-device fakes until Phase 4.
 */
@RunWith(AndroidJUnit4::class)
class BackendEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val backendUrl: String? = InstrumentationRegistry.getArguments().getString("backendUrl")
    private val userId = "e2e" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun s(id: Int, vararg args: Any) = Device.str(id, *args)

    @Before fun setUp() {
        assumeTrue("no backendUrl argument; backend end-to-end test skipped", backendUrl != null)
        Device.grantNotifications()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Device.app.container = AppContainer(Device.context, AssetFixtureSource(Device.context), tokenStore = InMemoryTokenStore(), backendUrl = backendUrl)
        }
    }

    @After fun tearDown() {
        scenario?.close()
        if (backendUrl != null) InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Device.app.container = AppContainer(Device.context, AssetFixtureSource(Device.context)) // back to the default fakes
        }
    }

    @Test fun registerChatClarifyCompareApproveAgainstTheBackend() = screenshotOnFailure("registerChatClarifyCompareApproveAgainstTheBackend") {
        val c = Device.app.container
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // Registration goes to the backend.
        compose.waitForText(s(R.string.welcome_create))
        compose.clickable(s(R.string.welcome_create)).performClick()
        compose.waitForText(s(R.string.register_intro))
        compose.onNode(hasSetTextAction() and hasText(s(R.string.auth_user_id))).performTextInput(userId)
        compose.onNode(hasSetTextAction() and hasText(s(R.string.auth_email))).performTextInput("$userId@example.com")
        compose.onNode(hasSetTextAction() and hasText(s(R.string.auth_password))).performTextInput("correct horse battery")
        compose.onNode(hasSetTextAction() and hasText(s(R.string.register_confirm_password))).performTextInput("correct horse battery")
        compose.onNode(hasText(s(R.string.register_terms))).performScrollTo().performClick()
        compose.clickable(s(R.string.register_submit)).performScrollTo().performClick()
        compose.waitForText(s(R.string.home_what_do_you_need), timeoutMs = 30_000)
        assertEquals(AccountState.SignedIn(userId, emailVerificationRequired = true), c.auth.state.value)
        Device.screenshot("backend-e2e-01-home")

        // Consents the frozen UI has no screen for yet (Phase 3 contract gap 12), set through the API.
        runBlocking {
            assertTrue(c.account.setConsent("ai_processing", true) is ApiResult.Success)
            assertTrue(c.providers.setAutomationConsent(ProviderKey.BLINKIT, true) is ApiResult.Success)
        }
        compose.waitUntil(20_000) { c.providers.providers.value.any { it.key == ProviderKey.BLINKIT && it.status == ProviderStatus.ENABLED && it.consent.automation } }

        // Grocery chat: the backend's gateway asks which milk, then builds the cart.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.category_grocery)))
        compose.clickable(s(R.string.category_grocery)).performClick()
        compose.waitFor(hasContentDescription(s(R.string.chat_send)))
        compose.onNode(hasSetTextAction()).performTextInput("Milk, brown bread, eggs and bananas")
        compose.waitFor(hasContentDescription(s(R.string.chat_send)) and isEnabled(), timeoutMs = 30_000)
        compose.onNode(hasContentDescription(s(R.string.chat_send))).performClick()
        compose.waitForText("Which milk should I add?", timeoutMs = 30_000)
        Device.screenshot("backend-e2e-02-clarify")
        compose.clickable("Toned milk 1 L").performClick()
        compose.waitForText(s(R.string.chat_compare_cart), timeoutMs = 30_000)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.chat_compare_cart)))
        Device.screenshot("backend-e2e-03-cart")
        val serverChats = runBlocking { c.chat.sessions() }
        assertTrue("the conversation is stored on the backend", (serverChats as ApiResult.Success).value.isNotEmpty())
        compose.clickable(s(R.string.chat_compare_cart)).performClick()

        // Comparison and automation are still on-device fakes, gated by the backend's provider status and consent.
        compose.waitForText(s(R.string.cmp_use, "Blinkit"), timeoutMs = 30_000)
        Device.screenshot("backend-e2e-04-comparison")
        compose.clickable(s(R.string.cmp_use, "Blinkit")).performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText(s(R.string.approve_start)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText(s(R.string.cmp_switch_title, "Blinkit")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodes(hasText(s(R.string.cmp_switch_title, "Blinkit")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasText(s(R.string.cmp_use, "Blinkit")) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        }
        // Wait for the heading: the button is further down a lazy list and isn't built until scrolled to.
        compose.waitForText(s(R.string.approve_steps))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.approve_start)))
        compose.clickable(s(R.string.approve_start)).performClick()
        compose.waitForText(s(R.string.ready_open_cart, "Blinkit"), timeoutMs = 30_000)
        Device.screenshot("backend-e2e-05-cart-ready")

        // The backend holds the account and the consents the journey used.
        runBlocking {
            assertEquals(userId, (c.account.profile() as ApiResult.Success).value.userId)
            val consents = (c.account.consents() as ApiResult.Success).value.filter { it.granted }.map { it.key }
            assertTrue(consents.containsAll(listOf("ai_processing", "automation.blinkit")))
            c.auth.logout()
        }
    }
}
