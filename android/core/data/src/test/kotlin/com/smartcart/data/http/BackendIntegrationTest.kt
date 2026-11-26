package com.smartcart.data.http

import com.smartcart.data.ApiResult
import com.smartcart.model.AccountState
import com.smartcart.model.Category
import com.smartcart.model.DeviceInfo
import com.smartcart.model.ErrorCode
import com.smartcart.model.NextStep
import com.smartcart.model.ProviderKey
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
}
