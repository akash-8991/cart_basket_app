package com.smartcart.data.fake

import com.smartcart.data.AccountRepository
import com.smartcart.data.ApiResult
import com.smartcart.data.DashboardRepository
import com.smartcart.data.Fixtures
import com.smartcart.data.ProviderRepository
import com.smartcart.data.RecommendationRepository
import com.smartcart.data.mock.DashboardScenario
import com.smartcart.data.mock.ListScenario
import com.smartcart.data.mock.MockControl
import com.smartcart.model.ActivityPage
import com.smartcart.model.Category
import com.smartcart.model.Consent
import com.smartcart.model.ConsentList
import com.smartcart.model.DashboardSummary
import com.smartcart.model.DataRequestAccepted
import com.smartcart.model.DeleteScope
import com.smartcart.model.MonthlyMetrics
import com.smartcart.model.PrivacyMode
import com.smartcart.model.Profile
import com.smartcart.model.Provider
import com.smartcart.model.ProviderKey
import com.smartcart.model.ProviderList
import com.smartcart.model.RecommendationList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

class FakeDashboardRepository(fixtures: Fixtures, mock: MockControl) : FakeBase(fixtures, mock), DashboardRepository {
    override suspend fun summary(month: String?): ApiResult<DashboardSummary> = call {
        val base = when (cfg.dashboard) {
            DashboardScenario.SUCCESS -> fixtures.load<DashboardSummary>("dashboard/summary.success.json")
            DashboardScenario.STALE -> fixtures.load<DashboardSummary>("dashboard/summary.stale.json")
            DashboardScenario.EMPTY -> fixtures.load<DashboardSummary>("dashboard/summary.empty.json")
            DashboardScenario.ERROR -> return@call serviceError()
        }
        if (month == null || month == base.month) return@call ApiResult.Success(base)
        // Other months: take the server-computed metrics for that month from the monthly fixture.
        val m = fixtures.load<MonthlyMetrics>("dashboard/monthly.json").months
        val idx = m.indexOfFirst { it.month == month }
        if (idx < 0) return@call ApiResult.Success(base)
        val prev = m.getOrNull(idx - 1)
        fun pct(a: Double, b: Double?) = b?.takeIf { it != 0.0 }?.let { Math.round((a - it) / it * 1000) / 10.0 }
        ApiResult.Success(
            base.copy(
                month = month,
                kpis = m[idx].kpis,
                previousKpis = prev?.kpis,
                deltaVsPrevious = base.deltaVsPrevious.copy(
                    totalOrdersPct = pct(m[idx].kpis.totalOrders.toDouble(), prev?.kpis?.totalOrders?.toDouble()),
                    totalAmountPct = pct(m[idx].kpis.totalAmount.amountMinor.toDouble(), prev?.kpis?.totalAmount?.amountMinor?.toDouble()),
                    ordersPerWeekPct = pct(m[idx].kpis.ordersPerWeek, prev?.kpis?.ordersPerWeek),
                ),
                providerSplit = m[idx].providerSplit,
                categorySplit = m[idx].categorySplit,
            ),
        )
    }

    override suspend fun monthly(from: String, to: String, provider: ProviderKey?, category: Category?): ApiResult<MonthlyMetrics> = call {
        if (cfg.dashboard == DashboardScenario.ERROR) return@call serviceError()
        val all = fixtures.load<MonthlyMetrics>("dashboard/monthly.json")
        ApiResult.Success(all.copy(months = all.months.filter { it.month in from..to }))
    }
}

class FakeProviderRepository(fixtures: Fixtures, mock: MockControl) : FakeBase(fixtures, mock), ProviderRepository {
    private val _providers = MutableStateFlow(fixtures.load<ProviderList>("providers/capabilities.json").providers)
    override val providers: StateFlow<List<Provider>> = _providers

    override suspend fun refresh(): ApiResult<List<Provider>> = call(0.5) { ApiResult.Success(_providers.value) }

    override suspend fun setAutomationConsent(provider: ProviderKey, granted: Boolean): ApiResult<Unit> = call(0.3) {
        _providers.update { list -> list.map { if (it.key == provider) it.copy(consent = it.consent.copy(automation = granted)) else it } }
        ApiResult.Success(Unit)
    }

    override suspend fun setHistoryConsent(provider: ProviderKey, granted: Boolean): ApiResult<Unit> = call(0.3) {
        _providers.update { list -> list.map { if (it.key == provider) it.copy(consent = it.consent.copy(history = granted)) else it } }
        ApiResult.Success(Unit)
    }
}

class FakeRecommendationRepository(fixtures: Fixtures, mock: MockControl) : FakeBase(fixtures, mock), RecommendationRepository {
    private val dismissed = mutableSetOf<String>()

    override suspend fun list(category: Category?): ApiResult<RecommendationList> = call {
        when (cfg.suggestions) {
            ListScenario.ERROR -> serviceError()
            ListScenario.EMPTY -> ApiResult.Success(fixtures.load("recommendations/empty.json"))
            ListScenario.SUCCESS -> {
                val l = fixtures.load<RecommendationList>("recommendations/list.json")
                ApiResult.Success(l.copy(recommendations = l.recommendations.filter { it.recommendationId !in dismissed && (category == null || it.category == category) }))
            }
        }
    }

    override suspend fun feedback(recommendationId: String, useful: Boolean): ApiResult<Unit> = call(0.3) {
        if (!useful) dismissed += recommendationId
        ApiResult.Success(Unit)
    }
}

class FakeAccountRepository(fixtures: Fixtures, mock: MockControl) : FakeBase(fixtures, mock), AccountRepository {
    private var profile = fixtures.load<Profile>("me/profile.json")
    private var consents = fixtures.load<ConsentList>("me/consents.json").consents
    private val _paused = MutableStateFlow(false)
    override val automationPaused: StateFlow<Boolean> = _paused

    override suspend fun profile(): ApiResult<Profile> = call(0.5) { ApiResult.Success(profile) }
    override suspend fun consents(): ApiResult<List<Consent>> = call(0.5) { ApiResult.Success(consents) }

    override suspend fun setConsent(key: String, granted: Boolean): ApiResult<Consent> = call(0.3) {
        val c = Consent(key, granted, "2026-11", Instant.now().toString())
        consents = consents.filterNot { it.key == key } + c
        ApiResult.Success(c)
    }

    override suspend fun activity(): ApiResult<ActivityPage> = call {
        when (cfg.activity) {
            ListScenario.SUCCESS -> ApiResult.Success(fixtures.load("me/activity.json"))
            ListScenario.EMPTY -> ApiResult.Success(fixtures.load("me/activity.empty.json"))
            ListScenario.ERROR -> serviceError()
        }
    }

    override suspend fun export(password: String): ApiResult<DataRequestAccepted> = call {
        ApiResult.Success(fixtures.load("me/export.accepted.json"))
    }

    override suspend fun delete(scope: DeleteScope, password: String): ApiResult<DataRequestAccepted> = call {
        ApiResult.Success(fixtures.load("me/delete.accepted.json"))
    }

    override suspend fun setPrivacyMode(mode: PrivacyMode): ApiResult<Profile> = call(0.3) {
        profile = profile.copy(privacyMode = mode)
        ApiResult.Success(profile)
    }

    override fun setAutomationPaused(paused: Boolean) {
        _paused.value = paused
    }
}
