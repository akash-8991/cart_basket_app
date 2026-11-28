package com.smartcart.data.device

import com.smartcart.automation.PriceCapture
import com.smartcart.automation.RunnerTiming
import com.smartcart.data.AccountRepository
import com.smartcart.data.ApiResult
import com.smartcart.data.ComparisonRepository
import com.smartcart.data.ProviderRepository
import com.smartcart.model.Availability
import com.smartcart.model.ComparisonQuote
import com.smartcart.model.LineMatch
import com.smartcart.model.MatchedLine
import com.smartcart.model.NotReadReason
import com.smartcart.model.ComparisonStatus
import com.smartcart.model.CostComponents
import com.smartcart.model.ProviderKey
import com.smartcart.model.ProviderStatus
import com.smartcart.model.QuoteConfirmation
import com.smartcart.model.QuoteRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Comparisons whose prices are read on the phone (Phase 4.3). For a provider with an adapter bound, the
 * phone opens the app, looks every item up (read-only), reads the stated fees, and that provider's column is
 * replaced with what was read; every other provider comes from [base] as before. The check runs in the
 * background as soon as the comparison starts, and the column shows its progress meanwhile.
 *
 * Captured prices stay on the phone until CR-005 gives the device a way to report them.
 */
class DeviceComparisonRepository(
    private val base: ComparisonRepository,
    private val device: OnDeviceAutomation,
    private val providers: ProviderRepository,
    private val account: AccountRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val timing: RunnerTiming = RunnerTiming(),
) : ComparisonRepository {

    /**
     * One provider's on-device check for one quote. Without a backend, [result] is the column worked out here; with
     * one, [done] is set once the reading has been sent and the server works the column out (Phase 4.4).
     */
    private data class Check(
        val provider: ProviderKey,
        val appName: String,
        val read: Int,
        val total: Int,
        val result: com.smartcart.model.ProviderResult? = null,
        val done: Boolean = false,
    )

    private val checks = ConcurrentHashMap<String, MutableStateFlow<Check>>()
    private val finished = ConcurrentHashMap<String, ComparisonQuote>()

    override suspend fun create(request: QuoteRequest): ApiResult<String> {
        // One bound provider per comparison: the phone can only drive one app at a time.
        val sink = base as? DeviceReadingSink
        val bound = request.eligibleProviders.firstOrNull { device.adapterFor(it) != null }
        if (sink != null && bound != null) return createOnServer(sink, request, bound)
        val r = base.create(request)
        if (r !is ApiResult.Success) return r
        val quoteId = r.value
        val quote = base.cached(quoteId) ?: return r
        // One bound provider per comparison: the phone can only drive one app at a time.
        val target = quote.providerResults.firstOrNull {
            (request.eligibleProviders.isEmpty() || it.provider in request.eligibleProviders) && device.adapterFor(it.provider) != null
        } ?: return r
        val adapter = device.adapterFor(target.provider)!!
        val items = target.lines.map(CapturedQuote::item)
        val state = MutableStateFlow(Check(target.provider, adapter.appName, 0, items.size))
        checks[quoteId] = state
        scope.launch {
            val now = clock.instant().toString()
            val driver = device.driver()
            val result = when {
                items.isEmpty() -> CapturedQuote.failed(target.provider, target.lines, "Nothing to look up in ${adapter.appName}", now)
                killed(target.provider) -> CapturedQuote.failed(target.provider, target.lines, "Automation for ${target.provider.displayName} is paused", now)
                // Reading an app's screens is part of what the person allows for that app (automation consent).
                !consented(target.provider) -> CapturedQuote.failed(target.provider, target.lines, "Allow automation for ${target.provider.displayName} to read its prices on this phone", now)
                driver == null -> CapturedQuote.failed(target.provider, target.lines, "Turn on SmartCart under Settings → Accessibility to read prices in ${adapter.appName}", now)
                else -> {
                    val capture = PriceCapture(driver, device.verifier, adapter, killSwitch = { killed(target.provider) }, timing = timing)
                    val outcome = capture.run(items) { read, total -> state.value = state.value.copy(read = read, total = total) }
                    device.returnToSmartCart()
                    val at = clock.instant().toString()
                    when (outcome) {
                        is PriceCapture.Result.Read -> CapturedQuote.result(target.provider, adapter.appName, target.lines, outcome.offers, outcome.fees, at, quote.currency)
                        is PriceCapture.Result.NotRead -> CapturedQuote.failed(target.provider, target.lines, "Couldn't read ${adapter.appName}: ${outcome.reason}", at)
                    }
                }
            }
            finished[quoteId] = CapturedQuote.merge(base.cached(quoteId) ?: quote, result)
            state.value = state.value.copy(result = result)
        }
        return r
    }

    /**
     * With a backend (CR-005): the server is told which provider the phone reads, the phone reads it, and the
     * reading — not a worked-out column — is sent for the server's engine to use. If anything stops the phone
     * from reading, it says why; if the reading never arrives, the server's deadline marks the provider unchecked.
     */
    private suspend fun createOnServer(sink: DeviceReadingSink, request: QuoteRequest, provider: ProviderKey): ApiResult<String> {
        val r = base.create(request.copy(deviceProviders = listOf(provider)))
        if (r !is ApiResult.Success) return r
        val quoteId = r.value
        val adapter = device.adapterFor(provider)!!
        val lines = request.cartLines.map { MatchedLine(lineId = it.lineId, requestedName = it.label, matchedName = it.name, matchedSize = it.size, quantity = it.quantity, match = LineMatch.EXACT) }
            .ifEmpty { base.cached(quoteId)?.providerResults?.firstOrNull { it.lines.isNotEmpty() }?.lines.orEmpty() }
        val items = lines.map(CapturedQuote::item)
        val state = MutableStateFlow(Check(provider, adapter.appName, 0, items.size))
        checks[quoteId] = state
        scope.launch {
            fun at() = clock.instant().toString()
            val driver = device.driver()
            val observation = when {
                items.isEmpty() -> DeviceReports.notRead(provider, NotReadReason.STOPPED, adapter, at())
                killed(provider) -> DeviceReports.notRead(provider, NotReadReason.KILL_SWITCH, adapter, at())
                !consented(provider) -> DeviceReports.notRead(provider, NotReadReason.CONSENT_MISSING, adapter, at())
                driver == null -> DeviceReports.notRead(provider, NotReadReason.SERVICE_OFF, adapter, at())
                else -> {
                    val capture = PriceCapture(driver, device.verifier, adapter, killSwitch = { killed(provider) }, timing = timing)
                    val outcome = capture.run(items) { read, total -> state.value = state.value.copy(read = read, total = total) }
                    device.returnToSmartCart()
                    DeviceReports.observation(provider, lines, outcome, adapter, at(), base.cached(quoteId)?.currency ?: "INR")
                }
            }
            sink.submitObservation(quoteId, observation)
            state.value = state.value.copy(done = true)
        }
        return r
    }

    private fun consented(provider: ProviderKey) = providers.providers.value.firstOrNull { it.key == provider }?.consent?.automation == true

    private fun killed(provider: ProviderKey) =
        account.automationPaused.value || providers.providers.value.firstOrNull { it.key == provider }?.status == ProviderStatus.KILL_SWITCHED

    /**
     * The base comparison's progress with the on-device column laid over it. It stays "in progress" until both
     * the base comparison and the phone's check have finished, then emits the merged quote and completes.
     */
    override fun observe(quoteId: String): Flow<ApiResult<ComparisonQuote>> {
        val check = checks[quoteId] ?: return base.observe(quoteId)
        if (base is DeviceReadingSink) return observeOnServer(quoteId, check)
        return channelFlow {
            var latest: ApiResult<ComparisonQuote>? = null
            var last: ApiResult<ComparisonQuote>? = null
            val lock = Any()
            // Both watchers call this; the merged quote is sent once per change, and the final one only once.
            fun emitMerged() = synchronized(lock) {
                val b = latest ?: return@synchronized
                val c = check.value
                val next = when {
                    b !is ApiResult.Success -> b
                    c.result != null && b.value.status != ComparisonStatus.IN_PROGRESS -> ApiResult.Success(finished.getValue(quoteId))
                    else -> ApiResult.Success(overlay(b.value, c))
                }
                if (next != last) { last = next; trySend(next) }
            }
            launch {
                base.observe(quoteId).collect { latest = it; emitMerged() }
                // The base comparison is done; wait for the phone if it's still reading.
                check.first { it.result != null }
                emitMerged()
            }
            launch {
                check.transformWhile { emit(it); it.result == null }.collect { emitMerged() }
            }
        }
    }

    /** The server's quote, with the phone's progress shown in its column until the reading has been sent. */
    private fun observeOnServer(quoteId: String, check: MutableStateFlow<Check>): Flow<ApiResult<ComparisonQuote>> = channelFlow {
        var latest: ApiResult<ComparisonQuote>? = null
        var last: ApiResult<ComparisonQuote>? = null
        val lock = Any()
        fun emitLatest() = synchronized(lock) {
            val b = latest ?: return@synchronized
            val c = check.value
            val next = if (b is ApiResult.Success && b.value.status == ComparisonStatus.IN_PROGRESS && !c.done) ApiResult.Success(overlay(b.value, c)) else b
            if (next != last) { last = next; trySend(next) }
        }
        val watch = launch { check.collect { emitLatest() } }
        base.observe(quoteId).collect { latest = it; emitLatest() }
        watch.cancel()
    }

    /** The base quote while the phone is still reading: that provider's column shows how far it has got. */
    private fun overlay(q: ComparisonQuote, c: Check): ComparisonQuote = q.copy(
        status = ComparisonStatus.IN_PROGRESS,
        providerResults = q.providerResults.map { r ->
            if (r.provider != c.provider) r
            else c.result ?: r.copy(
                availability = Availability.NOT_CHECKED, lines = emptyList(), components = CostComponents(), totalEstimate = null, rank = null,
                notes = listOf("Reading prices in ${c.appName}: ${c.read} of ${c.total} items"),
            )
        },
    )

    override fun cached(quoteId: String): ComparisonQuote? = finished[quoteId] ?: base.cached(quoteId)

    override suspend fun confirm(quoteId: String, provider: ProviderKey, acknowledgedSwitch: Boolean): ApiResult<QuoteConfirmation> =
        base.confirm(quoteId, provider, acknowledgedSwitch)
}
