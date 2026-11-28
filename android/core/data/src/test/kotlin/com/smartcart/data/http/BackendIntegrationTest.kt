package com.smartcart.data.http

import com.smartcart.data.ApiResult
import com.smartcart.model.AccountState
import com.smartcart.model.Category
import com.smartcart.model.DeviceInfo
import com.smartcart.model.ErrorCode
import com.smartcart.model.NextStep
import com.smartcart.model.ProviderKey
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * The real HTTP repositories against a running backend (Phase 3.7). Skipped unless one is given:
 *   ./gradlew :core:data:test -PbackendUrl=http://127.0.0.1:8080
 * (tools/backend-integration.sh starts a fresh backend and does this.)
 */
class BackendIntegrationTest {
    private val url: String? = System.getProperty("backendUrl")
    private lateinit var api: ApiClient
    private lateinit var store: MemoryTokenStore
    private lateinit var auth: HttpAuthRepository
    private lateinit var account: HttpAccountRepository
    private lateinit var providers: HttpProviderRepository
    private lateinit var chat: HttpChatRepository
    private val userId = "it" + UUID.randomUUID().toString().replace("-", "").take(10)
    private val password = "correct horse battery"

    @Before fun setUp() {
        assumeTrue("no backendUrl given; backend integration skipped", url != null)
        api = ApiClient(url!!)
        store = MemoryTokenStore()
        auth = HttpAuthRepository(api, store) { DeviceInfo(deviceId = "it-device-$userId", appVersion = "1.0.0", osVersion = "15") }
        account = HttpAccountRepository(api, auth)
        providers = HttpProviderRepository(api, account) { it == ProviderKey.BLINKIT }
        chat = HttpChatRepository(api, providers)
    }

    private fun <T> ApiResult<T>.ok(): T = (this as? ApiResult.Success)?.value ?: error("expected success, got $this")

    @Test fun registerProfileConsentProvidersAndSessions() = runBlocking {
        auth.register(userId, "$userId@example.com", password).ok()
        assertEquals(AccountState.SignedIn(userId, emailVerificationRequired = true), auth.state.value)
        assertEquals(userId, account.profile().ok().userId)
        assertFalse(account.consents().ok().single { it.key == "ai_processing" }.granted)
        assertTrue(account.setConsent("ai_processing", true).ok().granted)

        val list = providers.refresh().ok()
        assertEquals(11, list.size)
        assertEquals(true, list.single { it.key == ProviderKey.BLINKIT }.installed)
        providers.setAutomationConsent(ProviderKey.BLINKIT, true).ok()
        assertTrue(providers.providers.value.single { it.key == ProviderKey.BLINKIT }.consent.automation)
        assertEquals(1, auth.sessions().ok().size)
    }

    @Test fun groceryChatClarifiesThenBuildsACart() = runBlocking {
        auth.register(userId, "$userId@example.com", password).ok()
        account.setConsent("ai_processing", true).ok()
        val session = chat.createSession(Category.GROCERY).ok()
        val first = chat.send(session.sessionId, Category.GROCERY, "Milk, brown bread, eggs and bananas").ok()
        assertEquals(NextStep.CLARIFY, first.nextStep)
        val option = first.clarification!!.options.first { it.label == "Toned milk 1 L" }
        val cart = chat.send(session.sessionId, Category.GROCERY, option.label, first.clarification!!.clarificationId to option.optionId).ok()
        assertEquals(NextStep.COMPARE, cart.nextStep)
        assertEquals(listOf("Toned milk", "Brown bread", "Eggs", "Bananas"), cart.cartDraft!!.lines.map { it.name })
        assertTrue(chat.sessions().ok().any { it.sessionId == session.sessionId })
    }

    @Test fun aRememberedDeviceResumesAndRefreshRotates() = runBlocking {
        auth.register(userId, "$userId@example.com", password).ok()
        val first = store.stored!!.refreshToken
        val again = HttpAuthRepository(api, store) { DeviceInfo(deviceId = "it-device-$userId", appVersion = "1.0.0") }
        assertEquals(AccountState.Remembered(userId), again.state.value)
        again.resume().ok()
        assertTrue(store.stored!!.refreshToken != first)
        assertEquals(userId, HttpAccountRepository(api, again).profile().ok().userId)
    }

    @Test fun logoutEndsTheSessionOnTheServer() = runBlocking {
        auth.register(userId, "$userId@example.com", password).ok()
        val token = auth.accessToken
        auth.logout()
        val stale = ApiClient(url!!).apply { tokens = object : SessionTokens { override val accessToken = token; override suspend fun refresh() = false } }
        val r = stale.call("GET", "/v1/me", com.smartcart.model.Profile.serializer())
        assertEquals(ErrorCode.SESSION_EXPIRED, (r as ApiResult.Failure).error.code)
    }

