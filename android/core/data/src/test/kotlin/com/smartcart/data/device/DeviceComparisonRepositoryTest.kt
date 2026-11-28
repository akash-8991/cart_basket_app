package com.smartcart.data.device

import com.smartcart.automation.DELIVERY_FEE
import com.smartcart.automation.FakeStore
import com.smartcart.automation.FakeVerifier
import com.smartcart.automation.RunnerTiming
import com.smartcart.automation.teststore.TestStoreAdapter
import com.smartcart.data.ApiResult
import com.smartcart.data.FileFixtureSource
import com.smartcart.data.Fixtures
import com.smartcart.data.fake.FakeAccountRepository
import com.smartcart.data.fake.FakeComparisonRepository
import com.smartcart.data.fake.FakeProviderRepository
import com.smartcart.data.mock.MockConfig
import com.smartcart.data.mock.MockControl
import com.smartcart.model.ActionType
import com.smartcart.model.Availability
import com.smartcart.model.Category
import com.smartcart.model.ComparisonQuote
import com.smartcart.model.ComparisonStatus
import com.smartcart.model.LineMatch
import com.smartcart.model.Objective
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteRequest
import com.smartcart.model.RecommendationLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Phase 4.3: the bound provider's column comes from prices read on the phone, under the usual comparison rules. */
class DeviceComparisonRepositoryTest {
    private val fixtures = Fixtures(FileFixtureSource(File(System.getProperty("fixturesDir"))))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timing = RunnerTiming(pollMillis = 5, stepWaitMillis = 400, foreignPackageGraceMillis = 100)
    private val all = listOf(ProviderKey.BLINKIT, ProviderKey.BIGBASKET, ProviderKey.SWIGGY_INSTAMART)

    @After fun tearDown() = scope.cancel()

    private class Phone(val store: FakeStore, var connected: Boolean = true, val bound: ProviderKey? = ProviderKey.BLINKIT) : OnDeviceAutomation {
        var returns = 0
        override fun driver() = if (connected) store else null
        override val verifier = FakeVerifier()
        override fun adapterFor(provider: ProviderKey) = if (provider == bound) TestStoreAdapter(setOf(FakeVerifier.GOOD_SIGNER)) else null
        override fun returnToSmartCart() { returns++ }
    }

    private class Env(val phone: Phone, val mock: MockControl, val providers: FakeProviderRepository, val account: FakeAccountRepository, val comparisons: DeviceComparisonRepository)

    private fun env(scenario: String = "normal", phone: Phone = Phone(FakeStore(scenario, System::currentTimeMillis))): Env {
        val mock = MockControl(MockConfig(latencyMs = 0))
        val providers = FakeProviderRepository(fixtures, mock)
        val account = FakeAccountRepository(fixtures, mock)
        val base = FakeComparisonRepository(fixtures, mock)
        return Env(phone, mock, providers, account, DeviceComparisonRepository(base, phone, providers, account, scope, timing = timing))
    }

