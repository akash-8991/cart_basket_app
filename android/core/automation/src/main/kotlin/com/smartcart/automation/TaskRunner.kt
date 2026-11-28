package com.smartcart.automation

import com.smartcart.model.ActionRisk
import com.smartcart.model.ActionType
import com.smartcart.model.Checkpoint
import com.smartcart.model.FailureCode
import com.smartcart.model.LineMatch
import com.smartcart.model.MatchedLine
import com.smartcart.model.Money
import com.smartcart.model.NeedsUserAction
import com.smartcart.model.TaskEvent
import com.smartcart.model.TaskEventType
import com.smartcart.model.TaskFailure
import com.smartcart.model.TaskLimits
import com.smartcart.model.TaskStatus
import com.smartcart.model.UserActionReason
import com.smartcart.model.VerifiedCart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/** Ends a task from anywhere inside a step. */
class Halted(val outcome: Outcome) : RuntimeException(outcome.toString(), null, false, false)

sealed interface Outcome {
    data class NeedsUser(val reason: UserActionReason, val message: String) : Outcome
    data class Blocked(val message: String) : Outcome
    data class Failed(val code: FailureCode, val message: String) : Outcome
    data object Stopped : Outcome
    data object Rejected : Outcome
}

/** The runner's view of the task, in the contract's terms (CartTask fields). */
data class RunState(
    val status: TaskStatus = TaskStatus.APPROVED,
    val events: List<TaskEvent> = emptyList(),
    val actionsUsed: Int = 0,
    val checkpoint: Checkpoint? = null,
    val needsUserAction: NeedsUserAction? = null,
    val cart: VerifiedCart? = null,
    val failure: TaskFailure? = null,
    /** Index in the plan of the step running (or that halted); a resumed run starts from it. */
    val step: Int = 0,
)

/** Timing knobs. Defaults suit real phones; tests shrink them. */
data class RunnerTiming(
    val pollMillis: Long = 150,
    /** How long one step may wait for the next expected screen before deciding what went wrong. */
    val stepWaitMillis: Long = 8_000,
    /** How long another app may be in front (e.g. during launch) before it counts as a package mismatch. */
    val foreignPackageGraceMillis: Long = 2_000,
)

/**
 * Runs one approved cart task on the phone (Phase 4.1). Deterministic code, no model involved: it executes
 * exactly the approved plan through [adapter], and stops the moment anything is off (threat model §4):
 * not the full plan approved, prohibited action, kill switch, app missing or wrongly signed, another app in
 * front, payment/login/OTP/password/CAPTCHA/biometric screen, unknown screen, unexpected dialog, too many
 * actions, too long, nothing happening, a high-risk step the user rejects, or the emergency stop.
 */
