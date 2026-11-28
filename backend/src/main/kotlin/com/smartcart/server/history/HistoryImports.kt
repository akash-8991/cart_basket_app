package com.smartcart.server.history

import com.smartcart.server.flags.Flags
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.me.MeService
import com.smartcart.server.providers.ProviderRegistry
import com.smartcart.server.security.RateLimiter
import com.smartcart.server.security.Secrets
import com.smartcart.server.stubs.AdapterRef
import com.smartcart.server.stubs.MoneyIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Serializable data class UploadedItem(val name: String, val quantity: Int? = null, val amount: MoneyIn? = null)
@Serializable data class UploadedOrder(
    val sourceRef: String,
    val orderedAt: String? = null,
    val status: String? = null,
    val total: MoneyIn? = null,
    val items: List<UploadedItem>? = null,
    val locationLabel: String? = null,
    val extractionConfidence: String,
)
@Serializable data class HistoryBatch(
    val batchSeq: Int,
    val provider: String,
    val status: String,
    val ordersFound: Int,
    val confidence: String? = null,
    val failureReason: String? = null,
    val adapter: AdapterRef,
    val reviewed: Boolean,
    val orders: List<UploadedOrder> = emptyList(),
    val importOutcome: String? = null,
)

/**
 * History imports (CR-007, Phase 4.6). Order-history screens are parsed on the phone (threat model A-10);
 * the server is told how each app's import went and, only when the person chose to sync, reviewed the result
 * on the phone, still consents for that app and `history.upload.enabled` is on, receives the normalised
 * orders — limited to the fields the person chose. Duplicates are matched by a salted hash, never by the
 * provider's order ID; totals are checked by code. Uploaded history only ever affects its owner (A-17).
 */
