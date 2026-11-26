package com.smartcart.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import com.smartcart.app.feature.auth.LoginScreen
import com.smartcart.app.feature.auth.RegisterScreen
import com.smartcart.app.feature.auth.WelcomeScreen
import com.smartcart.app.feature.comparison.ComparisonScreen
import com.smartcart.app.feature.home.HomeScreen
import com.smartcart.app.feature.settings.HelpScreen
import com.smartcart.app.feature.settings.PrivacyScreen
import com.smartcart.app.feature.settings.ProvidersScreen
import com.smartcart.app.feature.task.ApproveScreen
import com.smartcart.app.feature.task.AutomationConsentScreen
import com.smartcart.app.feature.task.CartReadyScreen
import com.smartcart.app.feature.task.TaskScreen
import com.smartcart.app.security.InMemoryTokenStore
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.data.ApiResult
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs Google's Accessibility Test Framework (contrast, touch targets, missing labels, duplicate
 * descriptions, clickable spans…) over each key screen in light and dark mode, and saves a
 * screenshot of each for review. The device's font scale is whatever the matrix script set.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityAuditTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun container(cfg: MockConfig) = AppContainer(null, TestFixtures.source(), cfg, InMemoryTokenStore())

    private fun quote(c: AppContainer) = runBlocking {
        (c.comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value
    }

    private fun s(id: Int, vararg args: Any) = Device.str(id, *args)

    @Test
    fun keyScreensPassAccessibilityChecks() {
        Device.grantNotifications() // otherwise TaskScreen's just-in-time permission dialog covers the app
        var screen by mutableStateOf<@Composable () -> Unit>({})
        var dark by mutableStateOf(false)
        var c by mutableStateOf(container(MockConfig(latencyMs = 0)))
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides c) {
                SmartCartTheme(darkTheme = dark) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { screen() } }
            }
        }
        // Compose only exposes its full accessibility tree while an accessibility service is running.
        // Forcing it is safe from API 34; older AccessibilityManagers throw when events are sent with
        // no service enabled, and there the canary below skips the audit instead.
        if (Device.api >= 34) rule.runOnUiThread { forceComposeAccessibility(rule.activity.window.decorView) }
        val validator = AccessibilityValidator().setRunChecksFromRootView(true).setThrowExceptionFor(null)
        val problems = mutableListOf<String>()

        // Canary: the checker must flag a deliberately unlabelled 20dp Compose button, otherwise it cannot
        // see Compose nodes on this Android version and a "pass" would be meaningless. It is centred
        // because ATF excuses elements clipped by the screen edge. Touch-target size is not used as the
        // signal: Compose reports a small clickable's extended 48dp hit area as its bounds on newer Android.
        screen = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Box(Modifier.size(20.dp).background(Color.Gray).clickable { }) } }
        rule.waitForIdle()
        var canary = emptyList<String>()
        rule.runOnUiThread {
            val probeMaxPx = (60 * rule.activity.resources.displayMetrics.density).toInt()
            canary = validator.checkAndReturnResults(rule.activity.window.decorView)
                .filter { it.type == AccessibilityCheckResultType.ERROR }
                .filter { r -> r.element?.isClickable == true && r.element?.boundsInScreen?.let { it.width in 1..probeMaxPx && it.height in 1..probeMaxPx } == true }
                .map { it.sourceCheckClass.simpleName }
        }
        val sees = "SpeakableTextPresentCheck" in canary
        if (Device.api >= 34) assertTrue("ATF no longer detects Compose problems on API ${Device.api}: $canary", sees)
        assumeTrue("ATF cannot inspect Compose nodes on API ${Device.api}; automated audit skipped", sees)

        fun audit(name: String, waitText: String) {
            rule.waitForText(waitText, substring = true)
            rule.waitForIdle()
            rule.runOnUiThread {
                val content = rule.activity.findViewById<android.view.View>(android.R.id.content)
                val loc = IntArray(2).also { content.getLocationOnScreen(it) }
                val contentTop = loc[1]
                val contentBottom = loc[1] + content.height
                val sliverPx = (48 * rule.activity.resources.displayMetrics.density).toInt()
                validator.checkAndReturnResults(rule.activity.window.decorView)
                    .filter { it.type == AccessibilityCheckResultType.ERROR }
                    // Items cut off by the edge of the content area (e.g. the next card in a scrolling
                    // list) only expose a sliver to ATF; they are checked when scrolled fully into view.
                    .filterNot { r -> r.element?.boundsInScreen?.let { b -> (b.bottom >= contentBottom || b.top <= contentTop) && b.height < sliverPx } == true }
                    .forEach { r ->
                        val e = r.element
                        val where = e?.let { " <${it.className} bounds=${it.boundsInScreen} text=${it.text} desc=${it.contentDescription} clickable=${it.isClickable}>" } ?: ""
                        problems += "[$name${if (dark) " dark" else ""}] ${r.sourceCheckClass.simpleName}: ${r.message}$where"
                    }
            }
            Device.screenshot("a11y-$name${if (dark) "-dark" else ""}")
        }
        screen = { WelcomeScreen({}, {}, {}) }; audit("welcome", s(R.string.welcome_create))
        screen = { RegisterScreen({}, {}, {}) }; audit("register", s(R.string.register_intro))
        screen = { LoginScreen({}, {}, {}, {}) }; audit("login", s(R.string.login_remember))
        screen = { HomeScreen({}, {}, {}, {}, {}, {}, {}) }; audit("home", s(R.string.kpi_total_orders))
        dark = true; audit("home", s(R.string.kpi_total_orders)); dark = false

        val q1 = quote(c)
        screen = { ComparisonScreen(q1, {}, { _, _ -> }, {}, { _, _ -> }, {}) }; audit("comparison", s(R.string.rec_label_cheapest))
        dark = true; audit("comparison", s(R.string.rec_label_cheapest)); dark = false

        c = container(MockConfig(latencyMs = 0, comparison = ComparisonScenario.PARTIAL_COVERAGE))
        val q2 = quote(c)
        screen = { ComparisonScreen(q2, {}, { _, _ -> }, {}, { _, _ -> }, {}) }; audit("comparison-partial", s(R.string.cmp_partial_title))

        c = container(MockConfig(latencyMs = 0))
        val q3 = quote(c)
        screen = { AutomationConsentScreen(ProviderKey.BIGBASKET, q3, {}, {}, {}) }; audit("consent", s(R.string.consent_will))

        c = container(MockConfig(latencyMs = 0, task = TaskScenario.CHECKPOINT))
        val q4 = quote(c)
        val t = runBlocking { (c.tasks.create(q4, ProviderKey.BLINKIT) as ApiResult.Success).value }
        screen = { ApproveScreen(t.taskId, {}, {}) }; audit("approve", s(R.string.approve_steps))
        runBlocking { c.tasks.approve(t.taskId, t.plannedActions.map { it.actionId }) }
        rule.waitUntil(10_000) { c.tasks.activeTask.value?.status == TaskStatus.CHECKPOINT }
        screen = { TaskScreen(t.taskId, {}, {}, { _, _, _ -> }, {}) }; audit("task-checkpoint", s(R.string.task_status_checkpoint))

        c = container(MockConfig(latencyMs = 0))
        val q5 = quote(c)
        val t2 = runBlocking { (c.tasks.create(q5, ProviderKey.BLINKIT) as ApiResult.Success).value }
        runBlocking { c.tasks.approve(t2.taskId, t2.plannedActions.map { it.actionId }) }
        rule.waitUntil(10_000) { runBlocking { c.tasks.observe(t2.taskId).first() }?.status == TaskStatus.CART_READY }
        screen = { CartReadyScreen(t2.taskId, {}, { _, _ -> }, {}) }; audit("cart-ready", s(R.string.ready_verified_body, "Blinkit"))

        screen = { ProvidersScreen({}) }; audit("providers", s(R.string.providers_intro))
        screen = { PrivacyScreen({}, {}) }; audit("privacy", s(R.string.privacy_user_id))
        screen = { HelpScreen({}) }; audit("help", s(R.string.help_stop_title))
        screen = { com.smartcart.app.feature.auth.VerifyEmailScreen({}, {}) }; audit("verify-email", s(R.string.verify_body))

        assertTrue("Accessibility errors (API ${Device.api}, font ${Device.fontScale}):\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }
}

private fun forceComposeAccessibility(v: android.view.View) {
    if (v is androidx.compose.ui.platform.ViewRootForTest) v.forceAccessibilityForTesting(true)
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) forceComposeAccessibility(v.getChildAt(i))
}