    @Test fun wrongPasswordAndValidationErrorsComeBackAsContractErrors() = runBlocking {
        auth.register(userId, "$userId@example.com", password).ok()
        auth.logout()
        assertEquals(ErrorCode.INVALID_CREDENTIALS, (auth.login(userId, "not the password", true) as ApiResult.Failure).error.code)
        assertEquals(ErrorCode.WEAK_PASSWORD, (HttpAuthRepository(api, MemoryTokenStore()) { DeviceInfo(deviceId = "d2", appVersion = "1") }
            .register(userId + "x", "x@example.com", "1234567890") as ApiResult.Failure).error.code)
    }

    // ---------------------------------------------------------------- Phase 4.4: the server's comparison engine

    private val cart = listOf(
        com.smartcart.model.CartLine("l1", "Toned milk", 2, "1 L"), com.smartcart.model.CartLine("l2", "Brown bread", 1, "400 g"),
        com.smartcart.model.CartLine("l3", "Eggs", 1, "12 pack"), com.smartcart.model.CartLine("l4", "Bananas", 1, "6 pcs"),
    )
    private val grocers = listOf(ProviderKey.BLINKIT, ProviderKey.BIGBASKET, ProviderKey.SWIGGY_INSTAMART)
    private fun request() = com.smartcart.model.QuoteRequest(Category.GROCERY, cart, null, grocers, ProviderKey.BIGBASKET, com.smartcart.model.Objective.LOWEST_TOTAL, deliveryLabel = "Home")
    private fun com.smartcart.model.ComparisonQuote.of(p: ProviderKey) = providerResults.single { it.provider == p }

    @Test fun theServerWorksOutTheComparison() = runBlocking {
        auth.register(userId, "$userId@example.com", password).ok()
        val comparisons = HttpComparisonRepository(api, pollMillis = 200)
        val id = comparisons.create(request()).ok()
        val q = withTimeout(20_000) { comparisons.observe(id).toList() }.last().ok()
        assertEquals(com.smartcart.model.ComparisonStatus.COMPLETE, q.status)
        assertEquals(ProviderKey.BLINKIT, q.recommendation.provider)
        assertEquals(com.smartcart.model.RecommendationLabel.CHEAPEST, q.badgeLabel)
        assertEquals(22900L, q.of(ProviderKey.BLINKIT).totalEstimate!!.amountMinor)
        assertTrue(q.assumptions.contains("Delivery to your saved address \"Home\""))
        assertEquals(ProviderKey.BLINKIT, comparisons.confirm(id, ProviderKey.BLINKIT, acknowledgedSwitch = true).ok().confirmedProvider)
    }

    @Test fun thePhonesReadingChangesTheServersQuote() = runBlocking {
        auth.register(userId, "$userId@example.com", password).ok()
        providers.refresh().ok()
        providers.setAutomationConsent(ProviderKey.BLINKIT, true).ok()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val store = com.smartcart.automation.FakeStore("normal", System::currentTimeMillis)
        val phone = object : com.smartcart.data.device.OnDeviceAutomation {
            override fun driver() = store
            override val verifier = com.smartcart.automation.FakeVerifier()
            override fun adapterFor(provider: ProviderKey) =
                if (provider == ProviderKey.BLINKIT) com.smartcart.automation.teststore.TestStoreAdapter(setOf(com.smartcart.automation.FakeVerifier.GOOD_SIGNER)) else null
        }
        val comparisons = com.smartcart.data.device.DeviceComparisonRepository(
            HttpComparisonRepository(api, pollMillis = 200), phone, providers, account, scope,
            timing = com.smartcart.automation.RunnerTiming(pollMillis = 5, stepWaitMillis = 400, foreignPackageGraceMillis = 100),
        )
        val id = comparisons.create(request()).ok()
        val q = withTimeout(30_000) { comparisons.observe(id).toList() }.last().ok()
        val blinkit = q.of(ProviderKey.BLINKIT)
        assertEquals(com.smartcart.model.ResultSource.DEVICE, blinkit.source)
        assertEquals("₹233 read in the Test Store plus its ₹5 delivery fee", 23300L + 500L, blinkit.totalEstimate!!.amountMinor)
        assertEquals(com.smartcart.model.ResultSource.SERVER, q.of(ProviderKey.BIGBASKET).source)
        assertEquals(ProviderKey.BLINKIT, q.recommendation.provider)
        assertEquals("₹264 at BigBasket (the person's pick) minus ₹238", 2600L, q.recommendation.savingsVsSelected!!.amountMinor)
        scope.cancel()
    }

