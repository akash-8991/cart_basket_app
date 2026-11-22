package com.smartcart.data.fake

import com.smartcart.data.ApiResult
import com.smartcart.data.Fixtures
import com.smartcart.data.HistoryRepository
import com.smartcart.data.ProviderRepository
import com.smartcart.data.clientError
import com.smartcart.data.mock.ImportScenario
import com.smartcart.data.mock.ListScenario
import com.smartcart.data.mock.MockControl
import com.smartcart.model.Confidence
import com.smartcart.model.ErrorCode
import com.smartcart.model.ImportField
import com.smartcart.model.ImportSession
import com.smartcart.model.ImportStatus
import com.smartcart.model.Order
import com.smartcart.model.OrderPage
import com.smartcart.model.PrivacyMode
import com.smartcart.model.ProviderImportProgress
import com.smartcart.model.ProviderImportStatus
import com.smartcart.model.ProviderKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Simulates on-device order-history extraction. Raw screen text never exists in Phase 1. */
class FakeHistoryRepository(
    fixtures: Fixtures,
    mock: MockControl,
    private val providers: ProviderRepository,
    private val scope: CoroutineScope,
) : FakeBase(fixtures, mock), HistoryRepository {

    private var orders: List<Order> = fixtures.load<OrderPage>("history/orders.json").orders
    private val sessions = MutableStateFlow<Map<String, ImportSession>>(emptyMap())
    private val jobs = mutableMapOf<String, Job>()

    override suspend fun start(providers: List<ProviderKey>, fields: Set<ImportField>, mode: PrivacyMode): ApiResult<ImportSession> = call(0.5) {
        if (providers.isEmpty()) return@call clientError(ErrorCode.VALIDATION_FAILED, "Choose at least one app.")
        val missingConsent = providers.filter { p -> this.providers.providers.value.firstOrNull { it.key == p }?.consent?.history != true }
        if (missingConsent.isNotEmpty()) return@call fixtures.error("errors/consent-required.json")
        val id = "imp_" + UUID.randomUUID().toString().take(8)
        val s = ImportSession(id, ImportStatus.RUNNING, mode, providers.map { ProviderImportProgress(it, ProviderImportStatus.PENDING, 0) })
        sessions.update { it + (id to s) }
        jobs[id] = scope.launch { simulate(id, providers) }
        ApiResult.Success(s)
    }

    private suspend fun simulate(id: String, list: List<ProviderKey>) {
        val pool = fixtures.load<OrderPage>("history/orders.json").orders
        for (p in list) {
            edit(id, p) { it.copy(status = ProviderImportStatus.RUNNING) }
            val found = pool.count { it.provider == p }.coerceAtLeast(3)
            for (k in 1..found) {
                mock.latency(0.35)
                edit(id, p) { it.copy(ordersFound = k) }
            }
            val fail = cfg.importFlow == ImportScenario.ONE_PROVIDER_FAILS && p == list.last() && list.size > 1
            edit(id, p) {
                if (fail) it.copy(status = ProviderImportStatus.FAILED, ordersFound = 0, message = "${p.displayName} order history screen changed. Skipped safely.")
                else it.copy(status = ProviderImportStatus.COMPLETED, confidence = Confidence.HIGH)
            }
        }
        val okProviders = sessions.value[id]?.providers.orEmpty().filter { it.status == ProviderImportStatus.COMPLETED }.map { it.provider }.toSet()
        sessions.update { m ->
            val s = m[id] ?: return@update m
            m + (id to s.copy(status = ImportStatus.AWAITING_REVIEW, reviewSample = pool.filter { it.provider in okProviders }.take(5)))
        }
    }

    private fun edit(id: String, p: ProviderKey, f: (ProviderImportProgress) -> ProviderImportProgress) {
        sessions.update { m ->
            val s = m[id] ?: return@update m
            m + (id to s.copy(providers = s.providers.map { if (it.provider == p) f(it) else it }))
        }
    }

    override fun observe(sessionId: String): Flow<ImportSession?> = sessions.map { it[sessionId] }

    override suspend fun cancel(sessionId: String) {
        jobs.remove(sessionId)?.cancel()
        sessions.update { m -> m[sessionId]?.let { m + (sessionId to it.copy(status = ImportStatus.CANCELLED)) } ?: m }
    }

    override suspend fun confirmReview(sessionId: String, excludedOrderIds: Set<String>): ApiResult<ImportSession> = call(0.6) {
        val s = sessions.value[sessionId] ?: return@call clientError(ErrorCode.NOT_FOUND, "Import not found.")
        val done = s.copy(status = ImportStatus.COMPLETED)
        sessions.update { it + (sessionId to done) }
        orders = orders.filterNot { it.orderId in excludedOrderIds }
        ApiResult.Success(done)
    }

    override suspend fun orders(provider: ProviderKey?): ApiResult<List<Order>> = call {
        when (cfg.history) {
            ListScenario.ERROR -> serviceError()
            ListScenario.EMPTY -> ApiResult.Success(emptyList())
            ListScenario.SUCCESS -> ApiResult.Success(orders.filter { provider == null || it.provider == provider })
        }
    }

    override suspend fun deleteOrder(orderId: String): ApiResult<Unit> = call(0.3) {
        orders = orders.filterNot { it.orderId == orderId }
        ApiResult.Success(Unit)
    }
}