class TaskRunner(
    private val driver: Driver,
    private val verifier: PackageVerifier,
    private val adapter: Adapter,
    private val plan: ExecutablePlan,
    private val limits: TaskLimits,
    private val killSwitch: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val timing: RunnerTiming = RunnerTiming(),
) {
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state

    @Volatile private var stopRequested = false
    private var checkpointAnswer: CompletableDeferred<Boolean>? = null
    private var startedAt = 0L
    private var lastProgressAt = 0L
    private var reading: CartReading? = null
    private var seq = 0

    /** Emergency stop / cancel: takes effect before the next interaction, and at once if waiting. */
    fun stop() {
        stopRequested = true
        checkpointAnswer?.complete(false)
    }

    /** Answers the open checkpoint. Ignored if [checkpointId] isn't the one open. */
    fun answerCheckpoint(checkpointId: String, approve: Boolean) {
        if (_state.value.checkpoint?.checkpointId == checkpointId) checkpointAnswer?.complete(approve)
    }

    /**
     * Runs the plan. After the person has dealt with something only they can do (NEEDS_USER_ACTION), the task
     * is run again from the step that halted: [resumeFrom] is that step and [actionsAlreadyUsed] keeps the
     * action limit counting across the whole task. The full plan must still be the approved one, the app is
     * checked again, and the cart is verified against every approved item.
     */
    suspend fun run(approvedActionIds: Set<String>, resumeFrom: Int = 0, actionsAlreadyUsed: Int = 0): RunState {
        startedAt = now(); lastProgressAt = startedAt
        _state.update { it.copy(actionsUsed = actionsAlreadyUsed, step = resumeFrom) }
        val outcome = try {
            preflight(approvedActionIds)
            status(TaskStatus.RUNNING, if (resumeFrom > 0 || actionsAlreadyUsed > 0) "Continuing in ${adapter.appName}" else "Started in ${adapter.appName}")
            for (i in resumeFrom until plan.actions.size) {
                _state.update { it.copy(step = i) }
                runStep(plan.actions[i])
            }
            null
        } catch (h: Halted) {
            h.outcome
        }
        finish(outcome)
        return _state.value
    }

    // ------------------------------------------------------------------ preflight

    private fun preflight(approved: Set<String>) {
        val ids = plan.actions.map { it.action.actionId }.toSet()
        // Full-plan approval only (A-06): every step, and nothing else.
        if (approved != ids) throw Halted(Outcome.Blocked("Only the full plan you saw can be approved"))
        plan.actions.firstOrNull { it.action.risk == ActionRisk.PROHIBITED }?.let { throw Halted(Outcome.Blocked("“${it.action.description}” is never automated")) }
        plan.actions.firstOrNull { it.action.type !in adapter.supported }?.let { throw Halted(Outcome.Blocked("${adapter.appName} can't do “${it.action.description}” automatically")) }
        if (killSwitch()) throw Halted(Outcome.Failed(FailureCode.PROVIDER_KILL_SWITCH, "${adapter.appName} automation is switched off"))
        if (!verifier.isInstalled(adapter.packageName)) throw Halted(Outcome.Failed(FailureCode.APP_NOT_INSTALLED, "${adapter.appName} isn't installed"))
        val signers = verifier.signingDigests(adapter.packageName)
        if (signers.isEmpty() || signers.none { it in adapter.trustedSigners }) {
            throw Halted(Outcome.Failed(FailureCode.PACKAGE_MISMATCH, "This copy of ${adapter.appName} isn't the genuine app"))
        }
    }

    // ------------------------------------------------------------------ steps

    private suspend fun runStep(step: ExecutableAction) {
        val a = step.action
        checkLive()
        if (a.risk == ActionRisk.HIGH) askCheckpoint(a.actionId, a.description)
        event(TaskEventType.ACTION_STARTED, a.description, a.actionId)
        adapter.perform(step, ctx)
        if (a.type == ActionType.VERIFY_CART) verifyCart() else event(TaskEventType.ACTION_SUCCEEDED, a.description, a.actionId)
        lastProgressAt = now()
    }

    private suspend fun askCheckpoint(actionId: String, description: String) {
        val cp = Checkpoint("cp_$actionId", "$description. Continue?", ActionRisk.HIGH)
        val answer = CompletableDeferred<Boolean>().also { checkpointAnswer = it }
        _state.update { it.copy(status = TaskStatus.CHECKPOINT, checkpoint = cp) }
        event(TaskEventType.CHECKPOINT_RAISED, cp.prompt, actionId)
        val asked = now()
        val yes = answer.await()
        startedAt += now() - asked // time spent deciding doesn't count against the task's runtime
        checkpointAnswer = null
        _state.update { it.copy(checkpoint = null) }
        if (stopRequested) throw Halted(Outcome.Stopped)
        event(TaskEventType.CHECKPOINT_ANSWERED, if (yes) "You chose to continue" else "You chose to stop", actionId)
        if (!yes) throw Halted(Outcome.Rejected)
        _state.update { it.copy(status = TaskStatus.RUNNING) }
        lastProgressAt = now()
    }

    private fun verifyCart() {
        val r = reading ?: throw Halted(Outcome.Failed(FailureCode.CART_VERIFICATION_FAILED, "The cart couldn't be read"))
        val lines = plan.items.mapIndexed { i, want ->
            val got = r.lines.firstOrNull { it.name.equals(want.name, ignoreCase = true) }
            MatchedLine(
                lineId = "l${i + 1}", requestedName = want.name, matchedName = got?.name, quantity = got?.quantity ?: 0,
                lineTotal = got?.lineTotalMinor?.let { Money(it, r.currency) },
                match = if (got == null) LineMatch.UNAVAILABLE else LineMatch.EXACT,
            )
        }
        val wrong = plan.items.filter { want -> r.lines.none { it.name.equals(want.name, true) && it.quantity == want.quantity } }
        val extra = r.lines.filter { got -> plan.items.none { it.name.equals(got.name, true) } }
        val ok = wrong.isEmpty() && extra.isEmpty()
        val cart = VerifiedCart(ok, if (ok) iso(now()) else null, lines, r.totalMinor?.let { Money(it, r.currency) }, differsFromQuote = !ok)
        _state.update { it.copy(cart = cart) }
        if (!ok) {
            val what = (wrong.map { "${it.name} × ${it.quantity}" } + extra.map { "extra ${it.name}" }).joinToString(", ")
            throw Halted(Outcome.Failed(FailureCode.CART_VERIFICATION_FAILED, "The cart doesn't match what you approved: $what"))
        }
        event(TaskEventType.CART_VERIFIED, "Cart checked: ${plan.items.size} items match")
    }

    // ------------------------------------------------------------------ the guarded context

    private val ctx = object : AdapterContext {
        override suspend fun launch() {
            spend()
            if (!driver.launch(adapter.packageName)) throw Halted(Outcome.Failed(FailureCode.APP_NOT_INSTALLED, "${adapter.appName} couldn't be opened"))
        }

        override suspend fun awaitScreen(vararg names: String, condition: (ScreenSnapshot) -> Boolean): ScreenSnapshot {
            val since = now()
            var foreignSince: Long? = null
            var last: ScreenSnapshot? = null
            while (true) {
                checkLive()
                val s = driver.snapshot()
                if (s != null) {
                    if (s.packageName != adapter.packageName) {
                        val t = foreignSince ?: now().also { foreignSince = it }
                        if (now() - t >= timing.foreignPackageGraceMillis) {
                            throw Halted(Outcome.Failed(FailureCode.PACKAGE_MISMATCH, "Another app came to the front, so I stopped"))
                        }
                    } else {
                        foreignSince = null
                        last = s
                        val known = adapter.screens.firstOrNull { it.matches(s) }
                        Safety.hazard(s, known)?.let { throw Halted(hazardOutcome(it)) }
                        if (known != null && known.name in names && condition(s)) return s
                    }
                }
                if (now() - since >= timing.stepWaitMillis.coerceAtMost(limits.inactivityTimeoutSeconds * 1000L)) throw Halted(stuck(last))
                delay(timing.pollMillis)
            }
        }

        override suspend fun tap(screen: ScreenSnapshot, selector: Selector) {
            val n = screen.first(selector) ?: throw Halted(Outcome.Failed(FailureCode.UNKNOWN_SCREEN, "Expected control not found"))
            // Defence in depth: whatever an adapter asks, commit controls are never touched.
            if (Safety.forbiddenToTap(n)) throw Halted(Outcome.Blocked("Paying or placing the order is always left to you"))
            spend()
            if (!driver.click(n)) throw Halted(Outcome.Failed(FailureCode.UNKNOWN_SCREEN, "A control didn't respond"))
        }

        override suspend fun type(screen: ScreenSnapshot, selector: Selector, text: String) {
            val n = screen.first(selector) ?: throw Halted(Outcome.Failed(FailureCode.UNKNOWN_SCREEN, "Expected field not found"))
            if (Safety.forbiddenToType(n)) throw Halted(Outcome.Blocked("Secrets and payment details are never typed automatically"))
            spend()
            if (!driver.setText(n, text)) throw Halted(Outcome.Failed(FailureCode.UNKNOWN_SCREEN, "A field didn't accept text"))
        }

        override suspend fun back() {
            spend()
            driver.back()
        }

        override fun needsUser(reason: UserActionReason, message: String): Nothing = throw Halted(Outcome.NeedsUser(reason, message))

        override fun cartRead(reading: CartReading) { this@TaskRunner.reading = reading }
    }

    private fun hazardOutcome(h: Hazard): Outcome {
        val app = adapter.appName
        val then = "then come back and resume. I never type passwords or codes."
        return when (h) {
            Hazard.PAYMENT -> Outcome.Blocked("A payment screen appeared. Paying is always left to you")
            Hazard.LOGIN -> Outcome.NeedsUser(h.userReason!!, "$app wants you to sign in. Sign in yourself in $app, $then")
            Hazard.PASSWORD -> Outcome.NeedsUser(h.userReason!!, "$app is asking for your password. Enter it yourself, $then")
            Hazard.OTP -> Outcome.NeedsUser(h.userReason!!, "$app is asking for a one-time code. Enter it yourself, $then")
            Hazard.CAPTCHA -> Outcome.NeedsUser(h.userReason!!, "$app wants to check you're human. Do that yourself, $then")
            Hazard.BIOMETRIC -> Outcome.NeedsUser(h.userReason!!, "$app is asking for your fingerprint or face. Do that yourself, $then")
        }
    }

    /** Waited too long: say why as precisely as the screen allows. */
    private fun stuck(last: ScreenSnapshot?): Outcome {
        if (last == null) return Outcome.Failed(FailureCode.INACTIVITY_TIMEOUT, "${adapter.appName} stopped responding")
        val known = adapter.screens.firstOrNull { it.matches(last) }
        return when {
            known == null && last.isSystemStyleDialog -> Outcome.NeedsUser(UserActionReason.UNEXPECTED_DIALOG, "${adapter.appName} showed a message that needs you. Deal with it in ${adapter.appName}, then come back and resume.")
            known == null -> Outcome.Failed(FailureCode.UNKNOWN_SCREEN, "${adapter.appName} showed a screen I don't recognise, so I stopped")
            else -> Outcome.Failed(FailureCode.INACTIVITY_TIMEOUT, "${adapter.appName} didn't do what was expected")
        }
    }

    /** Checks done before every interaction and while waiting. */
    private fun checkLive() {
        if (stopRequested) throw Halted(Outcome.Stopped)
        if (killSwitch()) throw Halted(Outcome.Failed(FailureCode.PROVIDER_KILL_SWITCH, "${adapter.appName} automation was switched off"))
        if (now() - startedAt > limits.maxRuntimeSeconds * 1000L) throw Halted(Outcome.Failed(FailureCode.RUNTIME_EXCEEDED, "This took too long, so I stopped"))
        if (now() - lastProgressAt > limits.inactivityTimeoutSeconds * 1000L) throw Halted(Outcome.Failed(FailureCode.INACTIVITY_TIMEOUT, "Nothing happened for too long, so I stopped"))
    }

    /** Counts one interaction with the partner app against the task's limit. */
    private fun spend() {
        checkLive()
        if (_state.value.actionsUsed >= limits.maxActions) throw Halted(Outcome.Failed(FailureCode.MAX_ACTIONS_REACHED, "Reached the most steps allowed, so I stopped"))
        _state.update { it.copy(actionsUsed = it.actionsUsed + 1) }
        lastProgressAt = now()
    }

    // ------------------------------------------------------------------ outcome

    private fun finish(o: Outcome?) {
        when (o) {
            null -> status(TaskStatus.CART_READY, "Your cart is ready in ${adapter.appName}")
            // A halt is one line in the timeline: the reason, with the status change it causes.
            is Outcome.NeedsUser -> {
                _state.update { it.copy(needsUserAction = NeedsUserAction(o.reason, o.message)) }
                halt(TaskStatus.NEEDS_USER_ACTION, o.message)
            }
            is Outcome.Blocked -> {
                _state.update { it.copy(failure = TaskFailure(FailureCode.POLICY_BLOCKED, o.message)) }
                halt(TaskStatus.BLOCKED_POLICY, o.message)
            }
            is Outcome.Failed -> {
                _state.update { it.copy(failure = TaskFailure(o.code, o.message)) }
                halt(TaskStatus.FAILED_SAFE, o.message)
            }
            Outcome.Stopped -> halt(TaskStatus.CANCELLED, "Stopped")
            Outcome.Rejected -> status(TaskStatus.CANCELLED, "You chose to stop")
        }
    }

    private fun halt(s: TaskStatus, message: String) {
        _state.update { it.copy(status = s) }
        event(TaskEventType.HALT, message)
    }

    private fun status(s: TaskStatus, description: String) {
        _state.update { it.copy(status = s) }
        event(TaskEventType.STATE_CHANGED, description)
    }

    private fun event(type: TaskEventType, description: String, actionId: String? = null) {
        _state.update { it.copy(events = it.events + TaskEvent(++seq, iso(now()), type, description, actionId)) }
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
}
