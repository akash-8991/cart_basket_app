package com.smartcart.server.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.smartcart.server.flags.Flags
import com.smartcart.server.http.ApiJson
import com.smartcart.server.security.RateLimiter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Duration

sealed interface GatewayResult {
    data class Ok(val output: IntentOutput) : GatewayResult
    /** The deterministic path answers instead. [reason] is a fixed code, safe to log and count. */
    data class Fallback(val reason: String) : GatewayResult
}

/**
 * The only way chat reaches a model (D-019). Model output is untrusted: it must validate against the
 * frozen schema (which has no field that can approve, pay or grant anything), then pass semantic checks.
 * Any failure falls back to deterministic handling. The model never decides the next step.
 */
class ClaudeGateway(
    private val model: IntentModel,
    private val flags: Flags,
    private val limiter: RateLimiter,
    private val metrics: MeterRegistry,
    private val dailyCallsPerUser: Int = 200,
) {
    private val log = LoggerFactory.getLogger(ClaudeGateway::class.java)
    private val mapper = ObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
        ClaudeGateway::class.java.getResourceAsStream("/contracts/claude/intent-extraction.output.schema.json"),
    )

    fun extract(userId: String, aiConsent: Boolean, input: IntentInput): GatewayResult {
        val result = attempt(userId, aiConsent, input)
        metrics.counter("smartcart_ai_calls_total", "outcome", (result as? GatewayResult.Fallback)?.reason ?: "ok").increment()
        return result
    }

    private fun attempt(userId: String, aiConsent: Boolean, input: IntentInput): GatewayResult {
        if (!flags.isOn("chat.ai.enabled")) return GatewayResult.Fallback("disabled")
        if (!aiConsent) return GatewayResult.Fallback("no_consent")
        if (!limiter.hit("ai:day:$userId", dailyCallsPerUser, Duration.ofDays(1)).allowed) return GatewayResult.Fallback("budget")

        val raw = try {
            model.extract(input)
        } catch (e: Exception) {
            log.warn("intent model unavailable: {}", e.javaClass.simpleName) // never the message text
            return GatewayResult.Fallback("model_unavailable")
        }
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return GatewayResult.Fallback("not_json")
        if (schema.validate(node).isNotEmpty()) return GatewayResult.Fallback("schema_violation")
        val out = runCatching { ApiJson.decodeFromString(IntentOutput.serializer(), raw) }.getOrNull() ?: return GatewayResult.Fallback("schema_violation")

        // Semantic checks the schema can't express.
        if (out.replyText.isBlank()) return GatewayResult.Fallback("empty_reply")
        if (out.clarificationNeeded && out.clarificationQuestion.isNullOrBlank()) return GatewayResult.Fallback("incoherent")
        if (out.entities.any { it.name.isBlank() }) return GatewayResult.Fallback("incoherent")
        if (LINK.containsMatchIn(out.replyText)) return GatewayResult.Fallback("link_in_reply")
        if (UNSAFE_CLAIM.containsMatchIn(out.replyText)) return GatewayResult.Fallback("unsafe_claim")
        return GatewayResult.Ok(
            out.copy(
                // A provider the user doesn't have installed is dropped, never trusted.
                provider = out.provider?.takeIf { it in input.installedProviders },
                confidence = out.confidence.coerceIn(0.0, 1.0),
            ),
        )
    }

    private companion object {
        // Replies are shown in the app; a model-written link is a phishing vector, so none are allowed.
        // The model can't approve, order, book or pay, so a reply claiming it did (or asking for secrets) is
        // wrong or injected — either way it is not shown.
        val UNSAFE_CLAIM = Regex(
            "(?i)\\b(paid|payment|approved|charged|refund(ed)?|order(ed)?\\s+(is|has been|was)\\s+placed|placed\\s+(the|your)\\s+order|booked|" +
                "otp|one[- ]time password|password|pin|cvv|card number|upi)\\b",
        )
        val LINK = Regex("(?i)\\b(?:https?://|www\\.)|\\b[a-z0-9-]+\\.(?:com|in|net|org|io|app|link|xyz)\\b")
    }
}
