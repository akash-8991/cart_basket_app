package com.smartcart.automation

import com.smartcart.automation.teststore.TestStoreAdapter
import com.smartcart.model.ActionRisk
import com.smartcart.model.ActionType
import com.smartcart.model.PlannedAction
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4.3: reading prices, stock and fees on the phone, without ever changing the cart. */
class PriceCaptureTest {
    private val items = listOf(
        ItemRequest("Toned milk 1 L", 2), ItemRequest("Brown bread 400 g", 1), ItemRequest("Eggs 12 pack", 1), ItemRequest("Bananas 6 pcs", 1),
    )
    private val adapter = TestStoreAdapter(setOf(FakeVerifier.GOOD_SIGNER))
    private val timing = RunnerTiming(pollMillis = 100, stepWaitMillis = 5_000, foreignPackageGraceMillis = 1_000)

    private fun TestScope.store(scenario: String = "normal") = FakeStore(scenario, { testScheduler.currentTime })
    private fun TestScope.capture(s: FakeStore, a: Adapter = adapter) =
        PriceCapture(s, FakeVerifier(), a, now = { testScheduler.currentTime }, timing = timing)

    private fun FakeStore.changedTheCart() = clicks.any { it.startsWith("Add ") || it.startsWith("Increase ") || it.startsWith("Decrease ") }

    @Test fun readsEveryPriceAndTheStatedFees() = runTest {
        val s = store()
        val progress = mutableListOf<Pair<Int, Int>>()
        val r = capture(s).run(items) { read, total -> progress += read to total } as PriceCapture.Result.Read
        assertEquals(listOf(2800L, 4500L, 8400L, 4800L), r.offers.map { it.unitPriceMinor })
        assertTrue(r.offers.all { it.found && it.inStock })
        assertEquals(items.map { it.name }, r.offers.map { it.name })
        assertEquals(DELIVERY_FEE, r.fees!!.deliveryFeeMinor)
        assertEquals("the store says there are no other fees", listOf(0L, 0L, 0L, 0L), listOf(r.fees!!.packagingFeeMinor, r.fees!!.platformFeeMinor, r.fees!!.smallCartFeeMinor, r.fees!!.taxesMinor))
        assertEquals(4 to 4, progress.last())
        assertFalse("a price check never changes the cart", s.changedTheCart())
        assertFalse(s.clicks.any { "pay" in it.lowercase() })
    }

    @Test fun outOfStockAndMissingItemsAreReportedNotGuessed() = runTest {
        val r = capture(store("out_of_stock")).run(items + ItemRequest("Paneer 200 g", 1)) as PriceCapture.Result.Read
        val bread = r.offers.single { it.item.name == "Brown bread 400 g" }
        assertTrue(bread.found)
        assertFalse(bread.inStock)
        assertEquals(4500L, bread.unitPriceMinor)
        val paneer = r.offers.single { it.item.name == "Paneer 200 g" }
        assertFalse(paneer.found)
        assertEquals(null, paneer.unitPriceMinor)
    }

    @Test fun anythingOnlyThePersonCanDoEndsTheCheck() = runTest {
        for (scenario in listOf("login", "otp", "captcha")) {
            val s = store(scenario)
            val r = capture(s).run(items)
            assertTrue(scenario, r is PriceCapture.Result.NotRead)
            assertEquals("$scenario: nothing but the launch", 0, s.clicks.size)
        }
        val payment = capture(store("payment_after_add")).run(items)
        assertTrue("a price check never adds, so the payment screen never comes up", payment is PriceCapture.Result.Read)
    }

    @Test fun anUnknownScreenEndsTheCheck() = runTest {
        val r = capture(store("redesign")).run(items)
        assertTrue((r as PriceCapture.Result.NotRead).reason.contains("don't recognise"))
    }

    /** An adapter that, wrongly, tries to add the item while looking it up. */
    private class GreedyAdapter(private val inner: TestStoreAdapter) : Adapter by inner {
        override suspend fun perform(step: ExecutableAction, ctx: AdapterContext) {
            inner.perform(step, ctx)
            val item = step.item ?: return
            ctx.tap(ctx.awaitScreen("results"), Selector.Desc("Add ${item.name}"))
        }
    }

    @Test fun cartControlsAreRefusedEvenIfAnAdapterAsks() = runTest {
        val s = store()
        val r = capture(s, GreedyAdapter(adapter)).run(items)
        assertTrue(r is PriceCapture.Result.NotRead)
        assertEquals("A price check never changes your cart", (r as PriceCapture.Result.NotRead).reason)
        assertFalse(s.changedTheCart())
    }

    @Test fun aPriceCheckPlanCanOnlyOpenAndSearch() = runTest {
        val sneaky = ExecutablePlan.forPriceCheck("Test Store", items).let {
            it.copy(actions = it.actions + ExecutableAction(PlannedAction("p9", ActionType.SELECT_ITEM, "Add milk", ActionRisk.MEDIUM), items.first()))
        }
        val s = store()
        val r = TaskRunner(s, FakeVerifier(), adapter, sneaky, PriceCapture.DEFAULT_LIMITS, now = { testScheduler.currentTime }, timing = timing)
            .run(sneaky.actions.map { it.action.actionId }.toSet())
        assertEquals(TaskStatus.BLOCKED_POLICY, r.status)
        assertEquals(0, s.launches)
    }

    @Test fun cartTasksStillAddAsBefore() = runTest {
        // The same adapter in a cart plan: SEARCH steps without an item only check the screen.
        val plan = ExecutablePlan.forCart("Test Store", items.take(1))
        val s = store()
        val r = TaskRunner(s, FakeVerifier(), adapter, plan, PriceCapture.DEFAULT_LIMITS, now = { testScheduler.currentTime }, timing = timing)
            .run(plan.actions.map { it.action.actionId }.toSet())
        assertEquals(TaskStatus.CART_READY, r.status)
        assertTrue(r.offers.isEmpty())
        assertEquals("fees are read whenever the store opens", DELIVERY_FEE, r.fees?.deliveryFeeMinor)
    }
}
