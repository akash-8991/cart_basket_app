package com.smartcart.data.device

import com.smartcart.automation.Adapter
import com.smartcart.automation.Driver
import com.smartcart.automation.ExecutablePlan
import com.smartcart.automation.ItemRequest
import com.smartcart.automation.RunState
import com.smartcart.automation.RunnerTiming
import com.smartcart.automation.TaskRunner
import com.smartcart.data.AccountRepository
import com.smartcart.data.ApiResult
import com.smartcart.data.CartTaskRepository
import com.smartcart.data.ComparisonRepository
import com.smartcart.data.ProviderRepository
import com.smartcart.data.clientError
import com.smartcart.model.CartTask
import com.smartcart.model.ErrorCode
import com.smartcart.model.Handoff
import com.smartcart.model.HandoffStrategy
import com.smartcart.model.LineMatch
import com.smartcart.model.ProviderKey
import com.smartcart.model.ProviderStatus
import com.smartcart.model.StopReason
import com.smartcart.model.TaskEvent
import com.smartcart.model.TaskEventType
import com.smartcart.model.TaskLimits
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cart tasks that really run on this phone (Phase 4.2). The plan is built by code from the comparison, the
 * person approves all of it, and the 4.1 [TaskRunner] carries it out through the accessibility driver. The
 * runner's state is mirrored into the same [CartTask] the screens already show: timeline, checkpoints,
 * needs-you, emergency stop, verified cart.
 *
 * Until CR-005 there is no operation for reporting device progress to the server, so these tasks live on the
 * phone only.
 */
