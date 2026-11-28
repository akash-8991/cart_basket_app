package com.smartcart.data.http

import com.smartcart.data.ApiResult
import com.smartcart.data.ComparisonRepository
import com.smartcart.data.map
import com.smartcart.data.device.DeviceReadingSink
import com.smartcart.model.ComparisonQuote
import com.smartcart.model.ComparisonStatus
import com.smartcart.model.ConfirmQuoteRequest
import com.smartcart.model.PriceObservation
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteConfirmation
import com.smartcart.model.QuoteRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Comparisons worked out by the server's engine (Phase 4.4). A quote that is still in progress — for example
 * while the phone reads a provider (CR-005) — is polled until it settles; the server's own deadline for the
 * phone's reading guarantees it does.
 */
class HttpComparisonRepository(
    private val api: ApiClient,
    private val pollMillis: Long = 1_000,
    private val maxPolls: Int = 240,
) : ComparisonRepository, DeviceReadingSink {
    private val quotes = ConcurrentHashMap<String, ComparisonQuote>()

    override suspend fun create(request: QuoteRequest): ApiResult<String> =
        api.call("POST", "/v1/comparison/quotes", ComparisonQuote.serializer(), encode(QuoteRequest.serializer(), request), idempotent = true)
            .map { q -> quotes[q.quoteId] = q; q.quoteId }

    override fun observe(quoteId: String): Flow<ApiResult<ComparisonQuote>> = flow {
        var q = quotes[quoteId]?.also { emit(ApiResult.Success(it)) }
        var polls = 0
        while (q == null || q.status == ComparisonStatus.IN_PROGRESS) {
            if (q != null) {
                if (++polls > maxPolls) return@flow
                delay(pollMillis)
            }
            when (val r = api.call("GET", "/v1/comparison/quotes/$quoteId", ComparisonQuote.serializer())) {
                is ApiResult.Success -> { q = r.value; quotes[quoteId] = r.value; emit(r) }
                is ApiResult.Failure -> { emit(r); return@flow }
            }
        }
    }

    override fun cached(quoteId: String): ComparisonQuote? = quotes[quoteId]

    override suspend fun confirm(quoteId: String, provider: ProviderKey, acknowledgedSwitch: Boolean): ApiResult<QuoteConfirmation> =
        api.call("POST", "/v1/comparison/quotes/$quoteId/confirm", QuoteConfirmation.serializer(), encode(ConfirmQuoteRequest.serializer(), ConfirmQuoteRequest(provider, acknowledgedSwitch)), idempotent = true)

    override suspend fun submitObservation(quoteId: String, observation: PriceObservation): ApiResult<ComparisonQuote> =
        api.call("POST", "/v1/comparison/quotes/$quoteId/observations", ComparisonQuote.serializer(), encode(PriceObservation.serializer(), observation), idempotent = true)
            .map { q -> quotes[quoteId] = q; q }
}
