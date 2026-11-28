package com.smartcart.automation

import com.smartcart.model.TaskLimits
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Reads an app's prices, stock and fees for a list of items, on the phone (Phase 4.3). It runs a read-only
 * [ExecutablePlan.forPriceCheck] through the same [TaskRunner] as cart tasks, so every guard applies: the
 * app must be the genuine one, nothing but opening and searching is allowed, cart controls and payment are
 * never tapped, and login/OTP/CAPTCHA/payment/unknown screens end the check. A price check never asks the
 * person for anything: if the app needs them, the check simply doesn't read that app.
 */
class PriceCapture(
    private val driver: Driver,
    private val verifier: PackageVerifier,
    private val adapter: Adapter,
    private val limits: TaskLimits = DEFAULT_LIMITS,
    private val killSwitch: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val timing: RunnerTiming = RunnerTiming(),
) {
    sealed interface Result {
        data class Read(val offers: List<OfferReading>, val fees: FeeReading?) : Result
        /** Nothing usable: [reason] says why, in words the person can act on. */
        data class NotRead(val reason: String) : Result
    }

    @Volatile private var runner: TaskRunner? = null

    fun stop() { runner?.stop() }

    /** Runs the check; [progress] gets (items read, items to read) as results come in. */
    suspend fun run(items: List<ItemRequest>, progress: (Int, Int) -> Unit = { _, _ -> }): Result = coroutineScope {
        val plan = ExecutablePlan.forPriceCheck(adapter.appName, items)
        val r = TaskRunner(driver, verifier, adapter, plan, limits, killSwitch, now, timing).also { runner = it }
        val watch = launch(start = CoroutineStart.UNDISPATCHED) { r.state.collect { progress(it.offers.size, items.size) } }
        val end = r.run(plan.actions.map { it.action.actionId }.toSet())
        watch.cancel()
        progress(end.offers.size, items.size)
        if (end.status == TaskStatus.CART_READY) Result.Read(end.offers, end.fees)
        else Result.NotRead(end.needsUserAction?.message ?: end.failure?.message ?: "The price check was stopped")
    }

    companion object {
        /** Opening plus two actions (type, search) per item leaves room for about a dozen items. */
        val DEFAULT_LIMITS = TaskLimits(maxActions = 25, maxRuntimeSeconds = 120, inactivityTimeoutSeconds = 30)
    }
}
