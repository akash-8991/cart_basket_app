package com.smartcart.automation

import com.smartcart.automation.teststore.TestStoreAdapter
import com.smartcart.model.ActionRisk
import com.smartcart.model.ActionType
import com.smartcart.model.FailureCode
import com.smartcart.model.PlannedAction
import com.smartcart.model.TaskEventType
import com.smartcart.model.TaskLimits
import com.smartcart.model.TaskStatus
import com.smartcart.model.UserActionReason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRunnerTest {
    private val items = listOf(ItemRequest("Toned milk 1 L", 2), ItemRequest("Brown bread 400 g", 1))
    private val plan = ExecutablePlan.forCart("Test Store", items)
    private val adapter = TestStoreAdapter(setOf(FakeVerifier.GOOD_SIGNER))
    private val limits = TaskLimits(maxActions = 25, maxRuntimeSeconds = 180, inactivityTimeoutSeconds = 30)
    private val timing = RunnerTiming(pollMillis = 100, stepWaitMillis = 5_000, foreignPackageGraceMillis = 1_000)

    private fun TestScope.runner(
        store: FakeStore,
        p: ExecutablePlan = plan,
        verifier: PackageVerifier = FakeVerifier(),
        l: TaskLimits = limits,
        a: Adapter = adapter,
        kill: () -> Boolean = { false },
    ) = TaskRunner(store, verifier, a, p, l, kill, now = { testScheduler.currentTime }, timing = timing)

    private fun TestScope.store(scenario: String = "normal", latency: Long = 0) = FakeStore(scenario, { testScheduler.currentTime }, latency)
    private fun all(p: ExecutablePlan = plan) = p.actions.map { it.action.actionId }.toSet()

    @Test fun buildsTheApprovedCartAndVerifiesIt() = runTest {
        val s = store()
        val r = runner(s).run(all())
        assertEquals(r.failure.toString(), TaskStatus.CART_READY, r.status)
        val cart = r.cart!!
        assertTrue(cart.verified)
        assertFalse(cart.differsFromQuote)
        assertEquals(listOf(2, 1), cart.lines.map { it.quantity })
        assertEquals(2 * 2800L + 4500L, cart.totalEstimate!!.amountMinor)
        assertTrue(r.events.any { it.type == TaskEventType.CART_VERIFIED })
        assertEquals("milk: add, +1; bread: add; open cart, plus launch and typing", 9, r.actionsUsed)
        assertFalse("never goes near checkout", s.clicks.any { "pay" in it.lowercase() })
        assertEquals("events are numbered in order", r.events.indices.map { it + 1 }, r.events.map { it.seq })
    }

    @Test fun onlyTheFullPlanCanRun() = runTest {
        val s = store()
        val r = runner(s).run(all() - "a4")
        assertEquals(TaskStatus.BLOCKED_POLICY, r.status)
        assertEquals(0, s.launches)
    }

    @Test fun prohibitedOrUnsupportedStepsNeverStart() = runTest {
        val pay = ExecutablePlan(plan.actions + ExecutableAction(PlannedAction("a9", ActionType.VERIFY_CART, "Pay for the order", ActionRisk.PROHIBITED)))
        assertEquals(TaskStatus.BLOCKED_POLICY, runner(store(), pay).run(all(pay)).status)
        val address = ExecutablePlan(plan.actions + ExecutableAction(PlannedAction("a9", ActionType.CHANGE_ADDRESS, "Change the address", ActionRisk.HIGH)))
        val s = store()
        assertEquals(TaskStatus.BLOCKED_POLICY, runner(s, address).run(all(address)).status)
        assertEquals(0, s.launches)
    }

    @Test fun theAppMustBeInstalledAndGenuine() = runTest {
        assertEquals(FailureCode.APP_NOT_INSTALLED, runner(store(), verifier = FakeVerifier(installed = false)).run(all()).failure!!.code)
        val fake = runner(store(), verifier = FakeVerifier(signers = setOf("evil"))).run(all())
        assertEquals(TaskStatus.FAILED_SAFE, fake.status)
        assertEquals(FailureCode.PACKAGE_MISMATCH, fake.failure!!.code)
    }

    @Test fun theKillSwitchStopsATaskBeforeOrDuring() = runTest {
        assertEquals(FailureCode.PROVIDER_KILL_SWITCH, runner(store(), kill = { true }).run(all()).failure!!.code)
        val s = store()
        val r = runner(s, kill = { s.clicks.size >= 2 }).run(all()) // switched off after the second tap
        assertEquals(FailureCode.PROVIDER_KILL_SWITCH, r.failure!!.code)
        assertEquals("no interaction after the switch", 2, s.clicks.size)
    }

    @Test fun promptsOnlyThePersonCanAnswerHandThePhoneBack() = runTest {
        mapOf("otp" to UserActionReason.OTP_REQUIRED, "login" to UserActionReason.LOGIN_REQUIRED, "captcha" to UserActionReason.CAPTCHA).forEach { (scenario, reason) ->
            val s = store(scenario)
            val r = runner(s).run(all())
            assertEquals(scenario, TaskStatus.NEEDS_USER_ACTION, r.status)
            assertEquals(scenario, reason, r.needsUserAction!!.reason)
            assertTrue("$scenario: nothing touched", s.clicks.isEmpty())
        }
    }

    @Test fun aPaymentScreenEndsTheTaskWithoutTouchingIt() = runTest {
        val s = store("payment_after_add")
        val r = runner(s).run(all())
        assertEquals(TaskStatus.BLOCKED_POLICY, r.status)
        assertEquals(FailureCode.POLICY_BLOCKED, r.failure!!.code)
        assertFalse(s.clicks.any { it.contains("Pay", ignoreCase = true) })
    }

    @Test fun commitControlsAndSecretFieldsAreRefusedWhateverTheAdapterSays() = runTest {
        val rogue = object : Adapter by adapter {
            override suspend fun perform(step: ExecutableAction, ctx: AdapterContext) {
                if (step.action.type != ActionType.VERIFY_CART) return adapter.perform(step, ctx)
                val home = ctx.awaitScreen("home", "results")
                ctx.tap(home, Selector.Id("open_cart"))
                ctx.tap(ctx.awaitScreen("cart"), Selector.Id("checkout")) // "Proceed to pay"
            }
        }
        val s = store()
        val r = runner(s, a = rogue).run(all())
        assertEquals(TaskStatus.BLOCKED_POLICY, r.status)
        assertFalse(s.clicks.contains("Proceed to pay"))

        assertTrue(Safety.forbiddenToType(UiNode(id = "x:id/pw", password = true)))
        assertTrue(Safety.forbiddenToType(UiNode(id = "x:id/field", hint = "Enter OTP", editable = true)))
        assertTrue(Safety.forbiddenToType(UiNode(id = "x:id/upi_id", editable = true)))
        assertFalse(Safety.forbiddenToType(UiNode(id = "x:id/search_box", hint = "Search products", editable = true)))
        listOf("Place order", "Pay ₹245", "Book ride", "Confirm booking", "Slide to pay").forEach { assertTrue(it, Safety.forbiddenToTap(UiNode(text = it))) }
        listOf("Add Toned milk 1 L", "Open cart", "Search", "Increase Eggs 12 pack").forEach { assertFalse(it, Safety.forbiddenToTap(UiNode(text = it))) }
    }

    @Test fun wordingAloneDoesNotFlagAKnownScreen() {
        val cart = ScreenSnapshot(TestStoreAdapter.PACKAGE, UiNode(children = listOf(
            UiNode(id = "${TestStoreAdapter.PACKAGE}:id/cart_title", text = "Your cart"),
            UiNode(id = "${TestStoreAdapter.PACKAGE}:id/cart_total", text = "₹101.00"),
            UiNode(text = "Share the OTP with the delivery partner"),
        )))
        val known = adapter.screens.first { it.matches(cart) }
        assertNull(Safety.hazard(cart, known))
        assertEquals(Hazard.OTP, Safety.hazard(cart, null))
    }

    @Test fun anUnexpectedDialogNeedsThePerson() = runTest {
        val r = runner(store("dialog")).run(all())
        assertEquals(TaskStatus.NEEDS_USER_ACTION, r.status)
        assertEquals(UserActionReason.UNEXPECTED_DIALOG, r.needsUserAction!!.reason)
    }

    @Test fun anUnknownScreenHalts() = runTest {
        val r = runner(store("redesign")).run(all())
        assertEquals(TaskStatus.FAILED_SAFE, r.status)
        assertEquals(FailureCode.UNKNOWN_SCREEN, r.failure!!.code)
    }

    @Test fun anotherAppInFrontHalts() = runTest {
        val r = runner(store("leave_app")).run(all())
        assertEquals(FailureCode.PACKAGE_MISMATCH, r.failure!!.code)
    }

    @Test fun anUnavailableItemNeedsThePerson() = runTest {
        val r = runner(store("out_of_stock")).run(all())
        assertEquals(TaskStatus.NEEDS_USER_ACTION, r.status)
        assertEquals(UserActionReason.ITEM_UNAVAILABLE, r.needsUserAction!!.reason)
    }

    @Test fun aCartThatDriftedFailsVerification() = runTest {
        val r = runner(store("cart_drift")).run(all())
        assertEquals(TaskStatus.FAILED_SAFE, r.status)
        assertEquals(FailureCode.CART_VERIFICATION_FAILED, r.failure!!.code)
        assertFalse(r.cart!!.verified)
        assertTrue(r.cart!!.differsFromQuote)
        assertTrue(r.failure!!.message, "Toned milk 500 ml" in r.failure!!.message)
    }

    @Test fun limitsOnActionsAndRuntime() = runTest {
        val few = runner(store(), l = limits.copy(maxActions = 4)).run(all())
        assertEquals(FailureCode.MAX_ACTIONS_REACHED, few.failure!!.code)
        assertEquals(4, few.actionsUsed)
        val slow = runner(store(latency = 2_000), l = limits.copy(maxRuntimeSeconds = 10)).run(all())
        assertEquals(FailureCode.RUNTIME_EXCEEDED, slow.failure!!.code)
    }

    @Test fun theEmergencyStopTakesEffectBeforeTheNextInteraction() = runTest {
        val s = store(latency = 500)
        val r = runner(s)
        val job = async { r.run(all()) }
        advanceTimeBy(1_600); runCurrent()
        val before = s.clicks.size
        r.stop()
        val end = job.await()
        assertEquals(TaskStatus.CANCELLED, end.status)
        assertEquals(before, s.clicks.size)
    }

    @Test fun highRiskStepsWaitForTheUser() = runTest {
        val risky = ExecutablePlan(plan.actions.map { if (it.item?.name == "Brown bread 400 g") it.copy(action = it.action.copy(risk = ActionRisk.HIGH)) else it })
        // Approve: the task carries on, and the time spent deciding doesn't count against the runtime.
        val s = store()
        val r = runner(s, risky, l = limits.copy(maxRuntimeSeconds = 10))
        val job = async { r.run(all(risky)) }
        advanceTimeBy(500); runCurrent()
        assertEquals(TaskStatus.CHECKPOINT, r.state.value.status)
        val cp = r.state.value.checkpoint!!
        advanceTimeBy(60_000)
        r.answerCheckpoint("wrong-id", true)
        assertEquals(TaskStatus.CHECKPOINT, r.state.value.status)
        r.answerCheckpoint(cp.checkpointId, true)
        assertEquals(TaskStatus.CART_READY, job.await().status)

        // Reject: cancelled before the risky step touches anything.
        val s2 = store()
        val r2 = runner(s2, risky)
        val job2 = async { r2.run(all(risky)) }
        advanceTimeBy(500); runCurrent()
        val clicks = s2.clicks.size
        r2.answerCheckpoint(r2.state.value.checkpoint!!.checkpointId, false)
        assertEquals(TaskStatus.CANCELLED, job2.await().status)
        assertEquals(clicks, s2.clicks.size)
    }

    @Test fun pricesParse() {
        assertEquals(123450L, parseRupeesMinor("₹1,234.50"))
        assertEquals(5400L, parseRupeesMinor("Rs 54"))
        assertEquals(500L, parseRupeesMinor("₹5.0"))
        assertNull(parseRupeesMinor("free"))
    }
}
