package com.smartcart.server.tasks

import com.smartcart.server.flags.Flags
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ApiJson
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.me.MeService
import com.smartcart.server.providers.ProviderRegistry
import com.smartcart.server.providers.ProviderService
import com.smartcart.server.security.RateLimiter
import com.smartcart.server.stubs.CartLineIn
import com.smartcart.server.stubs.CheckpointIn
import com.smartcart.server.stubs.FailureIn
import com.smartcart.server.stubs.MoneyIn
import com.smartcart.server.stubs.NeedsUserIn
import com.smartcart.server.stubs.TaskEventIn
import com.smartcart.server.stubs.TaskProgressReport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** The task states and the transitions between them (D-002); the server refuses anything else. */
object TaskStateMachine {
    private val allowed: Map<String, Set<String>> = mapOf(
        "DRAFT" to setOf("AWAITING_APPROVAL", "CANCELLED"),
        "AWAITING_APPROVAL" to setOf("APPROVED", "CANCELLED", "EXPIRED"),
        "APPROVED" to setOf("RUNNING", "CANCELLED", "EXPIRED", "BLOCKED_POLICY"),
        "RUNNING" to setOf("CHECKPOINT", "NEEDS_USER_ACTION", "CART_READY", "CANCELLED", "FAILED_SAFE", "BLOCKED_POLICY"),
        "CHECKPOINT" to setOf("RUNNING", "CANCELLED", "FAILED_SAFE"),
        "NEEDS_USER_ACTION" to setOf("RUNNING", "CANCELLED", "FAILED_SAFE", "BLOCKED_POLICY"),
        "CART_READY" to setOf("HANDED_OFF", "EXPIRED"),
    )
    val TERMINAL = setOf("HANDED_OFF", "CANCELLED", "EXPIRED", "FAILED_SAFE", "BLOCKED_POLICY")
    val ACTIVE = setOf("APPROVED", "RUNNING", "CHECKPOINT", "NEEDS_USER_ACTION")
    fun can(from: String, to: String) = to in allowed[from].orEmpty()

    /** The D-002 transitions a phone may make in a progress report (CR-005); it may also stay where it is. */
    private val device = setOf("RUNNING", "CHECKPOINT", "NEEDS_USER_ACTION", "CART_READY", "FAILED_SAFE", "BLOCKED_POLICY", "CANCELLED")
    fun deviceCan(from: String, to: String) = from == to || (to in device && can(from, to))
}

/**
 * Cart tasks on the server (Phase 4.5). A task is bound to the comparison snapshot and the provider the
 * person confirmed (A-07); its plan is built by code from that snapshot's lines for the provider; it starts only
 * when every step is approved with the single-use nonce (A-06); every change goes through [TaskStateMachine].
 *
 * Partner apps are driven only on the phone, which reports its progress (CR-005). A task the phone doesn't run
 * — in development, a provider it doesn't automate — is simulated here so the journey can be followed end to
 * end; it never touches anything.
 */
