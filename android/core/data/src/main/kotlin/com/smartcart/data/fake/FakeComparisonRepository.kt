package com.smartcart.data.fake

import com.smartcart.data.ApiResult
import com.smartcart.data.ComparisonRepository
import com.smartcart.data.Fixtures
import com.smartcart.data.mock.ComparisonScenario
import com.smartcart.data.mock.MockControl
import com.smartcart.model.Availability
import com.smartcart.model.Category
import com.smartcart.model.ComparisonQuote
import com.smartcart.model.ComparisonStatus
import com.smartcart.model.CostComponents
import com.smartcart.model.ErrorCode
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteConfirmation
import com.smartcart.model.QuoteRequest
import com.smartcart.data.clientError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Stands in for the deterministic comparison engine. It never computes totals itself: every
 * figure comes from a validated contract fixture.
 */
class FakeComparisonRepository(fixtures: Fixtures, mock: MockControl) : FakeBase(fixtures, mock), ComparisonRepository {
    private val quotes = ConcurrentHashMap<String, ComparisonQuote>()

    init {
        // Recent comparisons on the home screen link to these IDs.
        listOf("comparison/grocery.complete.json", "comparison/travel.complete.json").forEach {
            val q = fixtures.load<ComparisonQuote>(it)
            quotes[q.quoteId] = q
        }
    }

    override suspend fun create(request: QuoteRequest): ApiResult<String> = call(0.4) {
        val path = when (cfg.comparison) {
            ComparisonScenario.ERROR -> return@call serviceError()
            ComparisonScenario.PARTIAL_COVERAGE -> "comparison/grocery.partial-coverage.json"
            ComparisonScenario.STALE -> "comparison/grocery.stale.json"
            ComparisonScenario.INCOMPARABLE -> "comparison/grocery.incomparable.json"
            ComparisonScenario.UNAVAILABLE -> "comparison/grocery.unavailable.json"
            ComparisonScenario.PRICE_CHANGED -> "comparison/grocery.price-changed.json"
            ComparisonScenario.MANUAL_ONLY -> "comparison/food.manual-only.json"
            ComparisonScenario.AUTO -> when (request.category) {
                Category.GROCERY -> "comparison/grocery.complete.json"
                Category.FOOD -> "comparison/food.complete.json"
                Category.TRAVEL -> "comparison/travel.complete.json"
                Category.SHOPPING -> "comparison/food.manual-only.json"
            }
        }
        var q = fixtures.load<ComparisonQuote>(path)
        if (request.category == Category.SHOPPING) {
            q = q.copy(
                category = Category.SHOPPING,
                providerResults = q.providerResults.zip(listOf(ProviderKey.AMAZON, ProviderKey.FLIPKART)) { r, p -> r.copy(provider = p) },
                coverage = q.coverage.copy(checked = emptyList()),
            )
        }
        // CR-004: the backend names the chosen address label in the assumptions; the fake does the same.
        request.deliveryLabel?.let { label ->
            q = q.copy(assumptions = q.assumptions.map { if (it.startsWith("Delivery to")) "Delivery to your saved address \"$label\"" else it })
        }
        val id = q.quoteId + "_" + Instant.now().toEpochMilli()
        quotes[id] = q.copy(quoteId = id)
        ApiResult.Success(id)
    }

    override fun observe(quoteId: String): Flow<ApiResult<ComparisonQuote>> = flow {
        val final = quotes[quoteId] ?: run {
            emit(clientError(ErrorCode.NOT_FOUND, "Comparison not found."))
            return@flow
        }
        if (cfg.offline) {
            emit(clientError(ErrorCode.OFFLINE, "You're offline."))
            return@flow
        }
        // Progress: providers flip from "checking" to their result one by one.
        val checking = final.copy(
            status = ComparisonStatus.IN_PROGRESS,
            providerResults = final.providerResults.map {
                it.copy(availability = Availability.NOT_CHECKED, lines = emptyList(), components = CostComponents(), totalEstimate = null, rank = null, notes = listOf("Checking…"))
            },
        )
        emit(ApiResult.Success(checking))
        if (final.status != ComparisonStatus.MANUAL_ONLY) {
            for (i in final.providerResults.indices) {
                mock.latency(0.8)
                val partial = checking.copy(providerResults = checking.providerResults.mapIndexed { j, r -> if (j <= i) final.providerResults[j] else r })
                if (i < final.providerResults.lastIndex) emit(ApiResult.Success(partial))
            }
        } else {
            mock.latency()
        }
        emit(ApiResult.Success(final))
    }

    override fun cached(quoteId: String): ComparisonQuote? = quotes[quoteId]

    override suspend fun confirm(quoteId: String, provider: ProviderKey, acknowledgedSwitch: Boolean): ApiResult<QuoteConfirmation> = call(0.5) {
        val q = quotes[quoteId] ?: return@call clientError(ErrorCode.NOT_FOUND, "Comparison not found.")
        if (q.needsSwitchConfirmation(provider) && !acknowledgedSwitch) {
            return@call clientError(ErrorCode.CONFLICT, "Confirm the switch to ${provider.displayName} first.")
        }
        ApiResult.Success(QuoteConfirmation(quoteId, provider, Instant.now().toString()))
    }
}
