package com.smartcart.data.device

import com.smartcart.automation.ExecutablePlan
import com.smartcart.automation.FakeStore
import com.smartcart.automation.FakeVerifier
import com.smartcart.automation.ItemRequest
import com.smartcart.automation.RunnerTiming
import com.smartcart.automation.teststore.TestStoreAdapter
import com.smartcart.data.ApiResult
import com.smartcart.data.FileFixtureSource
import com.smartcart.data.Fixtures
import com.smartcart.data.fake.FakeAccountRepository
import com.smartcart.data.fake.FakeCartTaskRepository
import com.smartcart.data.fake.FakeComparisonRepository
import com.smartcart.data.fake.FakeProviderRepository
import com.smartcart.data.mock.MockConfig
import com.smartcart.data.mock.MockControl
import com.smartcart.model.ActionRisk
import com.smartcart.model.ActionType
import com.smartcart.model.CartTask
import com.smartcart.model.Category
import com.smartcart.model.ErrorCode
import com.smartcart.model.FailureCode
import com.smartcart.model.HandoffStrategy
import com.smartcart.model.Objective
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteRequest
import com.smartcart.model.StopReason
import com.smartcart.model.TaskEventType
import com.smartcart.model.TaskStatus
import com.smartcart.model.UserActionReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 4.2: cart tasks run on the phone. The JVM Test Store ([FakeStore]) stands in for the real one, and the
 * 4.1 runner does the work; these tests check what the person sees through [com.smartcart.data.CartTaskRepository].
 */
class DeviceCartTaskRepositoryTest {
    private val fixtures = Fixtures(FileFixtureSource(File(System.getProperty("fixturesDir"))))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timing = RunnerTiming(pollMillis = 5, stepWaitMillis = 400, foreignPackageGraceMillis = 100)

    @After fun tearDown() = scope.cancel()

    private class Phone(val store: FakeStore, var connected: Boolean = true) : OnDeviceAutomation {
        override fun driver() = if (connected) store else null
        override val verifier = FakeVerifier()
        override fun adapterFor(provider: ProviderKey) =
            if (provider.category == Category.GROCERY) TestStoreAdapter(setOf(FakeVerifier.GOOD_SIGNER)) else null
    }

    private class Env(val phone: Phone, val mock: MockControl, val providers: FakeProviderRepository, val account: FakeAccountRepository, val comparisons: FakeComparisonRepository, val tasks: DeviceCartTaskRepository) {
        val store get() = phone.store
    }

    private fun env(
        scenario: String = "normal",
        latencyMs: Long = 0,
        planFor: (String, List<ItemRequest>) -> ExecutablePlan = ExecutablePlan::forCart,
    ): Env {
        val mock = MockControl(MockConfig(latencyMs = 0))
        val providers = FakeProviderRepository(fixtures, mock)
        val account = FakeAccountRepository(fixtures, mock)
        val comparisons = FakeComparisonRepository(fixtures, mock)
        val phone = Phone(FakeStore(scenario, now = System::currentTimeMillis, latencyMs = latencyMs))
        return Env(phone, mock, providers, account, comparisons, DeviceCartTaskRepository(phone, comparisons, providers, account, scope, timing = timing, planFor = planFor))
    }