class DeviceCartTaskRepository(
    private val device: OnDeviceAutomation,
    private val comparisons: ComparisonRepository,
    private val providers: ProviderRepository,
    private val account: AccountRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val limits: TaskLimits = DEFAULT_LIMITS,
    private val timing: RunnerTiming = RunnerTiming(),
    /** How a plan is built from the items; code, never a model. Tests swap it to exercise checkpoints. */
    private val planFor: (appName: String, items: List<ItemRequest>) -> ExecutablePlan = ExecutablePlan::forCart,
) : CartTaskRepository {

    private class Run(val adapter: Adapter, val plan: ExecutablePlan) {
        @Volatile var runner: TaskRunner? = null
        @Volatile var job: Job? = null
        @Volatile var step: Int = 0
    }

    private val tasks = MutableStateFlow<Map<String, CartTask>>(emptyMap())
    private val runs = ConcurrentHashMap<String, Run>()
    private val _active = MutableStateFlow<CartTask?>(null)
    override val activeTask: StateFlow<CartTask?> = _active
    private val lock = Any()

    /** Whether [taskId] is one of this phone's tasks (as opposed to a simulated one). */
    fun owns(taskId: String): Boolean = runs.containsKey(taskId)

    private fun now() = clock.instant().toString()

    /** Applies [change] atomically; a null result leaves the task as it was. Returns the task afterwards. */
    private fun edit(taskId: String, change: (CartTask) -> CartTask?): CartTask? = synchronized(lock) {
        val t = tasks.value[taskId] ?: return null
        val updated = change(t) ?: return t
        tasks.value = tasks.value + (taskId to updated)
        _active.value = tasks.value.values.firstOrNull { it.status.isActive }
        updated
    }

    private fun event(t: CartTask, type: TaskEventType, description: String) =
        TaskEvent(seq = (t.events.maxOfOrNull { it.seq } ?: 0) + 1, at = now(), type = type, description = description)

    private fun conflict() = clientError(ErrorCode.TASK_STATE_CONFLICT, "This task has moved on. Refresh and try again.")
    private fun notFound() = clientError(ErrorCode.NOT_FOUND, "Task not found.")
    private fun serviceOff() = clientError(ErrorCode.CONSENT_REQUIRED, "Turn on SmartCart under Settings → Accessibility, then try again.")

    override suspend fun create(quoteId: String, provider: ProviderKey): ApiResult<CartTask> {
        if (account.automationPaused.value) return clientError(ErrorCode.POLICY_BLOCKED, "Automation is paused. Resume it in Settings → Help.")
        val p = providers.providers.value.firstOrNull { it.key == provider }
        if (p == null || p.status != ProviderStatus.ENABLED || !p.automationAvailable) {
            return clientError(ErrorCode.PROVIDER_DISABLED, "${provider.displayName} automation isn't available right now.")
        }
        if (!p.consent.automation) return clientError(ErrorCode.CONSENT_REQUIRED, "Allow automation for ${provider.displayName} first.")
        val adapter = device.adapterFor(provider) ?: return clientError(ErrorCode.PROVIDER_DISABLED, "${provider.displayName} can't be automated on this phone.")
        val quote = comparisons.cached(quoteId) ?: return clientError(ErrorCode.NOT_FOUND, "Comparison not found.")
        val items = quote.providerResults.firstOrNull { it.provider == provider }?.lines.orEmpty()
            .filter { it.match != LineMatch.UNAVAILABLE && it.quantity > 0 }
            .map { ItemRequest(listOfNotNull(it.matchedName ?: it.requestedName, it.matchedSize).joinToString(" "), it.quantity) }
        if (items.isEmpty()) return clientError(ErrorCode.VALIDATION_FAILED, "There's nothing to add in ${provider.displayName}.")

        // The plan names the app that will really be opened (in debug builds, SmartCart Test Store).
        val plan = planFor(adapter.appName, items)
        val task = CartTask(
            taskId = "tsk_dev_" + UUID.randomUUID().toString().take(8),
            provider = provider,
            quoteId = quoteId,
            status = TaskStatus.AWAITING_APPROVAL,
            createdAt = now(),
            expiresAt = clock.instant().plus(APPROVAL_WINDOW).toString(),
            limits = limits,
            plannedActions = plan.actions.map { it.action },
            events = emptyList(),
            approvalNonce = UUID.randomUUID().toString(),
        )
        synchronized(lock) {
            runs[task.taskId] = Run(adapter, plan)
            tasks.value = tasks.value + (task.taskId to task)
        }
        return ApiResult.Success(task)
    }

    override fun observe(taskId: String): Flow<CartTask?> = tasks.map { it[taskId] }.distinctUntilChanged()

    override suspend fun approve(taskId: String, actionIds: List<String>): ApiResult<CartTask> {
        val t = tasks.value[taskId] ?: return notFound()
        val run = runs.getValue(taskId)
        // Duplicate taps return the same result (idempotent approval).
        if (t.status != TaskStatus.AWAITING_APPROVAL) {
            return if (t.status in setOf(TaskStatus.APPROVED, TaskStatus.RUNNING)) ApiResult.Success(t) else conflict()
        }
        if (clock.instant().isAfter(Instant.parse(t.expiresAt))) {
            edit(taskId) { it.copy(status = TaskStatus.EXPIRED, events = it.events + event(it, TaskEventType.STATE_CHANGED, "Approval window expired")) }
            return clientError(ErrorCode.TASK_EXPIRED, "This request expired. Start again.")
        }
        if (actionIds.toSet() != t.plannedActions.map { it.actionId }.toSet()) {
            return clientError(ErrorCode.VALIDATION_FAILED, "Every planned step must be approved.")
        }
        val driver = device.driver() ?: return serviceOff()
        var won = false
        val approved = edit(taskId) {
            if (it.status != TaskStatus.AWAITING_APPROVAL) null
            else it.copy(status = TaskStatus.APPROVED, approvalNonce = null, events = it.events + event(it, TaskEventType.STATE_CHANGED, "Approved by you")).also { won = true }
        }!!
        if (won) start(taskId, run, driver, resumeFrom = 0)
        return ApiResult.Success(approved)
    }

    private fun start(taskId: String, run: Run, driver: Driver, resumeFrom: Int) {
        val base = tasks.value.getValue(taskId)
        val runner = TaskRunner(driver, device.verifier, run.adapter, run.plan, limits, killSwitch = { killed(base.provider) }, timing = timing)
        run.runner = runner
        run.job = scope.launch {
            val mirror = launch { runner.state.collect { mirror(taskId, run, runner, base, it) } }
            val end = runner.run(run.plan.actions.map { it.action.actionId }.toSet(), resumeFrom, base.actionsUsed)
            mirror.cancel()
            mirror(taskId, run, runner, base, end)
        }
    }

    /** Copies the runner's view into the task. Late updates from a stopped or replaced runner are dropped. */
    private fun mirror(taskId: String, run: Run, runner: TaskRunner, base: CartTask, s: RunState) {
        if (s.events.isEmpty()) return // not started yet
        run.step = s.step
        val offset = base.events.maxOfOrNull { it.seq } ?: 0
        edit(taskId) { t ->
            if (run.runner !== runner || t.status.isTerminal) null
            else t.copy(
                status = s.status,
                events = base.events + s.events.map { it.copy(seq = it.seq + offset) },
                actionsUsed = s.actionsUsed,
                checkpoint = s.checkpoint,
                needsUserAction = s.needsUserAction,
                cart = s.cart ?: t.cart,
                failure = s.failure,
            )
        }
    }

    private fun killed(provider: ProviderKey) =
        account.automationPaused.value || providers.providers.value.firstOrNull { it.key == provider }?.status == ProviderStatus.KILL_SWITCHED

    override suspend fun answerCheckpoint(taskId: String, checkpointId: String, approve: Boolean): ApiResult<CartTask> {
        val t = tasks.value[taskId] ?: return notFound()
        if (t.status != TaskStatus.CHECKPOINT || t.checkpoint?.checkpointId != checkpointId) return conflict()
        runs[taskId]?.runner?.answerCheckpoint(checkpointId, approve)
        return ApiResult.Success(t)
    }

    override suspend fun resume(taskId: String): ApiResult<CartTask> {
        val t = tasks.value[taskId] ?: return notFound()
        if (t.status != TaskStatus.NEEDS_USER_ACTION) return conflict()
        val driver = device.driver() ?: return serviceOff()
        val run = runs.getValue(taskId)
        var won = false
        val resumed = edit(taskId) {
            if (it.status != TaskStatus.NEEDS_USER_ACTION) null
            else it.copy(status = TaskStatus.RUNNING, needsUserAction = null, events = it.events + event(it, TaskEventType.STATE_CHANGED, "Resumed by you")).also { won = true }
        }!!
        if (won) start(taskId, run, driver, resumeFrom = run.step)
        return ApiResult.Success(resumed)
    }

    /** Takes effect at once, locally: the runner is told to stop and nothing it reports afterwards is applied. */
    override suspend fun stop(taskId: String, reason: StopReason): CartTask? {
        val run = runs[taskId]
        run?.runner?.stop()
        run?.runner = null
        run?.job?.cancel()
        val text = when (reason) {
            StopReason.EMERGENCY_STOP -> "Stopped by you (emergency stop)"
            StopReason.USER_CANCEL -> "Cancelled by you"
            StopReason.PAUSE_ALL -> "Stopped: all automation paused"
        }
        return edit(taskId) { t ->
            if (t.status.isTerminal || t.status == TaskStatus.CART_READY) null
            else t.copy(status = TaskStatus.CANCELLED, checkpoint = null, needsUserAction = null, events = t.events + event(t, TaskEventType.HALT, text))
        }
    }

    override suspend fun stopAll(reason: StopReason) {
        tasks.value.values.filter { it.status.isActive || it.status == TaskStatus.AWAITING_APPROVAL }.forEach { stop(it.taskId, reason) }
    }

    /**
     * Opening the cart in the partner app. Signed, single-use handoff tokens come with the server-side tasks
     * (slice 4.5); until then the handoff simply names the app and the steps.
     */
    override suspend fun createHandoff(taskId: String): ApiResult<Handoff> {
        val t = tasks.value[taskId] ?: return notFound()
        if (t.status != TaskStatus.CART_READY && t.status != TaskStatus.HANDED_OFF) return conflict()
        val app = runs.getValue(taskId).adapter
        return ApiResult.Success(
            Handoff(
                handoffUrl = "smartcart://handoff/device/$taskId",
                strategy = HandoffStrategy.APP_LAUNCH,
                targetPackage = app.packageName,
                expiresAt = clock.instant().plus(APPROVAL_WINDOW).toString(),
                manualSteps = listOf("Open ${app.appName}", "Open the cart", "Check the items and pay in ${app.appName}"),
            ),
        )
    }

    override suspend fun markHandedOff(taskId: String) {
        val app = runs[taskId]?.adapter ?: return
        edit(taskId) { t ->
            if (t.status != TaskStatus.CART_READY) null
            else t.copy(status = TaskStatus.HANDED_OFF, events = t.events + event(t, TaskEventType.HANDOFF_CREATED, "Opened ${app.appName} cart"))
        }
    }

    companion object {
        /** The same limits the contract fixtures use for a cart task. */
        val DEFAULT_LIMITS = TaskLimits(maxActions = 25, maxRuntimeSeconds = 180, inactivityTimeoutSeconds = 30)
        private val APPROVAL_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