class CartTasks(
    private val flags: Flags,
    private val providers: ProviderService,
    private val me: MeService,
    private val limiter: RateLimiter,
    private val clock: Clock,
    private val handoffs: HandoffTokens,
) {
    private val random = SecureRandom()
    private val tasks = ConcurrentHashMap<String, TaskRecord>() // "<user>:<task>"

    internal class TaskRecord(
        val id: String,
        val userId: String,
        val provider: String,
        val quoteId: String,
        val createdAt: Instant,
        val expiresAt: Instant,
        val limits: JsonObject,
        val planned: List<JsonObject>,
        /** What the person approved: item (name and size, lower case) and quantity, from the snapshot. */
        val approvedItems: List<Pair<String, Int>>,
        /** The snapshot's lines and total for the provider; a simulated run's cart. */
        val snapshotLines: JsonArray,
        val snapshotTotal: JsonElement,
        /** The phone runs this task and reports its progress; otherwise it is simulated (development). */
        val phoneRuns: Boolean,
    ) {
        var status = "AWAITING_APPROVAL"
        val events = mutableListOf<JsonObject>()
        var actionsUsed = 0
        var checkpoint: JsonElement = JsonNull
        var needsUserAction: JsonElement = JsonNull
        var cart: JsonElement = JsonNull
        var failure: JsonElement = JsonNull
        var nonce: String? = null
        var approvedBy: String? = null
        var approvedAt: Instant? = null
        var pausedSeconds = 0L
        var pausedSince: Instant? = null
        var lastSeq = 0
        var lastResponse: JsonObject? = null
        /** When the cart became ready; links can be made for [HANDOFF_WINDOW] after (CR-006). */
        var readyAt: Instant? = null
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)
    private fun b64(n: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(n).also(random::nextBytes))
    private fun limit(key: String, max: Int, window: Duration) {
        val d = limiter.hit(key, max, window)
        if (!d.allowed) throw ApiException.limited(ErrorCode.RATE_LIMITED, d)
    }
    private fun JsonObject.str(k: String) = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun name(p: String) = ProviderRegistry.find(p)?.displayName ?: p

    private fun TaskRecord.json(): JsonObject = JsonObject(
        mapOf(
            "task_id" to JsonPrimitive(id),
            "provider" to JsonPrimitive(provider),
            "quote_id" to JsonPrimitive(quoteId),
            "status" to JsonPrimitive(status),
            "created_at" to JsonPrimitive(createdAt.toString()),
            "expires_at" to JsonPrimitive(expiresAt.toString()),
            "limits" to limits,
            "actions_used" to JsonPrimitive(actionsUsed),
            "planned_actions" to JsonArray(planned),
            "events" to JsonArray(events.toList()),
            "checkpoint" to checkpoint,
            "needs_user_action" to needsUserAction,
            "cart" to cart,
            "failure" to failure,
            "approval_nonce" to (nonce?.takeIf { status == "AWAITING_APPROVAL" }?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    private fun TaskRecord.event(type: String, description: String, actionId: String? = null) {
        events += JsonObject(
            mapOf(
                "seq" to JsonPrimitive((events.maxOfOrNull { it["seq"]!!.jsonPrimitive.content.toInt() } ?: 0) + 1),
                "at" to JsonPrimitive(now().toString()),
                "type" to JsonPrimitive(type),
                "description" to JsonPrimitive(description),
                "action_id" to (actionId?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
    }

    /** Every status change goes through the state machine; an illegal one is a conflict and changes nothing. */
    private fun TaskRecord.move(to: String, description: String, type: String = "state_changed") {
        if (status != to && !TaskStateMachine.can(status, to)) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
        status = to
        event(type, description)
    }

    private fun failSafe(t: TaskRecord, code: String, message: String) {
        t.failure = JsonObject(mapOf("code" to JsonPrimitive(code), "message" to JsonPrimitive(message)))
        t.checkpoint = JsonNull; t.needsUserAction = JsonNull
        t.move("FAILED_SAFE", message, "halt")
    }

    private fun own(userId: String, taskId: String): TaskRecord = tasks["$userId:$taskId"] ?: throw ApiException(ErrorCode.NOT_FOUND)

    // ------------------------------------------------------------------ create

    /**
     * A task for [provider] on the comparison snapshot [quote], which the person confirmed for that provider.
     * The plan lists exactly the snapshot's available lines for it (A-07).
     */
    fun create(userId: String, quote: JsonObject, provider: String, taskType: String, phoneRuns: Boolean): JsonObject {
        // The same gates Phase 4 automation must pass (plan rules 3–4).
        if (!flags.isOn("automation.global.enabled") || !providers.isUsable(provider)) throw ApiException(ErrorCode.PROVIDER_DISABLED)
        if ("automation.$provider" !in me.grantedKeys(userId)) throw ApiException(ErrorCode.CONSENT_REQUIRED)
        limit("tasks:$userId", 10, Duration.ofMinutes(10))
        val result = quote["provider_results"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.str("provider") == provider }
            ?: throw ApiException(ErrorCode.CONFLICT)
        val lines = result["lines"]!!.jsonArray.map { it.jsonObject }.filter { it.str("match") != "unavailable" }
        if (lines.isEmpty()) throw ApiException(ErrorCode.CONFLICT)
        val ride = quote.str("category") == "travel" || taskType == "ride_setup"
        val created = now()
        val t = TaskRecord(
            id = "tsk_" + b64(9), userId = userId, provider = provider, quoteId = quote.str("quote_id")!!,
            createdAt = created, expiresAt = created.plus(APPROVAL_WINDOW), limits = LIMITS,
            planned = plan(provider, lines, ride),
            approvedItems = lines.map { l -> listOfNotNull(l.str("matched_name") ?: l.str("requested_name"), l.str("matched_size")).joinToString(" ").lowercase() to (l["quantity"]?.jsonPrimitive?.content?.toInt() ?: 1) },
            snapshotLines = JsonArray(lines), snapshotTotal = result["total_estimate"] ?: JsonNull, phoneRuns = phoneRuns,
        )
        // CR-002 G: a single-use nonce ties the approval to exactly this plan.
        t.nonce = "apn_" + b64(16)
        tasks["$userId:${t.id}"] = t
        return t.json()
    }

    private fun plan(provider: String, lines: List<JsonObject>, ride: Boolean): List<JsonObject> {
        var n = 0
        fun a(type: String, d: String, risk: String) = JsonObject(mapOf("action_id" to JsonPrimitive("a${++n}"), "type" to JsonPrimitive(type), "description" to JsonPrimitive(d), "risk" to JsonPrimitive(risk)))
        val open = a("open_app", "Open ${name(provider)}", "low")
        if (ride) {
            return listOf(
                open,
                a("set_pickup", "Set pickup to your saved pickup label", "medium"),
                a("set_drop", "Set the drop you asked for", "medium"),
                a("choose_ride_class", "Choose ${lines.first().str("matched_name") ?: lines.first().str("requested_name")}", "medium"),
                a("verify_cart", "Check fare and route match. You book the ride yourself in ${name(provider)}", "low"),
            )
        }
        return listOf(open, a("search", "Search for each item", "low")) +
            lines.map { l -> a("select_item", "Add ${listOfNotNull(l.str("matched_name") ?: l.str("requested_name"), l.str("matched_size")).joinToString(" ")} × ${l["quantity"]?.jsonPrimitive?.content ?: "1"}", "medium") } +
            a("verify_cart", "Check the cart matches what you approved", "low")
    }

    // ------------------------------------------------------------------ read

    fun get(userId: String, taskId: String): JsonObject {
        val t = own(userId, taskId)
        synchronized(t) {
            refresh(t)
            // Development: a task the phone doesn't run is simulated to a verified cart once it is running.
            if (!t.phoneRuns && t.status == "RUNNING") simulate(t)
            return t.json()
        }
    }

    /** Time and switches, checked on every read and report: expiry, the runtime limit, kill switches. */
    private fun refresh(t: TaskRecord) {
        val now = clock.instant()
        if (t.status == "AWAITING_APPROVAL" && !now.isBefore(t.expiresAt)) t.move("EXPIRED", "Approval window expired")
        if (t.status in TaskStateMachine.ACTIVE && (!flags.isOn("automation.global.enabled") || !providers.isUsable(t.provider))) {
            if (t.status == "APPROVED") t.move("CANCELLED", "Stopped: ${name(t.provider)} automation was paused", "halt")
            else failSafe(t, "provider_kill_switch", "Automation for ${name(t.provider)} has been paused. You can continue manually.")
        }
    }

    private fun simulate(t: TaskRecord) {
        t.planned.forEach { a ->
            val type = a.str("type")!!
            if (type != "verify_cart") t.event("action_succeeded", a.str("description")!!.replaceFirst("Add ", "Added ").replaceFirst("Open ", "Opened "), a.str("action_id"))
        }
        t.actionsUsed = t.planned.size
        t.cart = JsonObject(
            mapOf(
                "verified" to JsonPrimitive(true), "verified_at" to JsonPrimitive(now().toString()), "lines" to t.snapshotLines,
                "total_estimate" to t.snapshotTotal, "differs_from_quote" to JsonPrimitive(false),
            ),
        )
        t.event("cart_verified", "Cart matches what you approved")
        t.move("CART_READY", "Cart ready")
        t.readyAt = clock.instant()
    }

    // ------------------------------------------------------------------ approve, checkpoint, stop

    fun approve(userId: String, sessionId: String, taskId: String, actionIds: List<String>, nonce: String): JsonObject {
        val t = own(userId, taskId)
        synchronized(t) {
            refresh(t)
            if (nonce.isBlank() || nonce.length > 128) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "approval_nonce", "invalid_format")
            when (t.status) {
                "AWAITING_APPROVAL" -> {}
                "EXPIRED" -> throw ApiException(ErrorCode.TASK_EXPIRED)
                else -> throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            }
            // Every planned step is approved, or none is: partial approval is refused (A-06).
            if (actionIds.toSet() != t.planned.map { it.str("action_id")!! }.toSet()) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            // The nonce must be the one issued for this plan; it is cleared once used, so it can't be replayed.
            if (!MessageDigest.isEqual(nonce.toByteArray(), (t.nonce ?: "").toByteArray())) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            t.nonce = null
            t.approvedBy = sessionId
            t.approvedAt = clock.instant()
            t.move("APPROVED", "Approved by you")
            if (!t.phoneRuns) t.move("RUNNING", "Started")
            return t.json()
        }
    }

    fun checkpoint(userId: String, taskId: String, checkpointId: String, decision: String): JsonObject {
        val t = own(userId, taskId)
        synchronized(t) {
            refresh(t)
            val open = (t.checkpoint as? JsonObject)?.str("checkpoint_id")
            if (t.status != "CHECKPOINT" || open != checkpointId) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            t.endPause()
            t.checkpoint = JsonNull
            if (decision == "approve") t.move("RUNNING", "You chose to continue", "checkpoint_answered")
            else t.move("CANCELLED", "You chose to stop", "checkpoint_answered")
            return t.json()
        }
    }

    /** The emergency stop always succeeds; a task that has already ended stays as it is. */
    fun stop(userId: String, taskId: String, reason: String): JsonObject {
        val t = own(userId, taskId)
        synchronized(t) {
            if (t.status in TaskStateMachine.TERMINAL || t.status == "CART_READY") return t.json()
            t.endPause()
            t.checkpoint = JsonNull; t.needsUserAction = JsonNull
            t.move(
                "CANCELLED",
                when (reason) { "emergency_stop" -> "Stopped by you (emergency stop)"; "pause_all" -> "Stopped: all automation paused"; else -> "Cancelled by you" },
                "halt",
            )
            return t.json()
        }
    }

    private fun TaskRecord.endPause() {
        pausedSince?.let { pausedSeconds += Duration.between(it, clock.instant()).seconds }
        pausedSince = null
    }

    // ------------------------------------------------------------------ CR-005: the phone's progress

    /**
     * What the phone did for a task it runs: applied only along device transitions of the state machine, with
     * the limits enforced and the cart checked here. When the task has already ended on the server (stopped,
     * paused, expired), the answer says so and the phone halts.
     */
    fun report(userId: String, sessionId: String, taskId: String, r: TaskProgressReport): JsonObject {
        val t = own(userId, taskId)
        synchronized(t) {
            if (!t.phoneRuns) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            val approver = t.approvedBy ?: throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            if (approver != sessionId) throw ApiException(ErrorCode.FORBIDDEN)
            limit("progress:$userId:$taskId", 120, Duration.ofMinutes(1))
            if (r.reportSeq == t.lastSeq) return t.lastResponse!!
            if (r.reportSeq < t.lastSeq) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            refresh(t)
            if (t.status in TaskStateMachine.TERMINAL || t.status == "CART_READY") return t.json().also { t.lastSeq = r.reportSeq; t.lastResponse = it }

            val next = (t.events.maxOfOrNull { it["seq"]!!.jsonPrimitive.content.toInt() } ?: 0) + 1
            if (r.events.withIndex().any { (i, e) -> e.seq != next + i }) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "events", "invalid_value")
            if (r.actionsUsed < t.actionsUsed) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "actions_used", "invalid_value")
            if (!TaskStateMachine.deviceCan(t.status, r.status)) throw ApiException(ErrorCode.TASK_STATE_CONFLICT)
            when (r.status) {
                "CHECKPOINT" -> r.checkpoint ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "checkpoint", "required")
                "NEEDS_USER_ACTION" -> r.needsUserAction ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "needs_user_action", "required")
                "FAILED_SAFE", "BLOCKED_POLICY" -> r.failure ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "failure", "required")
                "CART_READY" -> r.cart ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "cart", "required")
            }

            val paused = setOf("CHECKPOINT", "NEEDS_USER_ACTION")
            if (t.status in paused && r.status !in paused) t.endPause()
            if (t.status !in paused && r.status in paused) t.pausedSince = clock.instant()

            r.events.forEach { t.events += ApiJson.encodeToJsonElement(TaskEventIn.serializer(), it).jsonObject }
            t.actionsUsed = r.actionsUsed
            val maxActions = t.limits["max_actions"]!!.jsonPrimitive.content.toInt()
            val runtimeEnds = t.approvedAt!!.plusSeconds(t.limits["max_runtime_seconds"]!!.jsonPrimitive.content.toLong() + t.pausedSeconds)
            when {
                r.actionsUsed > maxActions -> { t.forceRunning(); failSafe(t, "max_actions_reached", "This took more steps than allowed, so it was stopped.") }
                r.status !in paused && r.status !in TaskStateMachine.TERMINAL && clock.instant().isAfter(runtimeEnds) ->
                    { t.forceRunning(); failSafe(t, "runtime_exceeded", "This took longer than allowed, so it was stopped.") }
                r.status == "CART_READY" -> {
                    // The phone says the cart is ready; the server checks it against what was approved (A-07).
                    val c = r.cart!!
                    val got = c.lines.map { l -> listOfNotNull(l.matchedName ?: l.requestedName, l.matchedSize).joinToString(" ").lowercase() to l.quantity }
                    val ok = t.approvedItems.size == got.size && t.approvedItems.toSet() == got.toSet()
                    val totals = c.lines.map { it.lineTotal }
                    val total = if (totals.isNotEmpty() && totals.all { it != null }) MoneyIn(totals.sumOf { it!!.amountMinor }, totals.first()!!.currency) else null
                    t.cart = JsonObject(
                        mapOf(
                            "verified" to JsonPrimitive(ok),
                            "verified_at" to (if (ok) JsonPrimitive(now().toString()) else JsonNull),
                            "lines" to JsonArray(c.lines.map { ApiJson.encodeToJsonElement(CartLineIn.serializer(), it) }),
                            "total_estimate" to (total?.let { ApiJson.encodeToJsonElement(MoneyIn.serializer(), it) } ?: JsonNull),
                            "differs_from_quote" to JsonPrimitive(!ok),
                        ),
                    )
                    t.forceRunning()
                    if (ok) { t.checkpoint = JsonNull; t.needsUserAction = JsonNull; t.status = "CART_READY"; t.readyAt = clock.instant() }
                    else failSafe(t, "cart_verification_failed", "The cart in the app doesn't match what you approved, so it was stopped.")
                }
                else -> {
                    t.status = r.status
                    t.checkpoint = if (r.status == "CHECKPOINT") ApiJson.encodeToJsonElement(CheckpointIn.serializer(), r.checkpoint!!) else JsonNull
                    t.needsUserAction = if (r.status == "NEEDS_USER_ACTION") ApiJson.encodeToJsonElement(NeedsUserIn.serializer(), r.needsUserAction!!) else JsonNull
                    t.failure = if (r.status in setOf("FAILED_SAFE", "BLOCKED_POLICY")) ApiJson.encodeToJsonElement(FailureIn.serializer(), r.failure!!) else JsonNull
                }
            }
            return t.json().also { t.lastSeq = r.reportSeq; t.lastResponse = it }
        }
    }

    /** A phone that jumps straight to an end state from APPROVED passes through RUNNING (D-002 has no shortcut). */
    private fun TaskRecord.forceRunning() { if (status == "APPROVED") status = "RUNNING" }

    // ------------------------------------------------------------------ handoff

    /**
     * An opaque, signed, single-use token valid for at most 10 minutes, bound to the user, the task and the
     * provider's app (A-08); the URL carries the token only — no task, order or user data. CR-006: a new link
     * can be made for a ready cart (a newer one replaces the older) for an hour after it became ready, and
     * making one doesn't hand the cart off — using it does.
     */
    fun handoff(userId: String, taskId: String): JsonObject {
        val t = own(userId, taskId)
        synchronized(t) {
            refresh(t)
            if (!t.phoneRuns && t.status == "RUNNING") simulate(t)
            if (t.status != "CART_READY" && t.status != "HANDED_OFF") throw ApiException(ErrorCode.CONFLICT)
            val ready = t.readyAt ?: clock.instant()
            if (clock.instant().isAfter(ready.plus(HANDOFF_WINDOW))) {
                if (t.status == "CART_READY") t.move("EXPIRED", "The cart's links have expired")
                throw ApiException(ErrorCode.CONFLICT)
            }
            limit("handoffs:$userId", 20, Duration.ofMinutes(10))
            // Partner package names are added only once verified on a device (provider-matrix.md); until then the
            // handoff is bound to the provider and the person opens the app themselves.
            val pkg: String? = null
            val (token, expires) = handoffs.issue(userId, t.id, t.provider, pkg)
            return JsonObject(
                mapOf(
                    "handoff_url" to JsonPrimitive("smartcart://handoff/$token"),
                    "strategy" to JsonPrimitive(if (pkg == null) "manual" else "app_launch"),
                    "target_package" to (pkg?.let(::JsonPrimitive) ?: JsonNull),
                    "expires_at" to JsonPrimitive(expires.toString()),
                    "manual_steps" to JsonArray(listOf("Open ${name(t.provider)}", "Open the cart", "Check the items and pay in ${name(t.provider)}").map(::JsonPrimitive)),
                ),
            )
        }
    }

    /**
     * CR-006: checks a link and uses it up. The cart is handed off now. Every refusal is the same not_found, so
     * a link reveals nothing about why it failed.
     */
    fun redeem(userId: String, token: String, packageName: String?): JsonObject {
        val ok = handoffs.redeem(userId, token, packageName) as? HandoffTokens.Redeemed.Ok ?: throw ApiException(ErrorCode.NOT_FOUND)
        val t = own(userId, ok.taskId)
        synchronized(t) {
            if (t.status == "CART_READY") t.move("HANDED_OFF", "Opened ${name(t.provider)} cart", "handoff_created")
            return JsonObject(
                mapOf(
                    "task_id" to JsonPrimitive(t.id),
                    "provider" to JsonPrimitive(t.provider),
                    "target_package" to (ok.packageName?.let(::JsonPrimitive) ?: JsonNull),
                    "strategy" to JsonPrimitive(if (ok.packageName == null) "manual" else "app_launch"),
                    "manual_steps" to JsonArray(listOf("Open ${name(t.provider)}", "Open the cart", "Check the items and pay in ${name(t.provider)}").map(::JsonPrimitive)),
                ),
            )
        }
    }

    companion object {
        val HANDOFF_WINDOW: Duration = Duration.ofHours(1)
        val APPROVAL_WINDOW: Duration = Duration.ofMinutes(30)
        val LIMITS = JsonObject(mapOf("max_actions" to JsonPrimitive(25), "max_runtime_seconds" to JsonPrimitive(180), "inactivity_timeout_seconds" to JsonPrimitive(30)))
    }
}