    // ---------------------------------------------------------------- Phase 4.5: tasks on the server

    @Test fun aTaskTheServerRunsGoesFromApprovalToAHandoffLink() = runBlocking {
        auth.register(userId, "$userId@example.com", password).ok()
        providers.refresh().ok()
        providers.setAutomationConsent(ProviderKey.BLINKIT, true).ok()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val comparisons = HttpComparisonRepository(api, pollMillis = 200)
        val tasks = HttpCartTaskRepository(api, comparisons, scope, pollMillis = 200)
        val id = comparisons.create(request()).ok()
        withTimeout(20_000) { comparisons.observe(id).toList() }
        comparisons.confirm(id, ProviderKey.BLINKIT, acknowledgedSwitch = true).ok()
        val t = tasks.create(id, ProviderKey.BLINKIT).ok()
        assertEquals(com.smartcart.model.TaskStatus.AWAITING_APPROVAL, t.status)
        assertTrue("the server issued the single-use nonce", t.approvalNonce != null)
        tasks.approve(t.taskId, t.plannedActions.map { it.actionId }).ok()
        val ready = withTimeout(20_000) { tasks.observe(t.taskId).first { it?.status == com.smartcart.model.TaskStatus.CART_READY } }!!
        assertTrue(ready.cart!!.verified)
        val h = tasks.createHandoff(t.taskId).ok()
        assertTrue(Regex("^smartcart://handoff/[A-Za-z0-9_-]{16,128}$").matches(h.handoffUrl) && t.taskId !in h.handoffUrl)
        assertEquals(com.smartcart.model.TaskStatus.HANDED_OFF, withTimeout(10_000) { tasks.observe(t.taskId).first { it?.status == com.smartcart.model.TaskStatus.HANDED_OFF } }!!.status)
        scope.cancel()
    }

    @Test fun aTaskThePhoneRunsIsReportedToTheServerWhichChecksTheCart() = runBlocking {
        auth.register(userId, "$userId@example.com", password).ok()
        providers.refresh().ok()
        providers.setAutomationConsent(ProviderKey.BLINKIT, true).ok()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val store = com.smartcart.automation.FakeStore("normal", System::currentTimeMillis)
        val phone = object : com.smartcart.data.device.OnDeviceAutomation {
            override fun driver() = store
            override val verifier = com.smartcart.automation.FakeVerifier()
            override fun adapterFor(provider: ProviderKey) =
                if (provider == ProviderKey.BLINKIT) com.smartcart.automation.teststore.TestStoreAdapter(setOf(com.smartcart.automation.FakeVerifier.GOOD_SIGNER)) else null
        }
        val timing = com.smartcart.automation.RunnerTiming(pollMillis = 5, stepWaitMillis = 400, foreignPackageGraceMillis = 100)
        val comparisons = com.smartcart.data.device.DeviceComparisonRepository(HttpComparisonRepository(api, pollMillis = 200), phone, providers, account, scope, timing = timing)
        val server = HttpCartTaskRepository(api, comparisons, scope)
        val tasks = com.smartcart.data.device.DeviceCartTaskRepository(phone, comparisons, providers, account, scope, timing = timing, server = server, retryMillis = 200)
        val id = comparisons.create(request()).ok()
        withTimeout(30_000) { comparisons.observe(id).toList() }
        comparisons.confirm(id, ProviderKey.BLINKIT, acknowledgedSwitch = true).ok()
        val t = tasks.create(id, ProviderKey.BLINKIT).ok()
        tasks.approve(t.taskId, t.plannedActions.map { it.actionId }).ok()
        val done = withTimeout(30_000) { tasks.observe(t.taskId).first { it != null && (it.status.isTerminal || it.cart?.verified == true) } }!!
        assertEquals(done.events.joinToString("\n") { it.description }, com.smartcart.model.TaskStatus.CART_READY, done.status)
        // The server's own record agrees: it took every report and checked the cart against the approved plan.
        // Reports are sent as the phone goes; wait for the last one to land.
        val onServer = withTimeout(15_000) {
            var got = server.getTask(t.taskId).ok()
            while (got.status != com.smartcart.model.TaskStatus.CART_READY) { kotlinx.coroutines.delay(200); got = server.getTask(t.taskId).ok() }
            got
        }
        assertTrue(onServer.cart!!.verified)
        assertEquals(done.events.map { it.seq }, onServer.events.map { it.seq })
        assertTrue(tasks.createHandoff(t.taskId).ok().handoffUrl.startsWith("smartcart://handoff/"))
        scope.cancel()
    }
}