class HistoryImports(
    private val flags: Flags,
    private val me: MeService,
    private val limiter: RateLimiter,
    private val clock: Clock,
    private val secrets: Secrets,
) {
    private class Session(
        val id: String,
        val userId: String,
        val uploadMode: String,
        val fields: Set<String>,
        val providers: LinkedHashMap<String, JsonObject>,
        val dedupSalt: String?,
    ) {
        var status = "running"
        var lastSeq = 0
        val sample = ArrayDeque<String>() // order IDs stored by this session, newest last
    }

    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()                    // "<user>:<session>"
    private val orders = ConcurrentHashMap<String, LinkedHashMap<String, JsonObject>>() // user → order ID → order
    private val byRef = ConcurrentHashMap<String, String>()                         // keyed source_ref → order ID

    private fun b64(n: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(n).also(random::nextBytes))
    private fun limit(key: String, max: Int, window: Duration) {
        val d = limiter.hit(key, max, window)
        if (!d.allowed) throw ApiException.limited(ErrorCode.RATE_LIMITED, d)
    }
    private fun conflict(): Nothing = throw ApiException(ErrorCode.CONFLICT)
    private fun bad(field: String): Nothing = throw ApiException.field(ErrorCode.VALIDATION_FAILED, field, "invalid_value")

    fun start(userId: String, providerKeys: List<String>, fields: List<String>, uploadMode: String): JsonObject {
        if (!flags.isOn("history.import.enabled")) throw ApiException(ErrorCode.PROVIDER_DISABLED)
        val consents = me.grantedKeys(userId)
        if (providerKeys.any { "history.$it" !in consents }) throw ApiException(ErrorCode.CONSENT_REQUIRED)
        limit("imports:$userId", 5, Duration.ofDays(1))
        val providers = LinkedHashMap<String, JsonObject>()
        providerKeys.distinct().forEach { providers[it] = provider(it, "pending", 0, null, null) }
        val s = Session("imp_" + b64(9), userId, uploadMode, fields.toSet(), providers, if (uploadMode == "normalized_upload") b64(24) else null)
        sessions["$userId:${s.id}"] = s
        return s.json()
    }

    fun session(userId: String, sessionId: String): JsonObject = (sessions["$userId:$sessionId"] ?: throw ApiException(ErrorCode.NOT_FOUND)).json()

    private fun provider(key: String, status: String, found: Int, confidence: String?, message: String?) = JsonObject(
        mapOf(
            "provider" to JsonPrimitive(key),
            "status" to JsonPrimitive(status),
            "orders_found" to JsonPrimitive(found),
            "confidence" to (confidence?.let(::JsonPrimitive) ?: JsonNull),
            "message" to (message?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    private fun Session.json(): JsonObject = JsonObject(
        mapOf(
            "session_id" to JsonPrimitive(id),
            "status" to JsonPrimitive(status),
            "upload_mode" to JsonPrimitive(uploadMode),
            "providers" to JsonArray(providers.values.toList()),
            "review_sample" to JsonArray(sample.toList().takeLast(5).mapNotNull { orders[userId]?.get(it) }),
            "dedup_salt" to (dedupSalt?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    private fun failureText(reason: String?, name: String) = when (reason) {
        null -> null
        "login_required" -> "$name wanted you to sign in, so its history wasn't read."
        "unknown_screen" -> "$name's order history screen changed. Skipped safely."
        "package_mismatch" -> "The $name app on your phone couldn't be verified."
        "app_not_installed" -> "$name isn't installed on your phone."
        "timeout" -> "$name took too long, so its history wasn't read."
        else -> "$name's history wasn't read."
    }

    /**
     * One app's result. Counts and status always; orders only when everything above allows it. A batch with
     * orders that isn't allowed is refused whole, so nothing personal reaches the server by mistake.
     */
    fun batch(userId: String, sessionId: String, b: HistoryBatch): JsonObject {
        val s = sessions["$userId:$sessionId"] ?: throw ApiException(ErrorCode.NOT_FOUND)
        synchronized(s) {
            limit("history-batches:$userId", 60, Duration.ofMinutes(1))
            if (s.status != "running") conflict()
            if (b.provider !in s.providers) conflict()
            if (b.batchSeq != s.lastSeq + 1) conflict()
            // Consent can be withdrawn at any time; once it is, nothing more is taken for that app.
            if ("history.${b.provider}" !in me.grantedKeys(userId)) throw ApiException(ErrorCode.CONSENT_REQUIRED)
            if (b.orders.isNotEmpty()) acceptOrders(s, b)

            val name = ProviderRegistry.find(b.provider)?.displayName ?: b.provider
            s.providers[b.provider] = provider(b.provider, b.status, b.ordersFound, b.confidence, failureText(b.failureReason, name))
            s.lastSeq = b.batchSeq
            b.importOutcome?.let { s.status = it }
            return s.json()
        }
    }

    private fun acceptOrders(s: Session, b: HistoryBatch) {
        if (s.uploadMode != "normalized_upload" || !b.reviewed) conflict()
        if (!flags.isOn("history.upload.enabled")) throw ApiException(ErrorCode.POLICY_BLOCKED)
        // An order needs its date and total to be kept (Order); without those two fields chosen, nothing is stored.
        if ("order_date" !in s.fields || "amounts" !in s.fields) conflict()
        val f = s.fields
        b.orders.forEach { o ->
            // Only the fields the person chose: anything else must be absent.
            if (o.status != null && "status" !in f) bad("orders.status")
            if (o.items != null && "items" !in f) bad("orders.items")
            if (o.items.orEmpty().any { it.quantity != null } && "quantity" !in f) bad("orders.items.quantity")
            if (o.items.orEmpty().any { it.amount != null } && "amounts" !in f) bad("orders.items.amount")
            if (o.locationLabel != null && "location_label" !in f) bad("orders.location_label")
            if (o.orderedAt == null || runCatching { Instant.parse(o.orderedAt) }.isFailure) bad("orders.ordered_at")
            if (o.total == null) bad("orders.total")
            if (!Regex("^[0-9a-f]{64}$").matches(o.sourceRef)) bad("orders.source_ref")
            if (o.status != null && o.status !in ORDER_STATES) bad("orders.status")
            if (o.extractionConfidence !in setOf("high", "medium", "low")) bad("orders.extraction_confidence")
            if (o.items.orEmpty().any { it.name.isBlank() || it.name.length > 120 || (it.quantity != null && it.quantity !in 0..999) }) bad("orders.items")
            if ((o.locationLabel?.length ?: 0) > 40) bad("orders.location_label")
            val amounts = listOfNotNull(o.total) + o.items.orEmpty().mapNotNull { it.amount }
            if (amounts.any { it.amountMinor !in 0..10_000_000L || it.currency.length != 3 }) bad("orders.total")
        }
        val mine = orders.getOrPut(s.userId) { LinkedHashMap() }
        b.orders.forEach { o ->
            // Duplicates by a keyed hash of the phone's salted reference; the provider order ID never arrives.
            val ref = secrets.hmacHex("history-ref", "${s.userId}|${o.sourceRef}")
            val id = byRef.getOrPut(ref) { "ord_" + b64(9) }
            mine[id] = order(id, b.provider, o)
            s.sample.remove(id); s.sample.addLast(id)
        }
    }

    /** Totals are checked by code: items that add up to more than the order's total can't be right. */
    private fun order(id: String, provider: String, o: UploadedOrder): JsonObject {
        val withQuantities = o.items.orEmpty().filter { it.quantity != null } // an item is kept only with its quantity
        val itemSum = withQuantities.mapNotNull { it.amount?.amountMinor }.takeIf { it.size == withQuantities.size && it.isNotEmpty() }?.sum()
        val consistent = itemSum == null || itemSum <= o.total!!.amountMinor
        fun money(m: MoneyIn) = JsonObject(mapOf("amount_minor" to JsonPrimitive(m.amountMinor), "currency" to JsonPrimitive(m.currency)))
        return JsonObject(
            mapOf(
                "order_id" to JsonPrimitive(id),
                "provider" to JsonPrimitive(provider),
                "category" to JsonPrimitive(ProviderRegistry.find(provider)?.category ?: "grocery"),
                "ordered_at" to JsonPrimitive(o.orderedAt),
                "status" to JsonPrimitive(o.status ?: "UNKNOWN"),
                "total" to money(o.total!!),
                "items" to JsonArray(
                    withQuantities.map { i ->
                        JsonObject(mapOf("name" to JsonPrimitive(i.name), "quantity" to JsonPrimitive(i.quantity!!), "amount" to (i.amount?.let(::money) ?: JsonNull)))
                    },
                ),
                "location_label" to (o.locationLabel?.let(::JsonPrimitive) ?: JsonNull),
                "extraction_confidence" to JsonPrimitive(if (consistent) o.extractionConfidence else "low"),
            ),
        )
    }

    /** The person's uploaded orders, newest first; null when they have none. */
    fun uploaded(userId: String): List<JsonObject>? =
        orders[userId]?.values?.toList()?.takeIf { it.isNotEmpty() }?.sortedByDescending { it["ordered_at"].toString() }

    /** Deletes one of the person's uploaded orders; false if they have no such order. */
    fun delete(userId: String, orderId: String): Boolean = orders[userId]?.remove(orderId) != null

    companion object {
        val ORDER_STATES = setOf("PLACED", "CONFIRMED", "PREPARING", "OUT_FOR_DELIVERY", "DELIVERED", "CANCELLED", "REFUNDED", "PARTIALLY_REFUNDED", "UNKNOWN")
    }
}
