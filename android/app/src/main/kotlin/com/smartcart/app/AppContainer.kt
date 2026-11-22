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

    val auth: AuthRepository = FakeAuthRepository(fixtures, mock, tokens)
    val dashboard: DashboardRepository = FakeDashboardRepository(fixtures, mock)
    val providers: ProviderRepository = FakeProviderRepository(fixtures, mock)
    val account: AccountRepository = FakeAccountRepository(fixtures, mock)
    val chat: ChatRepository = FakeChatRepository(fixtures, mock)
    val comparisons: ComparisonRepository = FakeComparisonRepository(fixtures, mock)
    val tasks: CartTaskRepository = FakeCartTaskRepository(fixtures, mock, comparisons, providers, account, scope)
    val history: HistoryRepository = FakeHistoryRepository(fixtures, mock, providers, scope)
    val recommendations: RecommendationRepository = FakeRecommendationRepository(fixtures, mock)

    val notifier: Notifier? = context?.let { Notifier(it.applicationContext, notificationPrefs) }

    init {
        notifier?.createChannels()
        watchTasksForNotifications()
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
