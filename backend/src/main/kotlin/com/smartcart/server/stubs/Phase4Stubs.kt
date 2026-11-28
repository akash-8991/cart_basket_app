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

    /** CR-005: a task the phone runs; only the session that approved it may report on it. */
    private class DeviceTask {
        var approvedBy: String? = null
        var approvedAt: Instant? = null
        var pausedSeconds = 0L
        var pausedSince: Instant? = null
        var lastSeq = 0
        var lastResponse: JsonObject? = null
    }

    private val quotes = ConcurrentHashMap<String, QuoteState>()          // "<user>:<quote>"
    private val deviceTasks = ConcurrentHashMap<String, DeviceTask>()     // "<user>:<task>"

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

    // ------------------------------------------------------------------ cart tasks

    fun createTask(userId: String, quoteId: String, provider: String, taskType: String): JsonObject {
        quote(userId, quoteId)
        if (confirmations["$userId:$quoteId"] != provider) throw ApiException(ErrorCode.CONFLICT)
        // The same gates Phase 4 automation must pass (plan rules 3–4).
        if (!flags.isOn("automation.global.enabled") || !providers.isUsable(provider)) throw ApiException(ErrorCode.PROVIDER_DISABLED)
        if ("automation.$provider" !in me.grantedKeys(userId)) throw ApiException(ErrorCode.CONSENT_REQUIRED)
        limit("stub:tasks:$userId", 10, Duration.ofMinutes(10))
        val name = ProviderRegistry.find(provider)!!.displayName
        val id = newId("tsk")
        val base = fixture("cart-tasks/awaiting-approval.json").redated()
        val actions = JsonArray(base["planned_actions"]!!.jsonArray.map { a ->
            a.jsonObject.with("description", a.jsonObject.str("description")!!.replace("Blinkit", name))
        })
        // CR-002 G: a single-use nonce ties the approval to exactly this plan.
        val nonce = "apn_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
        // CR-005: if the phone read this provider's prices, the phone runs its task; it isn't simulated here.
        if (quotes["$userId:$quoteId"]?.device?.contains(provider) == true) deviceTasks["$userId:$id"] = DeviceTask()
        return save("task", userId, id, base.with("task_id", id).with("quote_id", quoteId).with("provider", provider).with("planned_actions", actions).with("approval_nonce", nonce))
    }

    /** Reading a running task advances the canned run by one step: RUNNING → CART_READY. */
    fun task(userId: String, taskId: String): JsonObject {
        val t = expireIfDue(userId, own("task", userId, taskId))
        val simulated = deviceTasks["$userId:$taskId"] == null
        return if (simulated && t.str("status") == "RUNNING") transition(userId, t, "cart-tasks/cart-ready.json") else t
    }

    fun approve(userId: String, taskId: String, actionIds: List<String>, nonce: String, sessionId: String = ""): JsonObject {
        val t = expireIfDue(userId, own("task", userId, taskId))
        if (nonce.isBlank() || nonce.length > 128) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "approval_nonce", "invalid_format")
        when (t.str("status")) {
            "AWAITING_APPROVAL" -> {}
            "EXPIRED" -> throw ApiException(ErrorCode.TASK_EXPIRED)
            else -> throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
        }
        val planned = t["planned_actions"]!!.jsonArray.map { it.jsonObject.str("action_id")!! }.toSet()
        // Every planned step is approved, or none is: partial approval is refused.
        if (actionIds.toSet() != planned) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
        // The nonce must be the one issued for this plan; it is cleared once used, so it can't be replayed.
        if (!java.security.MessageDigest.isEqual(nonce.toByteArray(), (t.str("approval_nonce") ?: "").toByteArray())) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
        val device = deviceTasks["$userId:$taskId"] ?: return transition(userId, t, "cart-tasks/running.json")
        // A device-run task stays APPROVED until the phone reports; only this session may report on it.
        synchronized(device) {
            device.approvedBy = sessionId
            device.approvedAt = clock.instant()
            return save("task", userId, taskId, t.with("status", "APPROVED").with("approval_nonce", null).withEvent("state_changed", "Approved by you"))
        }
    }

    fun checkpoint(userId: String, taskId: String, checkpointId: String, decision: String): JsonObject {
        val t = own("task", userId, taskId)
        if (t.str("status") != "CHECKPOINT" || t["checkpoint"]?.takeUnless { it is JsonNull }?.jsonObject?.str("checkpoint_id") != checkpointId) {
            throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
        }
        val device = deviceTasks["$userId:$taskId"] ?: return transition(userId, t, if (decision == "approve") "cart-tasks/running.json" else "cart-tasks/cancelled.json")
        synchronized(device) {
            device.endPause()
            val yes = decision == "approve"
            return save("task", userId, taskId, t.with("status", if (yes) "RUNNING" else "CANCELLED").with("checkpoint", null)
                .withEvent("checkpoint_answered", if (yes) "You chose to continue" else "You chose to stop"))
        }
    }

    private fun DeviceTask.endPause() {
        pausedSince?.let { pausedSeconds += Duration.between(it, clock.instant()).seconds }
        pausedSince = null
    }

    private fun JsonObject.withEvent(type: String, description: String): JsonObject {
        val events = this["events"]?.jsonArray.orEmpty()
        val seq = (events.maxOfOrNull { it.jsonObject["seq"]!!.jsonPrimitive.content.toInt() } ?: 0) + 1
        val e = JsonObject(mapOf("seq" to JsonPrimitive(seq), "at" to JsonPrimitive(now().toString()), "type" to JsonPrimitive(type), "description" to JsonPrimitive(description), "action_id" to JsonNull))
        return with("events", JsonArray(events + e))
    }

    /**
     * CR-005: what the phone did for a task it runs. Applied only along device transitions of the task state
     * machine (D-002); the server enforces the limits and checks the cart itself. When the task has already
     * ended here (stopped, paused, expired), the answer says so and the phone halts.
     */
    fun reportProgress(userId: String, sessionId: String, taskId: String, r: TaskProgressReport): JsonObject {
        val t = own("task", userId, taskId)
        val device = deviceTasks["$userId:$taskId"] ?: throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
        synchronized(device) {
            val approver = device.approvedBy ?: throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            if (approver != sessionId) throw ApiException(ErrorCode.FORBIDDEN)
            limit("stub:progress:$userId:$taskId", 120, Duration.ofMinutes(1))
            if (r.reportSeq == device.lastSeq) return device.lastResponse!!
            if (r.reportSeq < device.lastSeq) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            val current = t.str("status")!!
            if (current in TERMINAL) return t.also { device.lastSeq = r.reportSeq; device.lastResponse = it }

            val events = t["events"]!!.jsonArray
            val next = (events.maxOfOrNull { it.jsonObject["seq"]!!.jsonPrimitive.content.toInt() } ?: 0) + 1
            if (r.events.withIndex().any { (i, e) -> e.seq != next + i }) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "events", "invalid_value")
            if (r.actionsUsed < (t["actions_used"]?.jsonPrimitive?.content?.toInt() ?: 0)) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "actions_used", "invalid_value")
            if (r.status != current && r.status !in DEVICE_MOVES[current].orEmpty()) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            when (r.status) {
                "CHECKPOINT" -> r.checkpoint ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "checkpoint", "required")
                "NEEDS_USER_ACTION" -> r.needsUserAction ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "needs_user_action", "required")
                "FAILED_SAFE", "BLOCKED_POLICY" -> r.failure ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "failure", "required")
                "CART_READY" -> r.cart ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "cart", "required")
            }

            val paused = setOf("CHECKPOINT", "NEEDS_USER_ACTION")
            if (current in paused && r.status !in paused) device.endPause()
            if (current !in paused && r.status in paused) device.pausedSince = clock.instant()

            val limits = t["limits"]!!.jsonObject
            val maxActions = limits["max_actions"]!!.jsonPrimitive.content.toInt()
            val runtimeEnds = device.approvedAt!!.plusSeconds(limits["max_runtime_seconds"]!!.jsonPrimitive.content.toLong() + device.pausedSeconds)
            var status = r.status
            var failure: JsonElement = r.failure?.let { ApiJson.encodeToJsonElement(FailureIn.serializer(), it) } ?: JsonNull
            var cart: JsonElement = t["cart"] ?: JsonNull
            var serverNote: String? = null
            fun failSafe(code: String, message: String) {
                status = "FAILED_SAFE"
                failure = JsonObject(mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message)))
                serverNote = message
            }
            when {
                r.actionsUsed > maxActions -> failSafe("max_actions_reached", "This took more steps than allowed, so it was stopped.")
                status !in paused && status !in TERMINAL && clock.instant().isAfter(runtimeEnds) -> failSafe("runtime_exceeded", "This took longer than allowed, so it was stopped.")
                status == "CART_READY" -> {
                    // The phone says the cart is ready; the server checks it against what was approved.
                    val c = r.cart!!
                    val approved = DeviceReadings.approvedItems(quote(userId, t.str("quote_id")!!), t.str("provider")!!)
                    val got = c.lines.map { l -> listOfNotNull(l.matchedName ?: l.requestedName, l.matchedSize).joinToString(" ").lowercase() to l.quantity }
                    val ok = approved.size == got.size && approved.toSet() == got.toSet()
                    val totals = c.lines.map { it.lineTotal }
                    val total = if (totals.all { it != null } && totals.isNotEmpty()) MoneyIn(totals.sumOf { it!!.amountMinor }, totals.first()!!.currency) else null
                    cart = JsonObject(
                        mapOf(
                            "verified" to JsonPrimitive(ok),
                            "verified_at" to (if (ok) JsonPrimitive(now().toString()) else JsonNull),
                            "lines" to JsonArray(c.lines.map { ApiJson.encodeToJsonElement(CartLineIn.serializer(), it) }),
                            "total_estimate" to (total?.let { ApiJson.encodeToJsonElement(MoneyIn.serializer(), it) } ?: JsonNull),
                            "differs_from_quote" to JsonPrimitive(!ok),
                        ),
                    )
                    if (!ok) failSafe("cart_verification_failed", "The cart in the app doesn't match what you approved, so it was stopped.")
                }
            }
            var updated = t.with("status", status)
                .with("actions_used", r.actionsUsed)
                .with("events", JsonArray(events + r.events.map { ApiJson.encodeToJsonElement(TaskEventIn.serializer(), it) }))
                .with("checkpoint", if (status == "CHECKPOINT") ApiJson.encodeToJsonElement(CheckpointIn.serializer(), r.checkpoint!!) else null)
                .with("needs_user_action", if (status == "NEEDS_USER_ACTION") ApiJson.encodeToJsonElement(NeedsUserIn.serializer(), r.needsUserAction!!) else null)
                .with("failure", if (status in setOf("FAILED_SAFE", "BLOCKED_POLICY")) failure else null)
                .with("cart", cart)
                .with("approval_nonce", null)
            serverNote?.let { updated = updated.withEvent("halt", it) }
            return save("task", userId, taskId, updated).also { device.lastSeq = r.reportSeq; device.lastResponse = it }
        }
    }

    /** The emergency stop always succeeds; a task that has already ended stays as it is. */
    fun stop(userId: String, taskId: String): JsonObject {
        val t = own("task", userId, taskId)
        if (t.str("status") in TERMINAL) return t
        val device = deviceTasks["$userId:$taskId"] ?: return transition(userId, t, "cart-tasks/cancelled.json")
        // A device-run task keeps its timeline; the phone learns of the stop from its next report's answer.
        synchronized(device) {
            device.endPause()
            return save("task", userId, taskId, t.with("status", "CANCELLED").with("checkpoint", null).with("needs_user_action", null).withEvent("halt", "Stopped by you"))
        }
    }

    private fun expireIfDue(userId: String, t: JsonObject): JsonObject =
        if (t.str("status") == "AWAITING_APPROVAL" && !Instant.parse(t.str("expires_at")!!).isAfter(clock.instant())) transition(userId, t, "cart-tasks/expired.json") else t

    /** Moves a task to the state of another fixture, keeping its identity, provider and plan. */
    private fun transition(userId: String, t: JsonObject, file: String): JsonObject {
        val next = fixture(file)
        val merged = JsonObject(next.toMutableMap().apply {
            for (k in listOf("task_id", "quote_id", "provider", "created_at", "expires_at", "limits", "planned_actions")) put(k, t[k]!!)
        })
        return save("task", userId, t.str("task_id")!!, merged)
    }

    // ------------------------------------------------------------------ handoff

    fun handoff(userId: String, taskId: String): JsonObject {
        val t = task(userId, taskId)
        if (t.str("status") != "CART_READY") throw ApiException(ErrorCode.CONFLICT)
        // Opaque, one-time, short-lived (threat model A-08): the URL carries no task, order or user data.
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))
        if (deviceTasks["$userId:$taskId"] != null) save("task", userId, taskId, t.with("status", "HANDED_OFF").withEvent("handoff_created", "Opened the cart in the app"))
        else transition(userId, t, "cart-tasks/handed-off.json")
        return fixture("deep-links/cart.json").with("handoff_url", "smartcart://handoff/$token").with("expires_at", now().plus(Duration.ofMinutes(10)).toString())
    }

    // ------------------------------------------------------------------ history

    fun startImport(userId: String, providerKeys: List<String>): JsonObject {
        if (!flags.isOn("history.import.enabled")) throw ApiException(ErrorCode.PROVIDER_DISABLED)
        val consents = me.grantedKeys(userId)
        if (providerKeys.any { "history.$it" !in consents }) throw ApiException(ErrorCode.CONSENT_REQUIRED)
        limit("stub:imports:$userId", 5, Duration.ofDays(1))
        val id = newId("imp")
        return save("import", userId, id, fixture("history/import.running.json").with("session_id", id))
    }

    /** Reading a running import moves it to review. */
    fun importSession(userId: String, sessionId: String): JsonObject {
        val s = own("import", userId, sessionId)
        return if (s.str("status") == "running") save("import", userId, sessionId, fixture("history/import.review.json").with("session_id", sessionId)) else s
    }

    fun orders(userId: String, provider: String?): JsonObject {
        val all = fixture("history/orders.json")["orders"]!!.jsonArray.map { it.jsonObject }
        val visible = all.filter { o -> "$userId:${o.str("order_id")}" !in deletedOrders && (provider == null || o.str("provider") == provider) }
        return JsonObject(mapOf("orders" to JsonArray(visible), "next_cursor" to JsonNull))
    }

    fun deleteOrder(userId: String, orderId: String) {
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

