package com.smartcart.data.device

import com.smartcart.automation.FakeStore
import com.smartcart.automation.FakeVerifier
import com.smartcart.automation.PriceCapture
import com.smartcart.automation.RunnerTiming
import com.smartcart.automation.teststore.TestStoreAdapter
import com.smartcart.data.ApiResult
import com.smartcart.data.FileFixtureSource
import com.smartcart.data.Fixtures
import com.smartcart.data.fake.FakeAccountRepository
import com.smartcart.data.fake.FakeComparisonRepository
import com.smartcart.data.fake.FakeProviderRepository
import com.smartcart.data.ApiJson
import com.smartcart.data.mock.MockConfig
import com.smartcart.data.mock.MockControl
import com.smartcart.model.CartTask
import com.smartcart.model.Category
import com.smartcart.model.LineMatch
import com.smartcart.model.MatchedLine
import com.smartcart.model.NotReadReason
import com.smartcart.model.ObservationOutcome
import com.smartcart.model.Objective
import com.smartcart.model.PriceObservation
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteRequest
import com.smartcart.model.TaskProgressReport
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** CR-005: the bodies the phone sends are raw readings, in the contract's shape, and continue the server's timeline. */
class DeviceReportsTest {
    private val adapter = TestStoreAdapter(setOf(FakeVerifier.GOOD_SIGNER))
    private val timing = RunnerTiming(pollMillis = 5, stepWaitMillis = 400, foreignPackageGraceMillis = 100)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private val lines = listOf(
        MatchedLine("l1", "Toned milk 1 L", "Toned milk", "1 L", 2, match = LineMatch.EXACT),
        MatchedLine("l2", "Brown bread 400 g", "Brown bread", "400 g", 1, match = LineMatch.EXACT),
        MatchedLine("l3", "Paneer 200 g", "Paneer", "200 g", 1, match = LineMatch.EXACT),
    )

    private fun capture(scenario: String) = runBlocking {
        PriceCapture(FakeStore(scenario, System::currentTimeMillis), FakeVerifier(), adapter, timing = timing).run(lines.map(CapturedQuote::item))
    }

    @Test fun anObservationCarriesOnlyWhatWasReadKeyedByLine() {
        val o = DeviceReports.observation(ProviderKey.BLINKIT, lines, capture("out_of_stock"), adapter, "2026-11-28T10:00:00Z", "INR")
        assertEquals(ObservationOutcome.READ, o.outcome)
        assertEquals(listOf("l1", "l2", "l3"), o.offers.map { it.lineId })
        assertEquals(2800L, o.offers[0].unitPrice!!.amountMinor)
        assertTrue(o.offers[0].inStock)
        assertTrue("found but out of stock", o.offers[1].found && !o.offers[1].inStock)
        assertTrue("not in the store", !o.offers[2].found && o.offers[2].unitPrice == null)
        assertEquals(500L, o.fees!!.deliveryFee!!.amountMinor)
        assertEquals("teststore", o.adapter.key)

        // In the contract's wire shape, and nothing computed: no totals, ranks or labels.
        val wire = ApiJson.strict.encodeToString(PriceObservation.serializer(), o)
        listOf("\"line_id\"", "\"in_stock\"", "\"captured_at\"", "\"delivery_fee\"").forEach { assertTrue(it, it in wire) }
        listOf("total", "rank", "label", "subtotal").forEach { assertTrue("no $it", it !in wire) }
        assertEquals(o, ApiJson.strict.decodeFromString(PriceObservation.serializer(), wire))
    }

    @Test fun anUnreadAppSaysWhy() {
        assertEquals(NotReadReason.LOGIN_REQUIRED, DeviceReports.observation(ProviderKey.BLINKIT, lines, capture("login"), adapter, "t", "INR").notReadReason)
        assertEquals(NotReadReason.UNKNOWN_SCREEN, DeviceReports.observation(ProviderKey.BLINKIT, lines, capture("redesign"), adapter, "t", "INR").notReadReason)
        val wrongSigner = runBlocking { PriceCapture(FakeStore("normal", System::currentTimeMillis), FakeVerifier(signers = setOf("other")), adapter, timing = timing).run(lines.map(CapturedQuote::item)) }
        assertEquals(NotReadReason.PACKAGE_MISMATCH, (wrongSigner as PriceCapture.Result.NotRead).code)
        val off = DeviceReports.notRead(ProviderKey.BLINKIT, NotReadReason.SERVICE_OFF, adapter, "t")
        assertEquals(ObservationOutcome.NOT_READ, off.outcome)
        assertTrue(off.offers.isEmpty() && off.fees == null)
    }

    @Test fun progressReportsContinueTheTimelineAndOnlyUseDeviceStates() = runBlocking {
        val fixtures = Fixtures(FileFixtureSource(File(System.getProperty("fixturesDir"))))
        val mock = MockControl(MockConfig(latencyMs = 0))
        val comparisons = FakeComparisonRepository(fixtures, mock)
        val store = FakeStore("login", System::currentTimeMillis)
        val phone = object : OnDeviceAutomation {
            override fun driver() = store
            override val verifier = FakeVerifier()
            override fun adapterFor(provider: ProviderKey) = adapter
        }
        val tasks = DeviceCartTaskRepository(phone, comparisons, FakeProviderRepository(fixtures, mock), FakeAccountRepository(fixtures, mock), scope, timing = timing)
        val quote = (comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value
        val t = (tasks.create(quote, ProviderKey.BLINKIT) as ApiResult.Success).value
        assertNull("nothing to report before approval", DeviceReports.progress(t, 1, 0, adapter))
        tasks.approve(t.taskId, t.plannedActions.map { it.actionId })
        suspend fun until(s: TaskStatus): CartTask = withTimeout(10_000) { tasks.observe(t.taskId).first { it?.status == s }!! }

        // The server's approval answer already holds "Approved by you" (seq 1); the phone's reports continue from there.
        val first = DeviceReports.progress(until(TaskStatus.NEEDS_USER_ACTION), 1, 1, adapter)!!
        assertEquals(TaskStatus.NEEDS_USER_ACTION, first.status)
        assertEquals((2..first.events.last().seq).toList(), first.events.map { it.seq })
        assertTrue(first.needsUserAction!!.message.length <= 200)

        store.userSignsIn()
        tasks.resume(t.taskId)
        val second = DeviceReports.progress(until(TaskStatus.CART_READY), 2, first.events.last().seq, adapter)!!
        assertEquals("each event is sent once, in order", first.events.last().seq + 1, second.events.first().seq)
        assertTrue(second.cart!!.verified)
        assertTrue(TaskProgressReport.DEVICE_STATUSES.containsAll(listOf(first.status, second.status)))
        assertEquals(second, ApiJson.strict.decodeFromString(TaskProgressReport.serializer(), ApiJson.strict.encodeToString(TaskProgressReport.serializer(), second)))
    }
}
