package com.smartcart.app

import android.content.Context
import com.smartcart.app.notifications.Notifier
import com.smartcart.app.notifications.NotificationPrefs
import com.smartcart.app.security.InMemoryTokenStore
import com.smartcart.app.security.KeystoreTokenStore
import com.smartcart.data.AccountRepository
import com.smartcart.data.AuthRepository
import com.smartcart.data.CartTaskRepository
import com.smartcart.data.ChatRepository
import com.smartcart.data.ComparisonRepository
import com.smartcart.data.DashboardRepository
import com.smartcart.data.FeatureFlags
import com.smartcart.data.FixtureSource
import com.smartcart.data.Fixtures
import com.smartcart.data.FlagKeys
import com.smartcart.data.HistoryRepository
import com.smartcart.data.LocalAnalyticsCollector
import com.smartcart.data.ProviderRepository
import com.smartcart.data.RecommendationRepository
import com.smartcart.data.TokenStore
import com.smartcart.data.fake.FakeAccountRepository
import com.smartcart.data.fake.FakeAuthRepository
import com.smartcart.data.fake.FakeCartTaskRepository
import com.smartcart.data.fake.FakeChatRepository
import com.smartcart.data.fake.FakeComparisonRepository
import com.smartcart.data.fake.FakeDashboardRepository
import com.smartcart.data.fake.FakeHistoryRepository
import com.smartcart.data.fake.FakeProviderRepository
import com.smartcart.data.fake.FakeRecommendationRepository
import com.smartcart.data.mock.MockConfig
import com.smartcart.data.mock.MockControl
import com.smartcart.data.http.ApiClient
import com.smartcart.data.http.HttpAccountRepository
import com.smartcart.data.http.HttpAuthRepository
import com.smartcart.data.http.HttpChatRepository
import com.smartcart.data.http.HttpProviderRepository
import com.smartcart.app.security.DeviceIdentity
import com.smartcart.model.AccountState
import com.smartcart.model.DeviceInfo
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manual DI (D-009). Phase 1 wires fixture-backed fakes; Phase 3 swaps in HTTP implementations
 * of the same repository interfaces without touching the UI.
 */
class AppContainer(
    context: Context?,
    fixtureSource: FixtureSource,
    mockConfig: MockConfig = MockConfig(),
    tokenStore: TokenStore? = null,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Phase 3.7: when set, auth, account, providers and chat use the backend; everything else stays on fakes until Phase 4. */
    val backendUrl: String? = null,
) {
    val mock = MockControl(mockConfig)
    val fixtures = Fixtures(fixtureSource)
    // Mock builds turn on flows that are off by default in production so they can be exercised.
    val flags = FeatureFlags(mapOf(FlagKeys.AUTOMATION_GLOBAL to true, FlagKeys.HISTORY_IMPORT to true))
    val analytics = LocalAnalyticsCollector(appVersion = BuildConfig.VERSION_NAME)
    val notificationPrefs = NotificationPrefs()

    private val tokens: TokenStore = tokenStore
        ?: context?.let { runCatching { KeystoreTokenStore(it.applicationContext) }.getOrNull() }
        ?: InMemoryTokenStore()

    private val api: ApiClient? = backendUrl?.takeIf { it.isNotBlank() }?.let {
        require(BuildConfig.DEBUG || it.startsWith("https://")) { "release builds talk to the backend over HTTPS only" }
        ApiClient(it)
    }
    private val httpAuth: HttpAuthRepository? = api?.let { a ->
        val identity = context?.let { DeviceIdentity(it) }
        HttpAuthRepository(a, tokens) { identity?.info() ?: DeviceInfo(deviceId = "test-device", appVersion = BuildConfig.VERSION_NAME) }
    }

    val auth: AuthRepository = httpAuth ?: FakeAuthRepository(fixtures, mock, tokens)
    val dashboard: DashboardRepository = FakeDashboardRepository(fixtures, mock)
    val account: AccountRepository = httpAuth?.let { HttpAccountRepository(api!!, it) } ?: FakeAccountRepository(fixtures, mock)
    // Which provider apps are installed needs verified package names, which provider-matrix.md withholds
    // until the Phase 4 device spike; until then the app reports "unknown" (null).
    val providers: ProviderRepository = api?.let { HttpProviderRepository(it, account) } ?: FakeProviderRepository(fixtures, mock)
    val chat: ChatRepository = api?.let { HttpChatRepository(it, providers) } ?: FakeChatRepository(fixtures, mock)
    val comparisons: ComparisonRepository = FakeComparisonRepository(fixtures, mock)
    val tasks: CartTaskRepository = FakeCartTaskRepository(fixtures, mock, comparisons, providers, account, scope)
    val history: HistoryRepository = FakeHistoryRepository(fixtures, mock, providers, scope)
    val recommendations: RecommendationRepository = FakeRecommendationRepository(fixtures, mock)

    val notifier: Notifier? = context?.let { Notifier(it.applicationContext, notificationPrefs) }

    init {
        notifier?.createChannels()
        watchTasksForNotifications()
        if (api != null) refreshProvidersOnSignIn()
    }

    /** Server-backed provider status and consents load once the user is signed in. */
    private fun refreshProvidersOnSignIn() {
        scope.launch {
            auth.state.collect { if (it is AccountState.SignedIn) providers.refresh() }
        }
    }

    /** Posts cart-ready / needs-you notifications even when the user is in another app. */
    private fun watchTasksForNotifications() {
        val n = notifier ?: return
        scope.launch {
            var lastActiveId: String? = null
            var lastStatus: TaskStatus? = null
            tasks.activeTask.collect { active ->
                if (active != null) {
                    if (active.status == TaskStatus.NEEDS_USER_ACTION && lastStatus != TaskStatus.NEEDS_USER_ACTION) n.needsUserAction(active)
                    lastActiveId = active.taskId
                    lastStatus = active.status
                } else {
                    val finishedId = lastActiveId
                    lastActiveId = null
                    lastStatus = null
                    if (finishedId != null) {
                        val final = tasks.observe(finishedId).first()
                        if (final?.status == TaskStatus.CART_READY) n.cartReady(final)
                    }
                }
            }
        }
    }
}

class AssetFixtureSource(private val context: Context) : FixtureSource {
    override fun read(path: String): String = context.assets.open("fixtures/$path").bufferedReader().use { it.readText() }
}
