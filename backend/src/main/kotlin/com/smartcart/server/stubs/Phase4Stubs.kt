package com.smartcart.server.stubs

import com.smartcart.server.comparison.ComparisonEngine
import com.smartcart.server.comparison.EngineInput
import com.smartcart.server.comparison.Fees
import com.smartcart.server.comparison.Offer
import com.smartcart.server.comparison.Reading
import com.smartcart.server.comparison.RequestedLine
import com.smartcart.server.comparison.SampleCatalog
import com.smartcart.server.flags.Flags
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ApiJson
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.me.MeService
import com.smartcart.server.providers.ProviderRegistry
import com.smartcart.server.providers.ProviderService
import com.smartcart.server.security.RateLimiter
import com.smartcart.server.security.Secrets
import com.smartcart.server.tasks.CartTasks
import com.smartcart.server.tasks.HandoffTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixture-backed stand-ins for the Phase 4 endpoints (D-020), so the app can be integrated end to end
 * before adapters and the comparison engine exist. The data is canned; the rules are not: ownership,
 * consent, kill switches, full-plan approval and terminal task states are enforced exactly as Phase 4
 * must enforce them. Development only — production answers service_unavailable.
 */
class Phase4Stubs(
    private val flags: Flags,
    private val providers: ProviderService,
    private val me: MeService,
    private val limiter: RateLimiter,
    private val clock: Clock,
    secrets: Secrets,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val store = ConcurrentHashMap<String, JsonObject>()          // "<kind>:<user>:<id>" → object
    private val confirmations = ConcurrentHashMap<String, String>()      // "<user>:<quote>" → provider
    private val deletedOrders = ConcurrentHashMap.newKeySet<String>()    // "<user>:<order>"

    /** A quote's inputs (4.4): the engine recomputes the quote from them each time it is read. */
    private class QuoteState(@Volatile var input: EngineInput, val device: Set<String>, val deadline: Instant) {
        val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val quotes = ConcurrentHashMap<String, QuoteState>()          // "<user>:<quote>"
    private val tasks = CartTasks(flags, providers, me, limiter, clock, HandoffTokens(secrets, clock))
    private val history = com.smartcart.server.history.HistoryImports(flags, me, limiter, clock, secrets)

    private fun fixture(path: String): JsonObject =
        json.parseToJsonElement(Phase4Stubs::class.java.getResourceAsStream("/contracts/fixtures/$path")!!.readAllBytes().decodeToString()).jsonObject

    private fun newId(prefix: String) = prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also(random::nextBytes))

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    /** Re-dates a fixture: `created_at` becomes now and `expires_at` keeps its original distance from it. */
    private fun JsonObject.redated(): JsonObject {
        val created = this["created_at"]?.jsonPrimitive?.content?.let(Instant::parse) ?: return this
        val ttl = this["expires_at"]?.jsonPrimitive?.content?.let { Duration.between(created, Instant.parse(it)) }
        return with("created_at", now().toString()).let { o -> ttl?.let { o.with("expires_at", now().plus(it).toString()) } ?: o }
    }

    private fun JsonObject.with(key: String, value: Any?): JsonObject = JsonObject(toMutableMap().apply {
        put(key, when (value) { null -> JsonNull; is JsonElement -> value; is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) })
    })

    private fun JsonObject.str(key: String) = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun own(kind: String, userId: String, id: String): JsonObject = store["$kind:$userId:$id"] ?: throw ApiException(ErrorCode.NOT_FOUND)

    private fun save(kind: String, userId: String, id: String, obj: JsonObject) = obj.also { store["$kind:$userId:$id"] = it }

    private fun limit(key: String, max: Int, window: Duration) {
        val d = limiter.hit(key, max, window)
        if (!d.allowed) throw ApiException.limited(ErrorCode.RATE_LIMITED, d)
    }

    // ------------------------------------------------------------------ comparison

    /**
     * Phase 4.4: a comparison worked out by [ComparisonEngine] from readings, not a canned fixture. Providers the
     * phone reads (CR-005) wait for its reading; in development the others are read from [SampleCatalog].
     */
    fun createQuote(
        userId: String,
        category: String,
        selectedProvider: String?,
        objective: String,
        deliveryLabel: String? = null,
        deviceProviders: List<String> = emptyList(),
        eligibleProviders: List<String> = emptyList(),
        lines: List<RequestedLine> = emptyList(),
    ): JsonObject {
        if (!flags.isOn("comparison.enabled")) throw ApiException(ErrorCode.SERVICE_UNAVAILABLE)
        limit("stub:quotes:$userId", 20, Duration.ofMinutes(1))
        val id = newId("qt")
        val created = now()
        val cart = lines.ifEmpty { SampleCatalog.sampleLines(category) }
        val manual = category == "shopping"
        val readings = LinkedHashMap<String, Reading>()
        for (p in eligibleProviders.distinct()) {
            readings[p] = when {
                manual -> Reading.NotRead("not_checked", "Compare this one yourself in the app.", null)
                p in deviceProviders -> Reading.Waiting("Waiting for prices from your phone", "device")
                else -> SampleCatalog.read(p, cart, created) ?: Reading.NotRead("failed", "${displayName(p)} couldn't be checked.", "server")
            }
        }
        val input = EngineInput(
            quoteId = id, category = category, currency = "INR", objective = objective, selectedProvider = selectedProvider,
            createdAt = created, expiresAt = created.plus(QUOTE_TTL), assumptions = assumptions(category, deliveryLabel), lines = cart,
            readings = readings, providerNames = readings.keys.associateWith(::displayName), manualOnly = manual,
        )
        quotes["$userId:$id"] = QuoteState(input, deviceProviders.toSet(), created.plus(DEVICE_WINDOW))
        return save("quote", userId, id, ComparisonEngine.compute(input, clock.instant()))
    }

    private fun displayName(p: String) = ProviderRegistry.find(p)?.displayName ?: p

    /** The sample comparison's assumptions; CR-004: the delivery one names the address the user chose, by label only. */
    private fun assumptions(category: String, deliveryLabel: String?): List<String> {
        val file = when (category) { "food" -> "comparison/food.complete.json"; "travel" -> "comparison/travel.complete.json"; else -> "comparison/grocery.complete.json" }
        return fixture(file)["assumptions"]!!.jsonArray.map { it.jsonPrimitive.content }.map { a ->
            if (deliveryLabel != null && a.startsWith("Delivery to")) "Delivery to your saved address \"$deliveryLabel\"" else a
        }
    }

    /** CR-005: what the phone read for one provider. The engine works out every figure from it. */
    fun submitObservation(userId: String, quoteId: String, obs: PriceObservation): JsonObject {
        val q = quote(userId, quoteId)
        if (!Instant.parse(q.str("expires_at")!!).isAfter(clock.instant())) throw ApiException(ErrorCode.QUOTE_EXPIRED)
        limit("stub:observations:$userId", 30, Duration.ofMinutes(1))
        val st = quotes["$userId:$quoteId"] ?: throw ApiException(ErrorCode.CONFLICT)
        synchronized(st) {
            // Only a provider the app said the phone would read, and only once.
            if (obs.provider !in st.device || obs.provider in st.reported) throw ApiException(ErrorCode.CONFLICT)
            val currency = st.input.currency
            val lineIds = st.input.lines.map { it.lineId }.toSet()
            if (obs.offers.any { it.lineId !in lineIds }) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "offers", "invalid_value")
            val amounts = obs.offers.mapNotNull { it.unitPrice } + obs.fees?.named()?.mapNotNull { it.second }.orEmpty()
            if (amounts.any { it.currency != currency }) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "currency", "invalid_value")
            val captured = Instant.parse(obs.capturedAt)
            if (captured.isAfter(clock.instant().plus(CLOCK_SKEW)) || captured.isBefore(st.input.createdAt.minus(CLOCK_SKEW))) {
                throw ApiException.field(ErrorCode.VALIDATION_FAILED, "captured_at", "invalid_value")
            }
            val reading = if (obs.outcome == "read") {
                val named = (obs.fees ?: ObservedFees()).named()
                Reading.Read(
                    capturedAt = captured,
                    offers = obs.offers.map { Offer(it.lineId, it.found, it.name, null, it.unitPrice?.amountMinor, it.inStock) },
                    // A fee the app didn't state is unknown, never zero (surge and discounts simply don't apply).
                    fees = Fees(named.mapNotNull { (k, v) -> v?.let { k to it.amountMinor } }.toMap(), named.filter { (k, v) -> v == null && k !in Fees.OPTIONAL }.map { it.first }.toSet()),
                    etaMinutes = obs.etaMinutes,
                    source = "device",
                    notes = listOf("Prices read on your phone"),
                )
            } else Reading.NotRead("failed", DeviceReadings.notReadNote(obs.notReadReason, displayName(obs.provider)), "device")
            st.reported += obs.provider
            st.input = st.input.copy(readings = LinkedHashMap(st.input.readings).apply { put(obs.provider, reading) })
            return save("quote", userId, quoteId, ComparisonEngine.compute(st.input, clock.instant()))
        }
    }

    /** The quote as it stands now; a provider whose reading hasn't arrived by the deadline counts as not checked. */
    fun quote(userId: String, quoteId: String): JsonObject {
        val stored = own("quote", userId, quoteId)
        val st = quotes["$userId:$quoteId"] ?: return stored
        synchronized(st) {
            val late = st.device - st.reported
            if (late.isNotEmpty() && !clock.instant().isBefore(st.deadline)) {
                st.reported += late
                st.input = st.input.copy(readings = LinkedHashMap(st.input.readings).apply {
                    late.forEach { put(it, Reading.NotRead("failed", "Your phone didn't report prices in time", "device")) }
                })
            }
            return save("quote", userId, quoteId, ComparisonEngine.compute(st.input, clock.instant()))
        }
    }

    fun confirmQuote(userId: String, quoteId: String, provider: String, acknowledgedSwitch: Boolean): JsonObject {
        val q = quote(userId, quoteId)
        if (!Instant.parse(q.str("expires_at")!!).isAfter(clock.instant())) throw ApiException(ErrorCode.QUOTE_EXPIRED)
        val offered = q["provider_results"]!!.jsonArray.map { it.jsonObject.str("provider") }
        if (provider !in offered) throw ApiException(ErrorCode.CONFLICT)
        // Switching away from the app the user picked needs an explicit acknowledgement (no pick, no switch).
        val picked = q.str("selected_provider")
        if (picked != null && provider != picked && !acknowledgedSwitch) throw ApiException(ErrorCode.CONFLICT)
        confirmations["$userId:$quoteId"] = provider
        return JsonObject(mapOf("quote_id" to JsonPrimitive(quoteId), "confirmed_provider" to JsonPrimitive(provider), "confirmed_at" to JsonPrimitive(now().toString())))
    }

    // ------------------------------------------------------------------ cart tasks (Phase 4.5: CartTasks)

    /** A task on a snapshot the person confirmed for [provider]; the phone runs it if the phone read that provider. */
    fun createTask(userId: String, quoteId: String, provider: String, taskType: String): JsonObject {
        val q = quote(userId, quoteId)
        if (confirmations["$userId:$quoteId"] != provider) throw ApiException(ErrorCode.CONFLICT)
        val phoneRuns = quotes["$userId:$quoteId"]?.device?.contains(provider) == true
        return tasks.create(userId, q, provider, taskType, phoneRuns)
    }

    fun task(userId: String, taskId: String) = tasks.get(userId, taskId)

    fun approve(userId: String, taskId: String, actionIds: List<String>, nonce: String, sessionId: String = "") =
        tasks.approve(userId, sessionId, taskId, actionIds, nonce)

    fun checkpoint(userId: String, taskId: String, checkpointId: String, decision: String) = tasks.checkpoint(userId, taskId, checkpointId, decision)

    fun reportProgress(userId: String, sessionId: String, taskId: String, r: TaskProgressReport) = tasks.report(userId, sessionId, taskId, r)

    fun stop(userId: String, taskId: String, reason: String = "user_cancel") = tasks.stop(userId, taskId, reason)

    // ------------------------------------------------------------------ handoff

    fun handoff(userId: String, taskId: String) = tasks.handoff(userId, taskId)

    fun redeemHandoff(userId: String, token: String, packageName: String?) = tasks.redeem(userId, token, packageName)

    // ------------------------------------------------------------------ history

    // CR-007 (Phase 4.6): real import sessions; orders arrive only as the phone's reviewed, consented batches.
    fun startImport(userId: String, providerKeys: List<String>, fields: List<String> = listOf("order_date", "items", "quantity", "amounts", "status"), uploadMode: String = "device_only") =
        history.start(userId, providerKeys, fields, uploadMode)

    fun importSession(userId: String, sessionId: String) = history.session(userId, sessionId)

    fun historyBatch(userId: String, sessionId: String, b: com.smartcart.server.history.HistoryBatch) = history.batch(userId, sessionId, b)

    /** The person's uploaded orders; until they have any, the sample orders (development). */
    fun orders(userId: String, provider: String?): JsonObject {
        val all = history.uploaded(userId) ?: fixture("history/orders.json")["orders"]!!.jsonArray.map { it.jsonObject }
        val visible = all.filter { o -> "$userId:${o.str("order_id")}" !in deletedOrders && (provider == null || o.str("provider") == provider) }
        return JsonObject(mapOf("orders" to JsonArray(visible), "next_cursor" to JsonNull))
    }

    fun deleteOrder(userId: String, orderId: String) {
        if (history.delete(userId, orderId)) return
        val exists = fixture("history/orders.json")["orders"]!!.jsonArray.any { it.jsonObject.str("order_id") == orderId }
        if (!exists || !deletedOrders.add("$userId:$orderId")) throw ApiException(ErrorCode.NOT_FOUND)
    }

    // ------------------------------------------------------------------ dashboard & recommendations

    fun dashboard(): JsonObject = fixture("dashboard/summary.success.json")

    fun monthly(): JsonObject = fixture("dashboard/monthly.json")

    fun recommendations(category: String?): JsonObject {
        val list = fixture("recommendations/list.json")
        if (category == null) return list
        return list.with("recommendations", JsonArray(list["recommendations"]!!.jsonArray.filter { it.jsonObject.str("category") == category }))
    }

    private companion object {
        val TERMINAL = setOf("CART_READY", "HANDED_OFF", "CANCELLED", "EXPIRED", "FAILED_SAFE", "BLOCKED_POLICY")

        val QUOTE_TTL: Duration = Duration.ofMinutes(5)

        /** CR-005: how long the phone has to report a provider's prices. */
        val DEVICE_WINDOW: Duration = Duration.ofSeconds(120)
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)

        /** The D-002 transitions the phone may make (a report may also stay in the same state). */
        val DEVICE_MOVES = mapOf(
            "APPROVED" to setOf("RUNNING", "CANCELLED", "BLOCKED_POLICY"),
            "RUNNING" to setOf("CHECKPOINT", "NEEDS_USER_ACTION", "CART_READY", "CANCELLED", "FAILED_SAFE", "BLOCKED_POLICY"),
            "CHECKPOINT" to setOf("RUNNING", "CANCELLED", "FAILED_SAFE"),
            "NEEDS_USER_ACTION" to setOf("RUNNING", "CANCELLED", "FAILED_SAFE", "BLOCKED_POLICY"),
        )
    }
}

