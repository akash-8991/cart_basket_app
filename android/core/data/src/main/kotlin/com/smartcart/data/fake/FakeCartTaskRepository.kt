package com.smartcart.data.fake

import com.smartcart.data.AccountRepository
import com.smartcart.data.ApiResult
import com.smartcart.data.CartTaskRepository
import com.smartcart.data.ComparisonRepository
import com.smartcart.data.Fixtures
import com.smartcart.data.ProviderRepository
import com.smartcart.data.automation.AutomationPolicy
import com.smartcart.data.automation.PolicyDecision
import com.smartcart.data.automation.ScreenObservation
import com.smartcart.data.automation.TaskStateMachine
import com.smartcart.data.clientError
import com.smartcart.data.mock.HandoffScenario
import com.smartcart.data.mock.MockControl
import com.smartcart.data.mock.TaskScenario
import com.smartcart.model.ActionRisk
import com.smartcart.model.ActionType
import com.smartcart.model.CartTask
import com.smartcart.model.Category
import com.smartcart.model.Checkpoint
import com.smartcart.model.ErrorCode
import com.smartcart.model.Handoff
import com.smartcart.model.LineMatch
import com.smartcart.model.NeedsUserAction
import com.smartcart.model.PlannedAction
import com.smartcart.model.ProviderKey
import com.smartcart.model.ProviderStatus
import com.smartcart.model.StopReason
import com.smartcart.model.TaskEvent
import com.smartcart.model.TaskEventType
import com.smartcart.model.TaskFailure
import com.smartcart.model.TaskStatus
import com.smartcart.model.VerifiedCart
import com.smartcart.model.FailureCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Simulates the on-device automation runtime against the deterministic safety policy.
 * No partner app is touched in Phase 1: screen observations are scripted per [TaskScenario].
 */
