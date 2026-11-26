package com.smartcart.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartcart.app.feature.auth.VerifyEmailScreen
import com.smartcart.app.feature.chat.ChatScreen
import com.smartcart.app.feature.home.HomeScreen
import com.smartcart.app.security.InMemoryTokenStore
import com.smartcart.app.ui.AppNavHost
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.data.ApiResult
import com.smartcart.data.mock.ChatScenario
import com.smartcart.data.mock.MockConfig
import com.smartcart.designsystem.SmartCartTheme
import com.smartcart.model.AccountState
import com.smartcart.model.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** CR-003 in the app: AI-processing consent, email verification, accepting updated terms. Shared: JVM and device. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class Cr003UiTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun s(id: Int) = ctx.getString(id)

    private fun container(cfg: MockConfig = MockConfig(latencyMs = 0)) = AppContainer(null, TestFixtures.source(), cfg, InMemoryTokenStore())

    private fun show(c: AppContainer, content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalAppContainer provides c) { SmartCartTheme(darkTheme = false) { content() } }
    }

    private fun waitForText(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun aiGranted(c: AppContainer) = runBlocking { (c.account.consents() as ApiResult.Success).value.single { it.key == "ai_processing" }.granted }

    /** Opens a grocery chat with AI consent not yet given, types a message and taps Send. */
    private fun chatWithoutAiConsent(cfg: MockConfig = MockConfig(latencyMs = 0)): AppContainer {
        val c = container(cfg)
        runBlocking { c.account.setConsent("ai_processing", false) }
        show(c) { ChatScreen(Category.GROCERY, onBack = {}, onComparison = {}) }
        rule.onNode(hasSetTextAction()).performTextInput("2 x eggs")
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription(s(R.string.chat_send)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription(s(R.string.chat_send))).performClick()
        return c
    }

    @Test fun aiHelpIsAskedBeforeTheFirstMessageAndNotNowStillSends() {
        val c = chatWithoutAiConsent()
        waitForText(s(R.string.ai_consent_title))
        assertTrue("nothing is sent until the question is answered", gone("2 x eggs"))
        rule.onNodeWithText(s(R.string.ai_consent_not_now)).performClick()
        rule.waitUntil(5_000) { gone(s(R.string.ai_consent_title)) }
        waitForText("2 x eggs") // then the held message is sent
        assertFalse(aiGranted(c))
        assertTrue("not asked again on this device", c.aiPrompts.asked(""))
    }

    @Test fun useAiHelpGrantsConsentAndSends() {
        val c = chatWithoutAiConsent()
        waitForText(s(R.string.ai_consent_title))
        rule.onNodeWithText(s(R.string.ai_consent_allow)).performClick()
        rule.waitUntil(5_000) { aiGranted(c) }
        rule.waitUntil(5_000) { gone(s(R.string.ai_consent_title)) }
    }

    @Test fun limitedRepliesOfferToTurnAiHelpOn() {
        val c = chatWithoutAiConsent(MockConfig(latencyMs = 0, chat = ChatScenario.DEGRADED))
        waitForText(s(R.string.ai_consent_title))
        rule.onNodeWithText(s(R.string.ai_consent_not_now)).performClick()
        waitForText(s(R.string.chat_ai_turn_on))
        rule.onNodeWithText(s(R.string.chat_ai_turn_on)).performClick()
        rule.waitUntil(5_000) { aiGranted(c) }
        rule.waitUntil(5_000) { gone(s(R.string.chat_ai_turn_on)) }
    }

    @Test fun unverifiedAccountsSeeTheVerifyBanner() {
        val c = container()
        runBlocking { c.auth.register("priya.k", "priya@example.com", "correct horse battery") }
        var opened = false
        show(c) { HomeScreen({}, {}, {}, {}, {}, {}, {}, onVerifyEmail = { opened = true }) }
        waitForText(s(R.string.home_verify_title))
        rule.onNodeWithText(s(R.string.home_verify_action)).performClick()
        assertTrue(opened)
    }

    @Test fun theVerifyScreenRejectsAWrongCodeThenVerifies() {
        val c = container()
        runBlocking { c.auth.register("priya.k", "priya@example.com", "correct horse battery") }
        var done = false
        show(c) { VerifyEmailScreen(onDone = { done = true }, onBack = {}) }
        rule.onNode(hasSetTextAction()).performTextInput("000000")
        rule.onNodeWithText(s(R.string.verify_submit)).performClick()
        rule.waitForIdle()
        assertFalse(done)
        rule.onNode(hasSetTextAction()).performTextReplacement("482913")
        rule.onNodeWithText(s(R.string.verify_submit)).performClick()
        rule.waitUntil(5_000) { done }
        assertEquals(AccountState.SignedIn("priya.k", emailVerificationRequired = false), c.auth.state.value)
    }

    @Test fun updatedTermsMustBeAcceptedBeforeContinuing() {
        val c = container()
        runBlocking {
            c.auth.register("priya.k", "priya@example.com", "correct horse battery")
            c.account.acceptPolicy("terms", "2026-10") // accepted an older version than the server's 2026-11
        }
        show(c) { AppNavHost(pendingLink = MutableStateFlow(null), consumeLink = {}) }
        waitForText(s(R.string.policy_update_title))
        rule.onNodeWithText(s(R.string.policy_update_accept)).performClick()
        rule.waitUntil(5_000) { gone(s(R.string.policy_update_title)) }
        val terms = runBlocking { (c.account.consents() as ApiResult.Success).value.single { it.key == "terms" } }
        assertEquals("2026-11", terms.version)
    }
}