    private suspend fun Env.quote(vararg p: ProviderKey = arrayOf(ProviderKey.BLINKIT)): String =
        (comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = p.toList(), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value

    private suspend fun Env.newTask(): CartTask = (tasks.create(quote(), ProviderKey.BLINKIT) as ApiResult.Success).value

    private suspend fun Env.approveAll(t: CartTask) = tasks.approve(t.taskId, t.plannedActions.map { it.actionId })

    private suspend fun Env.until(taskId: String, what: (CartTask) -> Boolean): CartTask =
        withTimeout(10_000) { tasks.observe(taskId).first { it != null && what(it) }!! }

    private fun CartTask.timeline() = events.joinToString("\n") { "${it.seq} ${it.type} ${it.description}" }

    @Test fun aRealCartIsBuiltOnThePhoneAfterFullApproval() = runBlocking {
        val e = env()
        val t = e.newTask()
        assertEquals(TaskStatus.AWAITING_APPROVAL, t.status)
        assertEquals("the plan names the app that is really opened", "Open Test Store", t.plannedActions.first().description)
        assertEquals(
            listOf("Add Toned milk 1 L × 2", "Add Brown bread 400 g × 1", "Add Eggs 12 pack × 1", "Add Bananas 6 pcs × 1"),
            t.plannedActions.filter { it.type == ActionType.SELECT_ITEM }.map { it.description },
        )
        assertEquals("nothing is touched before approval", 0, e.store.launches)

        assertTrue(e.approveAll(t) is ApiResult.Success)
        val done = e.until(t.taskId) { it.status.isTerminal || it.status == TaskStatus.CART_READY }
        assertEquals(done.timeline(), TaskStatus.CART_READY, done.status)
        val cart = done.cart!!
        assertTrue(cart.verified)
        assertEquals(listOf(2, 1, 1, 1), cart.lines.map { it.quantity })
        assertEquals(2 * 2800L + 4500 + 8400 + 4800, cart.totalEstimate!!.amountMinor)
        assertEquals("Approved by you", done.events.first().description)
        assertTrue(done.events.any { it.type == TaskEventType.CART_VERIFIED })
        assertEquals("event numbers are unique and in order", done.events.map { it.seq }.sorted().distinct(), done.events.map { it.seq })
        assertTrue(done.actionsUsed in 1..done.limits.maxActions)
        assertNull("no active task once the cart is ready", e.tasks.activeTask.value)
        assertFalse("payment is never touched", e.store.clicks.any { "pay" in it.lowercase() })

        val h = (e.tasks.createHandoff(t.taskId) as ApiResult.Success).value
        assertEquals(HandoffStrategy.APP_LAUNCH, h.strategy)
        assertEquals(TestStoreAdapter.PACKAGE, h.targetPackage)
        e.tasks.markHandedOff(t.taskId)
        assertEquals(TaskStatus.HANDED_OFF, e.tasks.observe(t.taskId).first()!!.status)
    }

    @Test fun nothingRunsWhileTheAccessibilityServiceIsOff() = runBlocking {
        val e = env()
        e.phone.connected = false
        val t = e.newTask()
        val r = e.approveAll(t)
        assertTrue(r is ApiResult.Failure && r.error.code == ErrorCode.CONSENT_REQUIRED)
        assertEquals(TaskStatus.AWAITING_APPROVAL, e.tasks.observe(t.taskId).first()!!.status)
        assertEquals(0, e.store.launches)

        e.phone.connected = true
        e.approveAll(t)
        assertEquals(TaskStatus.CART_READY, e.until(t.taskId) { it.status == TaskStatus.CART_READY }.status)
    }

    @Test fun onlyTheFullPlanCanBeApproved() = runBlocking {
        val e = env()
        val t = e.newTask()
        val r = e.tasks.approve(t.taskId, t.plannedActions.drop(1).map { it.actionId })
        assertTrue(r is ApiResult.Failure && r.error.code == ErrorCode.VALIDATION_FAILED)
        assertEquals(0, e.store.launches)
    }

    @Test fun aLoginHandsThePhoneBackAndResumeCarriesOn() = runBlocking {
        val e = env("login")
        val t = e.newTask()
        e.approveAll(t)
        val halted = e.until(t.taskId) { it.status == TaskStatus.NEEDS_USER_ACTION }
        assertEquals(UserActionReason.LOGIN_REQUIRED, halted.needsUserAction!!.reason)
        assertEquals("only the launch was spent", 1, halted.actionsUsed)
        assertTrue("says what to do", halted.needsUserAction!!.message.startsWith("Test Store wants you to sign in"))
        assertEquals("the halt is one line in the timeline", 1, halted.events.count { it.description == halted.needsUserAction!!.message })
        assertNotNull("the stop control stays visible", e.tasks.activeTask.value)

        e.store.userSignsIn()
        assertTrue(e.tasks.resume(t.taskId) is ApiResult.Success)
        val done = e.until(t.taskId) { it.status.isTerminal || it.status == TaskStatus.CART_READY }
        assertEquals(done.timeline(), TaskStatus.CART_READY, done.status)
        assertTrue(done.cart!!.verified)
        assertTrue(done.events.any { it.description == "Resumed by you" })
        assertTrue("the action count carries on across the resume", done.actionsUsed > halted.actionsUsed)
        assertNull(done.needsUserAction)
    }

    @Test fun aDialogMidwayResumesFromTheStepThatHalted() = runBlocking {
        val e = env("dialog")
        val t = e.newTask()
        e.approveAll(t)
        val halted = e.until(t.taskId) { it.status == TaskStatus.NEEDS_USER_ACTION }
        assertEquals(UserActionReason.UNEXPECTED_DIALOG, halted.needsUserAction!!.reason)
        e.store.userDismissesDialog()
        e.tasks.resume(t.taskId)
        val done = e.until(t.taskId) { it.status.isTerminal || it.status == TaskStatus.CART_READY }
        assertEquals(done.timeline(), TaskStatus.CART_READY, done.status)
        assertEquals("every approved item, each once", listOf(2, 1, 1, 1), done.cart!!.lines.map { it.quantity })
        assertEquals("the app is opened once; the resume carries on inside it", 1, e.store.launches)
    }

    @Test fun theEmergencyStopIsImmediateAndFinal() = runBlocking {
        val e = env(latencyMs = 40)
        val t = e.newTask()
        e.approveAll(t)
        e.until(t.taskId) { it.actionsUsed >= 3 }
        val stopped = e.tasks.stop(t.taskId, StopReason.EMERGENCY_STOP)!!
        assertEquals(TaskStatus.CANCELLED, stopped.status)
        val clicksAtStop = e.store.clicks.size
        delay(500)
        val later = e.tasks.observe(t.taskId).first()!!
        assertEquals("nothing the runner reports afterwards is applied", TaskStatus.CANCELLED, later.status)
        assertEquals("Stopped by you (emergency stop)", later.events.last().description)
        assertTrue("no taps after the stop", e.store.clicks.size <= clicksAtStop)
        assertNull(e.tasks.activeTask.value)
    }

    @Test fun aPaymentScreenBlocksTheTask() = runBlocking {
        val e = env("payment_after_add")
        val t = e.newTask()
        e.approveAll(t)
        val done = e.until(t.taskId) { it.status.isTerminal }
        assertEquals(TaskStatus.BLOCKED_POLICY, done.status)
        assertEquals(FailureCode.POLICY_BLOCKED, done.failure!!.code)
        assertFalse(e.store.clicks.any { "pay" in it.lowercase() })
    }

    @Test fun aFailedCartCheckIsReportedWithTheCart() = runBlocking {
        val e = env("cart_drift")
        val t = e.newTask()
        e.approveAll(t)
        val done = e.until(t.taskId) { it.status.isTerminal }
        assertEquals(TaskStatus.FAILED_SAFE, done.status)
        assertEquals(FailureCode.CART_VERIFICATION_FAILED, done.failure!!.code)
        assertFalse(done.cart!!.verified)
    }

    /** No step of a Test Store plan is high-risk, so one is marked high-risk here to drive the checkpoint. */
    private fun withHighRiskFirstItem(app: String, items: List<ItemRequest>) = ExecutablePlan.forCart(app, items).let { p ->
        p.copy(actions = p.actions.map { if (it.action.type == ActionType.SELECT_ITEM && it.item == items.first()) it.copy(action = it.action.copy(risk = ActionRisk.HIGH)) else it })
    }

    @Test fun checkpointAnswersReachTheRunner() = runBlocking {
        val e = env(planFor = ::withHighRiskFirstItem)
        val t = e.newTask()
        e.approveAll(t)
        val cp = e.until(t.taskId) { it.status == TaskStatus.CHECKPOINT }.checkpoint!!
        val wrong = e.tasks.answerCheckpoint(t.taskId, "cp_other", true)
        assertTrue(wrong is ApiResult.Failure && wrong.error.code == ErrorCode.TASK_STATE_CONFLICT)
        e.tasks.answerCheckpoint(t.taskId, cp.checkpointId, true)
        assertEquals(TaskStatus.CART_READY, e.until(t.taskId) { it.status == TaskStatus.CART_READY || it.status.isTerminal }.status)

        val e2 = env(planFor = ::withHighRiskFirstItem)
        val t2 = e2.newTask()
        e2.approveAll(t2)
        val cp2 = e2.until(t2.taskId) { it.status == TaskStatus.CHECKPOINT }.checkpoint!!
        e2.tasks.answerCheckpoint(t2.taskId, cp2.checkpointId, false)
        val rejected = e2.until(t2.taskId) { it.status.isTerminal }
        assertEquals(TaskStatus.CANCELLED, rejected.status)
        assertEquals("nothing was added", 0, e2.store.clicks.count { it.startsWith("Add ") })
    }

    @Test fun eachTaskGoesToThePhoneOnlyWhenItsProviderIsBound() = runBlocking {
        val e = env()
        val simulated = FakeCartTaskRepository(fixtures, e.mock, e.comparisons, e.providers, e.account, scope)
        val router = RoutingCartTaskRepository(simulated, e.tasks, { it == ProviderKey.BLINKIT }, scope)
        val quote = e.quote(ProviderKey.BLINKIT, ProviderKey.BIGBASKET)
        e.providers.setAutomationConsent(ProviderKey.BIGBASKET, true) // as the consent screen does
        val onPhone = (router.create(quote, ProviderKey.BLINKIT) as ApiResult.Success).value
        val simulatedTask = (router.create(quote, ProviderKey.BIGBASKET) as ApiResult.Success).value
        assertTrue(e.tasks.owns(onPhone.taskId))
        assertFalse(e.tasks.owns(simulatedTask.taskId))
        assertEquals(simulatedTask, router.observe(simulatedTask.taskId).first())

        router.approve(onPhone.taskId, onPhone.plannedActions.map { it.actionId })
        assertEquals(TaskStatus.CART_READY, withTimeout(10_000) { router.observe(onPhone.taskId).first { it?.status == TaskStatus.CART_READY } }!!.status)
        assertEquals(TestStoreAdapter.PACKAGE, (router.createHandoff(onPhone.taskId) as ApiResult.Success).value.targetPackage)

        router.stopAll(StopReason.PAUSE_ALL)
        assertEquals(TaskStatus.CANCELLED, router.observe(simulatedTask.taskId).first()!!.status)
        assertEquals("a ready cart isn't cancelled", TaskStatus.CART_READY, router.observe(onPhone.taskId).first()!!.status)
    }
}