class FakeCartTaskRepository(
    fixtures: Fixtures,
    mock: MockControl,
    private val comparisons: ComparisonRepository,
    private val providers: ProviderRepository,
    private val account: AccountRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) : FakeBase(fixtures, mock), CartTaskRepository {

    private val tasks = MutableStateFlow<Map<String, CartTask>>(emptyMap())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val checkpointAnswers = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val resumeSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val _active = MutableStateFlow<CartTask?>(null)
    override val activeTask: StateFlow<CartTask?> = _active

    private fun now() = clock.instant().toString()

    private fun put(task: CartTask) {
        tasks.update { it + (task.taskId to task) }
        _active.value = tasks.value.values.firstOrNull { it.status.isActive }
    }

    private fun get(taskId: String) = tasks.value[taskId]

    private fun transition(taskId: String, to: TaskStatus, description: String, type: TaskEventType = TaskEventType.STATE_CHANGED, edit: (CartTask) -> CartTask = { it }): CartTask? {
        val t = get(taskId) ?: return null
        // Illegal transitions (e.g. a late runtime step after Stop) are ignored, never applied.
        if (t.status != to && !TaskStateMachine.canTransition(t.status, to)) return null
        val updated = edit(t).copy(status = to, events = t.events + event(t, type, description))
        put(updated)
        return updated
    }

    private fun event(t: CartTask, type: TaskEventType, description: String, actionId: String? = null) =
        TaskEvent(seq = (t.events.maxOfOrNull { it.seq } ?: 0) + 1, at = now(), type = type, description = description, actionId = actionId)

    private fun log(taskId: String, type: TaskEventType, description: String, actionId: String? = null) {
        val t = get(taskId) ?: return
        put(t.copy(events = t.events + event(t, type, description, actionId)))
    }

    override suspend fun create(quoteId: String, provider: ProviderKey): ApiResult<CartTask> = call(0.6) {
        if (account.automationPaused.value) return@call clientError(ErrorCode.POLICY_BLOCKED, "Automation is paused. Resume it in Settings → Help.")
        if (cfg.task == TaskScenario.PROVIDER_DISABLED) return@call fixtures.error("errors/provider-disabled.json")
        val p = providers.providers.value.firstOrNull { it.key == provider }
        if (p == null || p.status != ProviderStatus.ENABLED || !p.automationAvailable) return@call fixtures.error("errors/provider-disabled.json")
        if (!p.consent.automation) return@call fixtures.error("errors/consent-required.json")
        val quote = comparisons.cached(quoteId) ?: return@call clientError(ErrorCode.NOT_FOUND, "Comparison not found.")

        val base = fixtures.load<CartTask>("cart-tasks/awaiting-approval.json")
        val task = base.copy(
            taskId = "tsk_" + UUID.randomUUID().toString().take(8),
            provider = provider,
            quoteId = quoteId,
            createdAt = now(),
            expiresAt = clock.instant().plus(Duration.ofMinutes(10)).toString(),
            plannedActions = plan(quote.category, provider, quote.providerResults.firstOrNull { it.provider == provider }?.lines.orEmpty().map {
                listOfNotNull(it.matchedName ?: it.requestedName, it.matchedSize).joinToString(" ") + " × ${it.quantity}"
            }),
            events = emptyList(),
        )
        put(task)
        ApiResult.Success(task)
    }

    private fun plan(category: Category, provider: ProviderKey, items: List<String>): List<PlannedAction> {
        var n = 0
        fun a(type: ActionType, d: String, risk: ActionRisk) = PlannedAction("a${++n}", type, d, risk)
        val open = a(ActionType.OPEN_APP, "Open ${provider.displayName}", ActionRisk.LOW)
        return if (category == Category.TRAVEL) {
            listOf(
                open,
                a(ActionType.SET_PICKUP, "Set pickup to your saved Home label", ActionRisk.MEDIUM),
                a(ActionType.SET_DROP, "Set drop to Airport Terminal 1", ActionRisk.MEDIUM),
                a(ActionType.CHOOSE_RIDE_CLASS, "Choose Auto", ActionRisk.MEDIUM),
                a(ActionType.VERIFY_CART, "Check fare and route match. You book the ride yourself in ${provider.displayName}", ActionRisk.LOW),
            )
        } else {
            listOf(open, a(ActionType.SEARCH, "Search for each item", ActionRisk.LOW)) +
                items.map { a(ActionType.SELECT_ITEM, "Add $it", ActionRisk.MEDIUM) } +
                a(ActionType.VERIFY_CART, "Check the cart matches what you approved", ActionRisk.LOW)
        }
    }

    override fun observe(taskId: String): Flow<CartTask?> = tasks.map { it[taskId] }.distinctUntilChanged()

    override suspend fun approve(taskId: String, actionIds: List<String>): ApiResult<CartTask> = call(0.4) {
        val t = get(taskId) ?: return@call clientError(ErrorCode.NOT_FOUND, "Task not found.")
        // Duplicate taps return the same result (idempotent approval).
        if (t.status != TaskStatus.AWAITING_APPROVAL) {
            return@call if (t.status in setOf(TaskStatus.APPROVED, TaskStatus.RUNNING)) ApiResult.Success(t)
            else fixtures.error("cart-tasks/state-conflict.json")
        }
        if (clock.instant().isAfter(java.time.Instant.parse(t.expiresAt))) {
            transition(taskId, TaskStatus.EXPIRED, "Approval window expired")
            return@call clientError(ErrorCode.TASK_EXPIRED, "This request expired. Start again.")
        }
        if (actionIds.toSet() != t.plannedActions.map { it.actionId }.toSet()) {
            return@call clientError(ErrorCode.VALIDATION_FAILED, "Every planned step must be approved.")
        }
        val approved = transition(taskId, TaskStatus.APPROVED, "Approved by you")!!
        jobs[taskId] = scope.launch { run(taskId) }
        ApiResult.Success(approved)
    }

    private suspend fun run(taskId: String) {
        val started = clock.instant()
        transition(taskId, TaskStatus.RUNNING, "Started")
        val skipped = mutableSetOf<String>()
        val t0 = get(taskId) ?: return
        val itemSteps = t0.plannedActions.filter { it.type == ActionType.SELECT_ITEM || it.type == ActionType.SET_DROP }
        for ((index, action) in t0.plannedActions.withIndex()) {
            val t = get(taskId) ?: return
            if (t.status.isTerminal) return
            log(taskId, TaskEventType.ACTION_STARTED, action.description.replaceFirstChar { it.uppercase() } + "…", action.actionId)
            mock.latency(1.1)

            val observation = when {
                cfg.task == TaskScenario.NEEDS_USER_ACTION && index == 1 && t.needsUserAction == null && !t.events.any { it.description == "Resumed" } -> ScreenObservation.LoginRequired
                cfg.task == TaskScenario.FAILED_SAFE && index == 3 -> ScreenObservation.UnknownScreen
                cfg.task == TaskScenario.BLOCKED_POLICY && action.type == ActionType.VERIFY_CART -> ScreenObservation.PaymentScreen
                cfg.task == TaskScenario.CHECKPOINT && action == itemSteps.getOrNull(1) ->
                    if (t.provider.category == Category.TRAVEL) ScreenObservation.StateDrift("${t.provider.displayName} shows a higher fare than the comparison (+₹25).")
                    else ScreenObservation.ItemUnavailable(action.description.removePrefix("Add ").substringBefore(" ×"))
                else -> ScreenObservation.Expected
            }
            val budget = AutomationPolicy.Budget(t.actionsUsed, t.limits.maxActions, Duration.between(started, clock.instant()).seconds, t.limits.maxRuntimeSeconds)
            val killed = providers.providers.value.firstOrNull { it.key == t.provider }?.status == ProviderStatus.KILL_SWITCHED

            when (val decision = AutomationPolicy.evaluate(observation, budget, killed)) {
                PolicyDecision.Continue -> Unit
                is PolicyDecision.Halt -> {
                    transition(taskId, TaskStatus.FAILED_SAFE, haltText(decision.code), TaskEventType.HALT) {
                        it.copy(failure = TaskFailure(decision.code, failureMessage(decision.code, it.provider)))
                    }
                    return
                }
                is PolicyDecision.Block -> {
                    transition(taskId, TaskStatus.BLOCKED_POLICY, "Stopped: payment screen detected", TaskEventType.HALT) {
                        it.copy(failure = TaskFailure(decision.code, "A payment screen appeared. I never handle payments, so I stopped. Your cart is unchanged from the last verified step."))
                    }
                    return
                }
                is PolicyDecision.HandToUser -> {
                    val signal = CompletableDeferred<Unit>().also { resumeSignals[taskId] = it }
                    transition(taskId, TaskStatus.NEEDS_USER_ACTION, "${t.provider.displayName} needs you: ${decision.reason.title.lowercase()}", TaskEventType.HALT) {
                        it.copy(needsUserAction = NeedsUserAction(decision.reason, "${it.provider.displayName} needs you to sign in. Sign in yourself, then tap Resume. I never enter passwords or OTPs."))
                    }
                    signal.await()
                    if (get(taskId)?.status != TaskStatus.NEEDS_USER_ACTION) return
                    transition(taskId, TaskStatus.RUNNING, "Resumed") { it.copy(needsUserAction = null) }
                }
                is PolicyDecision.AskUser -> {
                    val answer = CompletableDeferred<Boolean>().also { checkpointAnswers[taskId] = it }
                    transition(taskId, TaskStatus.CHECKPOINT, decision.prompt.substringBefore('.') , TaskEventType.CHECKPOINT_RAISED) {
                        it.copy(checkpoint = Checkpoint("cp_" + action.actionId, decision.prompt, decision.risk))
                    }
                    val ok = withTimeoutOrNull(t.limits.inactivityTimeoutSeconds * 1000L) { answer.await() }
                    val current = get(taskId) ?: return
                    if (current.status != TaskStatus.CHECKPOINT) return
                    when (ok) {
                        null -> {
                            transition(taskId, TaskStatus.FAILED_SAFE, "Stopped: no answer in time", TaskEventType.HALT) {
                                it.copy(checkpoint = null, failure = TaskFailure(FailureCode.INACTIVITY_TIMEOUT, failureMessage(FailureCode.INACTIVITY_TIMEOUT, it.provider)))
                            }
                            return
                        }
                        false -> {
                            transition(taskId, TaskStatus.CANCELLED, "You chose to stop", TaskEventType.CHECKPOINT_ANSWERED) { it.copy(checkpoint = null) }
                            return
                        }
                        true -> {
                            transition(taskId, TaskStatus.RUNNING, "You chose to continue", TaskEventType.CHECKPOINT_ANSWERED) { it.copy(checkpoint = null) }
                            if (action.type == ActionType.SELECT_ITEM) {
                                skipped += action.actionId
                                log(taskId, TaskEventType.ACTION_SUCCEEDED, "Skipped ${action.description.removePrefix("Add ")}", action.actionId)
                                continue
                            }
                        }
                    }
                }
            }
            val after = get(taskId) ?: return
            if (after.status.isTerminal) return
            put(after.copy(actionsUsed = after.actionsUsed + 1))
            log(taskId, TaskEventType.ACTION_SUCCEEDED, doneText(action), action.actionId)
        }
        finish(taskId, skipped)
    }

    private fun finish(taskId: String, skipped: Set<String>) {
        val t = get(taskId) ?: return
        val quote = comparisons.cached(t.quoteId)
        val result = quote?.providerResults?.firstOrNull { it.provider == t.provider }
        val selectSteps = t.plannedActions.filter { it.type == ActionType.SELECT_ITEM }
        val skippedIdx = selectSteps.withIndex().filter { it.value.actionId in skipped }.map { it.index }.toSet()
        val lines = result?.lines.orEmpty().mapIndexed { i, l -> if (i in skippedIdx) l.copy(match = LineMatch.UNAVAILABLE, lineTotal = null, note = "Skipped with your OK") else l }
        log(taskId, TaskEventType.CART_VERIFIED, if (skipped.isEmpty()) "Cart matches what you approved" else "Cart verified without skipped items")
        transition(taskId, TaskStatus.CART_READY, "Cart ready") {
            it.copy(
                cart = VerifiedCart(
                    verified = true,
                    verifiedAt = now(),
                    lines = lines,
                    // When items were skipped the old estimate no longer applies; the app shows the final price.
                    totalEstimate = if (skipped.isEmpty()) result?.totalEstimate else null,
                    differsFromQuote = skipped.isNotEmpty(),
                ),
            )
        }
    }

    private fun doneText(a: com.smartcart.model.PlannedAction) = when (a.type) {
        ActionType.OPEN_APP -> a.description.replace("Open", "Opened")
        ActionType.SEARCH -> "Searched for items"
        ActionType.SELECT_ITEM -> a.description.replace("Add", "Added")
        ActionType.VERIFY_CART -> "Checked cart"
        else -> a.description
    }

    private fun haltText(code: FailureCode) = when (code) {
        FailureCode.UNKNOWN_SCREEN -> "Stopped: unfamiliar screen"
        FailureCode.MAX_ACTIONS_REACHED -> "Stopped: step limit reached"
        FailureCode.RUNTIME_EXCEEDED -> "Stopped: time limit reached"
        FailureCode.PACKAGE_MISMATCH -> "Stopped: a different app opened"
        FailureCode.PROVIDER_KILL_SWITCH -> "Stopped: app paused by SmartCart"
        else -> "Stopped safely"
    }

    private fun failureMessage(code: FailureCode, provider: ProviderKey) = when (code) {
        FailureCode.UNKNOWN_SCREEN -> "${provider.displayName} showed a screen I do not recognise, so I stopped without changing anything else. You can continue manually."
        FailureCode.MAX_ACTIONS_REACHED -> "This took more steps than allowed, so I stopped. You can continue manually."
        FailureCode.RUNTIME_EXCEEDED -> "This took longer than allowed, so I stopped. You can continue manually."
        FailureCode.INACTIVITY_TIMEOUT -> "I didn't get an answer in time, so I stopped. Nothing else was changed."
        FailureCode.PACKAGE_MISMATCH -> "A different app came to the front, so I stopped immediately."
        FailureCode.PROVIDER_KILL_SWITCH -> "Automation for ${provider.displayName} has been paused. You can continue manually."
        else -> "I stopped safely. You can continue manually."
    }

    override suspend fun answerCheckpoint(taskId: String, checkpointId: String, approve: Boolean): ApiResult<CartTask> = call(0.2) {
        val t = get(taskId) ?: return@call clientError(ErrorCode.NOT_FOUND, "Task not found.")
        if (t.status != TaskStatus.CHECKPOINT || t.checkpoint?.checkpointId != checkpointId) return@call fixtures.error("cart-tasks/state-conflict.json")
        checkpointAnswers.remove(taskId)?.complete(approve)
        ApiResult.Success(get(taskId)!!)
    }

    override suspend fun resume(taskId: String): ApiResult<CartTask> = call(0.2) {
        val t = get(taskId) ?: return@call clientError(ErrorCode.NOT_FOUND, "Task not found.")
        if (t.status != TaskStatus.NEEDS_USER_ACTION) return@call fixtures.error("cart-tasks/state-conflict.json")
        resumeSignals.remove(taskId)?.complete(Unit)
        ApiResult.Success(t)
    }

    override suspend fun stop(taskId: String, reason: StopReason): CartTask? {
        val t = get(taskId) ?: return null
        if (t.status.isTerminal || t.status == TaskStatus.CART_READY) return t
        jobs.remove(taskId)?.cancel()
        checkpointAnswers.remove(taskId)?.cancel()
        resumeSignals.remove(taskId)?.cancel()
        val text = when (reason) {
            StopReason.EMERGENCY_STOP -> "Stopped by you (emergency stop)"
            StopReason.USER_CANCEL -> "Cancelled by you"
            StopReason.PAUSE_ALL -> "Stopped: all automation paused"
        }
        return transition(taskId, TaskStatus.CANCELLED, text, TaskEventType.HALT) { it.copy(checkpoint = null, needsUserAction = null) }
    }

    override suspend fun stopAll(reason: StopReason) {
        tasks.value.values.filter { it.status.isActive || it.status == TaskStatus.AWAITING_APPROVAL }.forEach { stop(it.taskId, reason) }
    }

    override suspend fun createHandoff(taskId: String): ApiResult<Handoff> = call(0.5) {
        val t = get(taskId) ?: return@call clientError(ErrorCode.NOT_FOUND, "Task not found.")
        if (t.status != TaskStatus.CART_READY && t.status != TaskStatus.HANDED_OFF) return@call fixtures.error("cart-tasks/state-conflict.json")
        val path = when (cfg.handoff) {
            HandoffScenario.CART -> "deep-links/cart.json"
            HandoffScenario.APP_LAUNCH -> "deep-links/app-launch.json"
            HandoffScenario.MANUAL -> "deep-links/manual.json"
        }
        ApiResult.Success(fixtures.load(path))
    }

    override suspend fun markHandedOff(taskId: String) {
        val t = get(taskId) ?: return
        if (t.status == TaskStatus.CART_READY) transition(taskId, TaskStatus.HANDED_OFF, "Opened ${t.provider.displayName} cart", TaskEventType.HANDOFF_CREATED)
    }
}