    /** Starts a grocery comparison and returns every quote the screen would see, ending with the final one. */
    private suspend fun Env.compare(): Pair<String, List<ComparisonQuote>> {
        val id = (comparisons.create(QuoteRequest(Category.GROCERY, eligibleProviders = all, objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value
        val seen = withTimeout(10_000) { comparisons.observe(id).toList() }.map { (it as ApiResult.Success).value }
        return id to seen
    }

    private fun ComparisonQuote.of(p: ProviderKey) = providerResults.single { it.provider == p }

    @Test fun pricesReadOnThePhoneReplaceTheBoundProvider() = runBlocking {
        val e = env()
        val (id, seen) = e.compare()
        val final = seen.last()
        assertTrue("in progress until the phone has read every price", seen.dropLast(1).all { it.status == ComparisonStatus.IN_PROGRESS })
        assertTrue("the column shows progress", seen.any { q -> q.of(ProviderKey.BLINKIT).notes.any { it.startsWith("Reading prices in Test Store:") } })

        val blinkit = final.of(ProviderKey.BLINKIT)
        assertEquals(Availability.AVAILABLE, blinkit.availability)
        assertEquals(listOf(2800L, 4500L, 8400L, 4800L), blinkit.lines.map { it.unitPrice!!.amountMinor })
        assertEquals(listOf("Toned milk 1 L", "Brown bread 400 g", "Eggs 12 pack", "Bananas 6 pcs"), blinkit.lines.map { it.matchedName })
        assertEquals(DELIVERY_FEE, blinkit.components.deliveryFee!!.amountMinor)
        assertEquals(0L, blinkit.components.platformFee!!.amountMinor)
        assertEquals(23300L + DELIVERY_FEE, blinkit.totalEstimate!!.amountMinor)
        assertTrue(blinkit.capturedAt != null && blinkit.notes.first() == "Prices read on this phone from Test Store")
        assertEquals(listOf("eta_minutes"), blinkit.unavailableFields)

        // ₹238 against the fixtures' ₹243 (Instamart) and ₹264 (BigBasket, the one the person picked).
        assertEquals(ComparisonStatus.COMPLETE, final.status)
        assertEquals(listOf(1, 3, 2), all.map { final.of(it).rank })
        assertEquals(ProviderKey.BLINKIT, final.recommendation.provider)
        assertEquals(RecommendationLabel.CHEAPEST, final.badgeLabel)
        assertEquals(26400L - 23800L, final.recommendation.savingsVsSelected!!.amountMinor)
        assertEquals("other providers are untouched", 24300L, final.of(ProviderKey.SWIGGY_INSTAMART).totalEstimate!!.amountMinor)

        assertEquals(final, e.comparisons.cached(id))
        assertEquals("SmartCart is brought back afterwards", 1, e.phone.returns)
        assertFalse("reading prices never changes the cart", e.phone.store.clicks.any { it.startsWith("Add ") || it.startsWith("Increase ") })
    }

    @Test fun anOutOfStockItemLeavesThatCartUnrankedAndCheapestMovesOn() = runBlocking {
        val final = env("out_of_stock").compare().second.last()
        val blinkit = final.of(ProviderKey.BLINKIT)
        assertEquals(Availability.PARTIAL, blinkit.availability)
        assertNull("no total for an incomplete cart", blinkit.totalEstimate)
        assertNull(blinkit.rank)
        val bread = blinkit.lines.single { it.requestedName.startsWith("Brown bread") }
        assertEquals(LineMatch.UNAVAILABLE, bread.match)
        assertEquals("Out of stock", bread.note)
        assertEquals(ProviderKey.SWIGGY_INSTAMART, final.recommendation.provider)
        assertEquals(RecommendationLabel.CHEAPEST, final.badgeLabel)
        assertEquals(26400L - 24300L, final.recommendation.savingsVsSelected!!.amountMinor)
    }

    @Test fun anAppThatCantBeReadMeansNoCheapestClaim() = runBlocking {
        val final = env("login").compare().second.last()
        val blinkit = final.of(ProviderKey.BLINKIT)
        assertEquals(Availability.FAILED, blinkit.availability)
        assertTrue(blinkit.notes.single().contains("sign in"))
        assertEquals(ComparisonStatus.PARTIAL_COVERAGE, final.status)
        assertEquals(listOf(ProviderKey.BLINKIT), final.coverage.failed)
        assertEquals("never \"Cheapest\" without full coverage", RecommendationLabel.LOWEST_OBSERVED_ESTIMATE, final.badgeLabel)
        assertEquals(ProviderKey.SWIGGY_INSTAMART, final.recommendation.provider)
    }

    @Test fun nothingIsReadWithoutTheServiceOrTheConsent() = runBlocking {
        val off = env(phone = Phone(FakeStore("normal", System::currentTimeMillis), connected = false))
        val a = off.compare().second.last().of(ProviderKey.BLINKIT)
        assertEquals(Availability.FAILED, a.availability)
        assertTrue(a.notes.single().startsWith("Turn on SmartCart"))
        assertEquals(0, off.phone.store.launches)

        val noConsent = env()
        noConsent.providers.setAutomationConsent(ProviderKey.BLINKIT, false)
        val b = noConsent.compare().second.last().of(ProviderKey.BLINKIT)
        assertEquals(Availability.FAILED, b.availability)
        assertTrue(b.notes.single().startsWith("Allow automation for Blinkit"))
        assertEquals(0, noConsent.phone.store.launches)
    }

    @Test fun withNothingBoundTheComparisonIsUnchanged() = runBlocking {
        val e = env(phone = Phone(FakeStore("normal", System::currentTimeMillis), bound = null))
        val final = e.compare().second.last()
        assertEquals(22900L, final.of(ProviderKey.BLINKIT).totalEstimate!!.amountMinor)
        assertEquals(0, e.phone.store.launches)
    }

    @Test fun aCartTaskAddsExactlyWhatWasFoundInStock() = runBlocking {
        val e = env("out_of_stock")
        val (id, _) = e.compare()
        val tasks = DeviceCartTaskRepository(e.phone, e.comparisons, e.providers, e.account, scope, timing = timing)
        val t = (tasks.create(id, ProviderKey.BLINKIT) as ApiResult.Success).value
        assertEquals(
            "the out-of-stock bread isn't planned",
            listOf("Add Toned milk 1 L × 2", "Add Eggs 12 pack × 1", "Add Bananas 6 pcs × 1"),
            t.plannedActions.filter { it.type == ActionType.SELECT_ITEM }.map { it.description },
        )
    }

    /** Stands in for the server (4.4): records what the phone sends and settles the quote once the reading arrives. */
    private class FakeServer(private val inner: FakeComparisonRepository) : com.smartcart.data.ComparisonRepository by inner, DeviceReadingSink {
        val requests = mutableListOf<QuoteRequest>()
        val readings = mutableListOf<com.smartcart.model.PriceObservation>()
        private val settled = kotlinx.coroutines.flow.MutableStateFlow(false)
        override suspend fun create(request: QuoteRequest) = inner.create(request).also { requests += request }
        override suspend fun submitObservation(quoteId: String, observation: com.smartcart.model.PriceObservation): ApiResult<ComparisonQuote> {
            readings += observation
            settled.value = true
            return ApiResult.Success(inner.cached(quoteId)!!)
        }
        override fun observe(quoteId: String) = kotlinx.coroutines.flow.flow {
            val q = inner.cached(quoteId)!!
            emit(ApiResult.Success(q.copy(status = ComparisonStatus.IN_PROGRESS)))
            settled.first { it }
            emit(ApiResult.Success(q.copy(providerResults = q.providerResults.map { if (it.provider == ProviderKey.BLINKIT) it.copy(source = com.smartcart.model.ResultSource.DEVICE) else it })))
        }
    }

    @Test fun withABackendThePhoneSendsItsReadingInsteadOfWorkingOutTheColumn() = runBlocking {
        val e = env()
        val server = FakeServer(FakeComparisonRepository(fixtures, e.mock))
        val repo = DeviceComparisonRepository(server, e.phone, e.providers, e.account, scope, timing = timing)
        val cart = listOf(com.smartcart.model.CartLine("c1", "Toned milk", 2, "1 L"), com.smartcart.model.CartLine("c2", "Paneer", 1, "200 g"))
        val id = (repo.create(QuoteRequest(Category.GROCERY, cart, eligibleProviders = all, objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value
        val seen = withTimeout(10_000) { repo.observe(id).toList() }.map { (it as ApiResult.Success).value }

        assertEquals("the server is told which provider the phone reads", listOf(ProviderKey.BLINKIT), server.requests.single().deviceProviders)
        val reading = server.readings.single()
        assertEquals(com.smartcart.model.ObservationOutcome.READ, reading.outcome)
        assertEquals("keyed by the cart's own lines", listOf("c1", "c2"), reading.offers.map { it.lineId })
        assertEquals(2800L, reading.offers[0].unitPrice!!.amountMinor)
        assertFalse("paneer isn't in the store", reading.offers[1].found)
        assertTrue("progress shows while the phone reads", seen.any { q -> q.of(ProviderKey.BLINKIT).notes.any { it.startsWith("Reading prices in Test Store:") } })
        assertEquals("the server's quote is the final word", com.smartcart.model.ResultSource.DEVICE, seen.last().of(ProviderKey.BLINKIT).source)
        assertEquals(1, e.phone.returns)
    }

    @Test fun withABackendAnUnreadableProviderIsReportedWithItsReason() = runBlocking {
        val e = env(phone = Phone(FakeStore("normal", System::currentTimeMillis), connected = false))
        val server = FakeServer(FakeComparisonRepository(fixtures, e.mock))
        val repo = DeviceComparisonRepository(server, e.phone, e.providers, e.account, scope, timing = timing)
        val id = (repo.create(QuoteRequest(Category.GROCERY, eligibleProviders = all, objective = Objective.LOWEST_TOTAL)) as ApiResult.Success).value
        withTimeout(10_000) { repo.observe(id).toList() }
        assertEquals(com.smartcart.model.NotReadReason.SERVICE_OFF, server.readings.single().notReadReason)
        assertEquals(0, e.phone.store.launches)
    }
}
