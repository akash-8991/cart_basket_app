package com.smartcart.server.stubs

import com.smartcart.server.flags.Flags
import com.smartcart.server.http.ApiException
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

    fun createQuote(userId: String, category: String, selectedProvider: String?, objective: String, deliveryLabel: String? = null): JsonObject {
        if (!flags.isOn("comparison.enabled")) throw ApiException(ErrorCode.SERVICE_UNAVAILABLE)
        limit("stub:quotes:$userId", 20, Duration.ofMinutes(1))
        val file = when (category) {
            "grocery" -> "comparison/grocery.complete.json"
            "food" -> "comparison/food.complete.json"
            "travel" -> "comparison/travel.complete.json"
            else -> throw ApiException(ErrorCode.SERVICE_UNAVAILABLE) // no shopping fixture yet
        }
        val id = newId("qt")
        val quote = fixture(file).redated().with("quote_id", id).with("objective", objective)
            .let { q -> selectedProvider?.let { q.with("selected_provider", it) } ?: q }
            .let { q -> deliveryLabel?.let { label -> q.withDeliveryLabel(label) } ?: q }
        return save("quote", userId, id, quote)
    }

    /** CR-004: the assumptions name the address the user chose (by label only). */
    private fun JsonObject.withDeliveryLabel(label: String): JsonObject = with(
        "assumptions",
        JsonArray(this["assumptions"]!!.jsonArray.map { a ->
            if (a.jsonPrimitive.content.startsWith("Delivery to")) JsonPrimitive("Delivery to your saved address \"$label\"") else a
        }),
    )

    fun quote(userId: String, quoteId: String) = own("quote", userId, quoteId)

    fun confirmQuote(userId: String, quoteId: String, provider: String, acknowledgedSwitch: Boolean): JsonObject {
        val q = quote(userId, quoteId)
        if (!Instant.parse(q.str("expires_at")!!).isAfter(clock.instant())) throw ApiException(ErrorCode.QUOTE_EXPIRED)
        val offered = q["provider_results"]!!.jsonArray.map { it.jsonObject.str("provider") }
        if (provider !in offered) throw ApiException(ErrorCode.CONFLICT)
        // Switching away from the app the user picked needs an explicit acknowledgement.
        if (provider != q.str("selected_provider") && !acknowledgedSwitch) throw ApiException(ErrorCode.CONFLICT)
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
        return save("task", userId, id, base.with("task_id", id).with("quote_id", quoteId).with("provider", provider).with("planned_actions", actions).with("approval_nonce", nonce))
    }

    /** Reading a running task advances the canned run by one step: RUNNING → CART_READY. */
    fun task(userId: String, taskId: String): JsonObject {
        val t = expireIfDue(userId, own("task", userId, taskId))
        return if (t.str("status") == "RUNNING") transition(userId, t, "cart-tasks/cart-ready.json") else t
    }

    fun approve(userId: String, taskId: String, actionIds: List<String>, nonce: String): JsonObject {
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
        return transition(userId, t, "cart-tasks/running.json")
    }

    fun checkpoint(userId: String, taskId: String, checkpointId: String, decision: String): JsonObject {
        val t = own("task", userId, taskId)
        if (t.str("status") != "CHECKPOINT" || t["checkpoint"]?.takeUnless { it is JsonNull }?.jsonObject?.str("checkpoint_id") != checkpointId) {
            throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
        }
        return transition(userId, t, if (decision == "approve") "cart-tasks/running.json" else "cart-tasks/cancelled.json")
    }

    /** The emergency stop always succeeds; a task that has already ended stays as it is. */
    fun stop(userId: String, taskId: String): JsonObject {
        val t = own("task", userId, taskId)
        return if (t.str("status") in TERMINAL) t else transition(userId, t, "cart-tasks/cancelled.json")
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
        transition(userId, t, "cart-tasks/handed-off.json")
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
    }
}

