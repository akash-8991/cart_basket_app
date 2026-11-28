package com.smartcart.data

import com.smartcart.model.AssistantTurn
import com.smartcart.model.AuthSession
import com.smartcart.model.CartTask
import com.smartcart.model.ChatSession
import com.smartcart.model.ChatSessionDetail
import com.smartcart.model.ChatSessionPage
import com.smartcart.model.ComparisonQuote
import com.smartcart.model.ConsentList
import com.smartcart.model.DashboardSummary
import com.smartcart.model.DataRequestAccepted
import com.smartcart.model.ErrorEnvelope
import com.smartcart.model.Handoff
import com.smartcart.model.ImportSession
import com.smartcart.model.MonthlyMetrics
import com.smartcart.model.OrderPage
import com.smartcart.model.Profile
import com.smartcart.model.ProviderList
import com.smartcart.model.RecommendationList
import com.smartcart.model.RecoveryAccepted
import com.smartcart.model.ResetPasswordResponse
import com.smartcart.model.RevokeAllResponse
import com.smartcart.model.SessionList
import com.smartcart.model.TokenPair
import com.smartcart.model.ActivityPage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Decodes every contract fixture with the strict JSON config: if the OpenAPI contract and the
 * Kotlin models drift apart (renamed, added or removed fields; new enum values), this fails.
 */
class FixtureContractTest {
    private val root = File(System.getProperty("fixturesDir") ?: "../../../contracts/fixtures")

    @Serializable
    data class Manifest(val version: Int, val fixtures: List<Entry>)

    @Serializable
    data class Entry(val file: String, val schema: String, val status: Int)

    private val serializers: Map<String, KSerializer<*>> = mapOf(
        "AuthSession" to AuthSession.serializer(),
        "TokenPair" to TokenPair.serializer(),
        "ErrorEnvelope" to ErrorEnvelope.serializer(),
        "RecoveryAccepted" to RecoveryAccepted.serializer(),
        "ResetPasswordResponse" to ResetPasswordResponse.serializer(),
        "SessionList" to SessionList.serializer(),
        "RevokeAllResponse" to RevokeAllResponse.serializer(),
        "Profile" to Profile.serializer(),
        "ConsentList" to ConsentList.serializer(),
        "ActivityPage" to ActivityPage.serializer(),
        "DataRequestAccepted" to DataRequestAccepted.serializer(),
        "ProviderList" to ProviderList.serializer(),
        "ClientConfig" to com.smartcart.model.ClientConfig.serializer(),
        "ComparisonQuote" to ComparisonQuote.serializer(),
        "ChatSession" to ChatSession.serializer(),
        "ChatSessionPage" to ChatSessionPage.serializer(),
        "ChatSessionDetail" to ChatSessionDetail.serializer(),
        "AssistantTurn" to AssistantTurn.serializer(),
        "CartTask" to CartTask.serializer(),
        "Handoff" to Handoff.serializer(),
        "OrderPage" to OrderPage.serializer(),
        "ImportSession" to ImportSession.serializer(),
        "RecommendationList" to RecommendationList.serializer(),
        "DashboardSummary" to DashboardSummary.serializer(),
        "MonthlyMetrics" to MonthlyMetrics.serializer(),
        // CR-005 request bodies
        "PriceObservation" to com.smartcart.model.PriceObservation.serializer(),
        "TaskProgressReport" to com.smartcart.model.TaskProgressReport.serializer(),
    )

    @Test
    fun everyFixtureDecodesStrictly() {
        val manifest = ApiJson.strict.decodeFromString(Manifest.serializer(), File(root, "manifest.json").readText())
        assertTrue("manifest should list fixtures", manifest.fixtures.size > 50)
        val failures = mutableListOf<String>()
        for (e in manifest.fixtures) {
            val s = serializers[e.schema]
            if (s == null) {
                failures += "${e.file}: no Kotlin model registered for ${e.schema}"
                continue
            }
            runCatching { ApiJson.strict.decodeFromString(s, File(root, e.file).readText()) }
                .onFailure { failures += "${e.file}: ${it.message?.lineSequence()?.first()}" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun comparisonModelNeverShowsCheapestWithoutFullCoverage() {
        val fixtures = Fixtures(FileFixtureSource(root))
        val partial = fixtures.load<ComparisonQuote>("comparison/grocery.partial-coverage.json")
        // Even if the server mislabelled it, the client downgrades the badge.
        val forged = partial.copy(recommendation = partial.recommendation.copy(label = com.smartcart.model.RecommendationLabel.CHEAPEST))
        assertEquals(com.smartcart.model.RecommendationLabel.LOWEST_OBSERVED_ESTIMATE, forged.badgeLabel)
        val complete = fixtures.load<ComparisonQuote>("comparison/grocery.complete.json")
        assertEquals(com.smartcart.model.RecommendationLabel.CHEAPEST, complete.badgeLabel)
    }
}
