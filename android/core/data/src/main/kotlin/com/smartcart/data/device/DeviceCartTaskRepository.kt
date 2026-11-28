package com.smartcart.data.device

import com.smartcart.automation.Adapter
import com.smartcart.automation.Driver
import com.smartcart.automation.ExecutableAction
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
import com.smartcart.data.automation.TaskStateMachine
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * Phase 4.5: with a backend, the server holds the task. It issues the plan and the single-use nonce, the
     * phone reports every step (CR-005), and the server's answer has the last word: if it has stopped or failed
     * the task, the phone halts, and its check of the cart replaces the phone's.
     */
    private val server: TaskServer? = null,
    private val retryMillis: Long = 1_000,
) : CartTaskRepository {

    private class Run(val adapter: Adapter, val plan: ExecutablePlan) {
        @Volatile var runner: TaskRunner? = null
        @Volatile var job: Job? = null
        @Volatile var step: Int = 0
        // Reporting to the server (Phase 4.5)
        val changed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        @Volatile var reporter: Job? = null
        var reportSeq = 0
        var reportedEventSeq = 0
        /** The task's status as the server last confirmed it. */
        val onServer = MutableStateFlow<TaskStatus?>(null)
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
        server?.let { return createOnServer(it, quoteId, provider) }
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

    /** The server's task, with a plan the runner can carry out: its item steps matched to the snapshot's lines. */
    private suspend fun createOnServer(server: TaskServer, quoteId: String, provider: ProviderKey): ApiResult<CartTask> {
        val adapter = device.adapterFor(provider) ?: return clientError(ErrorCode.PROVIDER_DISABLED, "${provider.displayName} can't be automated on this phone.")
        val r = server.createTask(quoteId, provider)
        if (r !is ApiResult.Success) return r
        val t = r.value
        val items = comparisons.cached(quoteId)?.providerResults?.firstOrNull { it.provider == provider }?.lines.orEmpty()
            .filter { it.match != LineMatch.UNAVAILABLE && it.quantity > 0 }
            .map { ItemRequest(listOfNotNull(it.matchedName ?: it.requestedName, it.matchedSize).joinToString(" "), it.quantity) }
        val selects = t.plannedActions.count { it.type == com.smartcart.model.ActionType.SELECT_ITEM }
        if (selects != items.size) return clientError(ErrorCode.TASK_STATE_CONFLICT, "This task has moved on. Refresh and try again.")
        var next = 0
        val plan = ExecutablePlan(t.plannedActions.map { a -> ExecutableAction(a, if (a.type == com.smartcart.model.ActionType.SELECT_ITEM) items[next++] else null) })
        synchronized(lock) {
            runs[t.taskId] = Run(adapter, plan)
            tasks.value = tasks.value + (t.taskId to t)
        }
        return ApiResult.Success(t)
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
        server?.let { srv ->
            // The server checks the full plan and its nonce; only then does the phone touch anything.
            val ok = srv.approveTask(taskId, actionIds, t.approvalNonce ?: return conflict())
            if (ok !is ApiResult.Success) return ok
            edit(taskId) { ok.value }
            run.reportedEventSeq = ok.value.events.maxOfOrNull { it.seq } ?: 0
            startReporting(srv, taskId, run)
            start(taskId, run, driver, resumeFrom = 0)
            return ok
        }
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

    /**
     * Sends the phone's state to the server after each change, in order, one report at a time; a failed send is
     * retried with the same number (the server answers a repeat the same way). The server's answer wins when it
     * has ended the task or checked the cart.
     */
    private fun startReporting(server: TaskServer, taskId: String, run: Run) {
        run.reporter = scope.launch {
            for (unit in run.changed) {
                while (true) {
                    val t = tasks.value[taskId] ?: return@launch
                    val full = DeviceReports.progress(t, run.reportSeq + 1, run.reportedEventSeq, run.adapter) ?: break
                    val last = lastReported[taskId] ?: TaskStatus.APPROVED
                    if (full.events.isEmpty() && run.reportSeq > 0 && full.status == last) break
                    // Reports may lag the phone; D-002 has no shortcut such as APPROVED → CART_READY, so pass through
                    // RUNNING first (with the events), then send where the phone is now.
                    val bridge = full.status != last && full.status != TaskStatus.RUNNING &&
                        !TaskStateMachine.canTransition(last, full.status) && TaskStateMachine.canTransition(last, TaskStatus.RUNNING)
                    val report = if (bridge) full.copy(status = TaskStatus.RUNNING, checkpoint = null, needsUserAction = null, failure = null, cart = null) else full
                    when (val r = server.reportProgress(taskId, report)) {
                        is ApiResult.Success -> {
                            run.reportSeq = report.reportSeq
                            run.reportedEventSeq = maxOf(run.reportedEventSeq, report.events.maxOfOrNull { it.seq } ?: 0)
                            lastReported[taskId] = report.status
                            run.onServer.value = r.value.status
                            adopt(taskId, run, r.value)
                            break
                        }
                        is ApiResult.Failure -> {
                            if (r.error.code == ErrorCode.TASK_STATE_CONFLICT || r.error.code == ErrorCode.FORBIDDEN) {
                                // The server won't take this task's reports any more: read where it stands and follow it.
                                (server.getTask(taskId) as? ApiResult.Success)?.let { adopt(taskId, run, it.value) }
                                break
                            }
                            kotlinx.coroutines.delay(retryMillis)
                        }
                    }
                }
                if (tasks.value[taskId]?.let { it.status.isTerminal || it.status == TaskStatus.CART_READY } == true && run.runner == null) break
            }
        }
    }

    private val lastReported = ConcurrentHashMap<String, TaskStatus>()

    /** The server ended the task or checked the cart: its view replaces the phone's, and the runner stops. */
    private fun adopt(taskId: String, run: Run, server: CartTask) {
        val local = tasks.value[taskId] ?: return
        val ended = server.status.isTerminal && !local.status.isTerminal
        // Only a server that has ended the task or checked the cart is ahead of the phone; anything else is behind.
        val checked = server.status == TaskStatus.CART_READY && local.status == TaskStatus.CART_READY
        if (!ended && !checked) return
        if (ended) {
            run.runner?.stop()
            run.runner = null
            run.job?.cancel()
        }
        edit(taskId) { server }
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
        run.changed.trySend(Unit)
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
        if (won) {
            run.changed.trySend(Unit)
            start(taskId, run, driver, resumeFrom = run.step)
        }
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
        val stopped = edit(taskId) { t ->
            if (t.status.isTerminal || t.status == TaskStatus.CART_READY) null
            else t.copy(status = TaskStatus.CANCELLED, checkpoint = null, needsUserAction = null, events = t.events + event(t, TaskEventType.HALT, text))
        }
        // The server is told straight after; stopping never waits for the network.
        server?.let { srv -> if (run != null) scope.launch { srv.stopTask(taskId, reason); run.reporter?.cancel() } }
        return stopped
    }

    override suspend fun stopAll(reason: StopReason) {
        tasks.value.values.filter { it.status.isActive || it.status == TaskStatus.AWAITING_APPROVAL }.forEach { stop(it.taskId, reason) }
    }

    /**
     * Opening the cart in the partner app. Signed, single-use handoff tokens come with the server-side tasks
     * (slice 4.5); until then the handoff simply names the app and the steps.
     */
    override suspend fun createHandoff(taskId: String): ApiResult<Handoff> {
        server?.let { srv ->
            // The server issues the link only for a cart it has checked; wait for the phone's last report to land.
            runs[taskId]?.let { run -> withTimeoutOrNull(15_000) { run.onServer.first { it == TaskStatus.CART_READY || it?.isTerminal == true } } }
            val h = srv.handoff(taskId)
            if (h is ApiResult.Success) (srv.getTask(taskId) as? ApiResult.Success)?.let { got -> edit(taskId) { got.value } }
            return h
        }
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
        if (server != null) return // the server marks it when it issues the link
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
