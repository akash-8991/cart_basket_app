package com.smartcart.data

import com.smartcart.data.fake.FakeAccountRepository
import com.smartcart.data.fake.FakeCartTaskRepository
import com.smartcart.data.fake.FakeComparisonRepository
import com.smartcart.data.fake.FakeProviderRepository
import com.smartcart.data.mock.MockConfig
import com.smartcart.data.mock.MockControl
import com.smartcart.data.mock.TaskScenario
import com.smartcart.model.Category
import com.smartcart.model.ErrorCode
import com.smartcart.model.LineMatch
import com.smartcart.model.Objective
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteRequest
import com.smartcart.model.StopReason
import com.smartcart.model.TaskEventType
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FakeCartTaskRepositoryTest {
    private val fixtures = Fixtures(FileFixtureSource(File(System.getProperty("fixturesDir"))))

    private class Env(val mock: MockControl, val providers: FakeProviderRepository, val account: FakeAccountRepository, val comparisons: FakeComparisonRepository, val tasks: FakeCartTaskRepository)

    /**
     * The simulator runs in its own scope on the test scheduler (foreground work, so advanceUntilIdle
     * drives it and virtual time applies to timeouts), but outside runTest's children so a task left
     * waiting for the user does not hang the test.
     */
    private fun TestScope.env(
        scenario: TaskScenario = TaskScenario.CART_READY,
        scope: CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
    ): Env {
        val mock = MockControl(MockConfig(latencyMs = 0, task = scenario))
        val providers = FakeProviderRepository(fixtures, mock)
        val account = FakeAccountRepository(fixtures, mock)
        val comparisons = FakeComparisonRepository(fixtures, mock)
        return Env(mock, providers, account, comparisons, FakeCartTaskRepository(fixtures, mock, comparisons, providers, account, scope))
    }

    private suspend fun Env.groceryQuote(): String =
        (comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value

    private suspend fun Env.createTask(provider: ProviderKey = ProviderKey.BLINKIT) =
        (tasks.create(groceryQuote(), provider) as ApiResult.Success).value

    @Test
    fun happyPathRequiresApprovalAndEndsWithVerifiedCart() = runTest {
        val e = env()
        val t = e.createTask()
        assertEquals(TaskStatus.AWAITING_APPROVAL, t.status)
        assertTrue("no action before approval", t.events.isEmpty())
        e.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
        advanceUntilIdle()
        val done = e.tasks.observe(t.taskId).first()!!
        assertEquals(TaskStatus.CART_READY, done.status)
        assertTrue(done.cart!!.verified)
        assertEquals(4, done.cart!!.lines.size)
        assertNull("no active task once ready", e.tasks.activeTask.value)
        assertTrue(done.actionsUsed <= done.limits.maxActions)
    }

    @Test
    fun partialApprovalIsRejected() = runTest {
        val e = env()
        val t = e.createTask()
        val r = e.tasks.approve(t.taskId, t.plannedActions.take(2).map { it.actionId })
        assertTrue(r is ApiResult.Failure && r.error.code == ErrorCode.VALIDATION_FAILED)
        assertEquals(TaskStatus.AWAITING_APPROVAL, e.tasks.observe(t.taskId).first()!!.status)
    }

    @Test
    fun duplicateApprovalIsIdempotent() = runTest {
        val e = env()
        val t = e.createTask()
        val ids = t.plannedActions.map { it.actionId }
        val first = e.tasks.approve(t.taskId, ids)
        val second = e.tasks.approve(t.taskId, ids)
        assertTrue(first is ApiResult.Success && second is ApiResult.Success)
        advanceUntilIdle()
        val events = e.tasks.observe(t.taskId).first()!!.events
        assertEquals("only one run", 1, events.count { it.description == "Started" })
    }

    @Test
    fun emergencyStopCancelsAndNothingRunsAfterwards() = runTest {
        val e = env(TaskScenario.CHECKPOINT)
        val t = e.createTask()
        e.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
        runCurrent()
        assertEquals(TaskStatus.CHECKPOINT, e.tasks.activeTask.value?.status)
        val stopped = e.tasks.stop(t.taskId, StopReason.EMERGENCY_STOP)!!
        assertEquals(TaskStatus.CANCELLED, stopped.status)
        advanceUntilIdle()
        val after = e.tasks.observe(t.taskId).first()!!
        assertEquals(TaskStatus.CANCELLED, after.status)
        assertEquals("no events after the stop", stopped.events.size, after.events.size)
        assertNull(e.tasks.activeTask.value)
    }

    @Test
    fun checkpointApproveSkipsItemAndFlagsCart() = runTest {
        val e = env(TaskScenario.CHECKPOINT)
        val t = e.createTask()
        e.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
        runCurrent()
        val cp = e.tasks.activeTask.value!!.checkpoint!!
        e.tasks.answerCheckpoint(t.taskId, cp.checkpointId, approve = true)
        advanceUntilIdle()
        val done = e.tasks.observe(t.taskId).first()!!
        assertEquals(TaskStatus.CART_READY, done.status)
        assertTrue(done.cart!!.differsFromQuote)
        assertNull("old estimate is not shown when the cart changed", done.cart!!.totalEstimate)
        assertEquals(1, done.cart!!.lines.count { it.match == LineMatch.UNAVAILABLE })
    }

    @Test
    fun checkpointRejectStopsTheTask() = runTest {
        val e = env(TaskScenario.CHECKPOINT)
        val t = e.createTask()
        e.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
        runCurrent()
        val cp = e.tasks.activeTask.value!!.checkpoint!!
        e.tasks.answerCheckpoint(t.taskId, cp.checkpointId, approve = false)
        advanceUntilIdle()
        assertEquals(TaskStatus.CANCELLED, e.tasks.observe(t.taskId).first()!!.status)
    }

    @Test
    fun unansweredCheckpointTimesOutSafely() = runTest {
        val e = env(TaskScenario.CHECKPOINT)
        val t = e.createTask()
        e.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
        advanceUntilIdle() // virtual time passes the inactivity timeout
        val done = e.tasks.observe(t.taskId).first()!!
        assertEquals(TaskStatus.FAILED_SAFE, done.status)
        assertEquals(com.smartcart.model.FailureCode.INACTIVITY_TIMEOUT, done.failure!!.code)
    }

    @Test
    fun loginPromptHandsControlToUserThenResumes() = runTest {
        val e = env(TaskScenario.NEEDS_USER_ACTION)
        val t = e.createTask()
        e.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
        runCurrent()
        assertEquals(TaskStatus.NEEDS_USER_ACTION, e.tasks.activeTask.value?.status)
        e.tasks.resume(t.taskId)
        advanceUntilIdle()
        assertEquals(TaskStatus.CART_READY, e.tasks.observe(t.taskId).first()!!.status)
    }

    @Test
    fun paymentScreenBlocksAndUnknownScreenFailsSafe() = runTest {
        val blocked = env(TaskScenario.BLOCKED_POLICY)
        val t1 = blocked.createTask()
        blocked.tasks.approve(t1.taskId, t1.plannedActions.map { it.actionId })
        advanceUntilIdle()
        assertEquals(TaskStatus.BLOCKED_POLICY, blocked.tasks.observe(t1.taskId).first()!!.status)

        val failed = env(TaskScenario.FAILED_SAFE)
        val t2 = failed.createTask()
        failed.tasks.approve(t2.taskId, t2.plannedActions.map { it.actionId })
        advanceUntilIdle()
        val f = failed.tasks.observe(t2.taskId).first()!!
        assertEquals(TaskStatus.FAILED_SAFE, f.status)
        assertTrue(f.events.any { it.type == TaskEventType.HALT })
    }

    @Test
    fun consentDisabledProviderAndPausedAutomationAreRefused() = runTest {
        val e = env()
        val q = e.groceryQuote()
        // BigBasket has no automation consent in fixtures.
        val noConsent = e.tasks.create(q, ProviderKey.BIGBASKET)
        assertTrue(noConsent is ApiResult.Failure && noConsent.error.code == ErrorCode.CONSENT_REQUIRED)
        // Amazon is pending approval (provider gate).
        val gated = e.tasks.create(q, ProviderKey.AMAZON)
        assertTrue(gated is ApiResult.Failure && gated.error.code == ErrorCode.PROVIDER_DISABLED)
        e.account.setAutomationPaused(true)
        val paused = e.tasks.create(q, ProviderKey.BLINKIT)
        assertTrue(paused is ApiResult.Failure && paused.error.code == ErrorCode.POLICY_BLOCKED)
    }

    @Test
    fun handoffOnlyAfterVerifiedCart() = runTest {
        val e = env()
        val t = e.createTask()
        val early = e.tasks.createHandoff(t.taskId)
        assertTrue(early is ApiResult.Failure)
        e.tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
        advanceUntilIdle()
        val h = e.tasks.createHandoff(t.taskId)
        assertTrue(h is ApiResult.Success)
        e.tasks.markHandedOff(t.taskId)
        assertEquals(TaskStatus.HANDED_OFF, e.tasks.observe(t.taskId).first()!!.status)
        assertFalse((h as ApiResult.Success).value.handoffUrl.contains(t.taskId))
    }
}
