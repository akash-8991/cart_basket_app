package com.smartcart.data.fake

import com.smartcart.data.ApiResult
import com.smartcart.data.ChatRepository
import com.smartcart.data.Fixtures
import com.smartcart.data.mock.ChatScenario
import com.smartcart.data.mock.ListScenario
import com.smartcart.data.mock.MockControl
import com.smartcart.model.AssistantTurn
import com.smartcart.model.Category
import com.smartcart.model.ChatSession
import com.smartcart.model.ChatSessionPage
import java.time.Instant
import java.util.UUID

/**
 * Stands in for the backend orchestrator + Claude gateway. Responses are contract fixtures;
 * selection is a simple deterministic rule so every chat state is reachable.
 */
class FakeChatRepository(fixtures: Fixtures, mock: MockControl) : FakeBase(fixtures, mock), ChatRepository {
    private val sessions = fixtures.load<ChatSessionPage>("chat/sessions.json").sessions.toMutableList()
    private val clarified = mutableSetOf<String>()

    override suspend fun createSession(category: Category): ApiResult<ChatSession> = call(0.3) {
        val s = ChatSession(
            sessionId = "chs_" + UUID.randomUUID().toString().take(8),
            category = category,
            title = "New ${category.title.lowercase()} chat",
            createdAt = Instant.now().toString(),
        )
        sessions.add(0, s)
        ApiResult.Success(s)
    }

    override suspend fun sessions(): ApiResult<List<ChatSession>> = call {
        when (cfg.history) {
            ListScenario.ERROR -> serviceError()
            ListScenario.EMPTY -> ApiResult.Success(emptyList())
            ListScenario.SUCCESS -> ApiResult.Success(sessions.toList())
        }
    }

    override suspend fun deleteSession(sessionId: String): ApiResult<Unit> = call(0.3) {
        sessions.removeAll { it.sessionId == sessionId }
        ApiResult.Success(Unit)
    }

    override suspend fun send(
        sessionId: String,
        category: Category,
        text: String,
        clarificationAnswer: Pair<String, String>?,
    ): ApiResult<AssistantTurn> = call(1.4) {
        when (cfg.chat) {
            ChatScenario.ERROR -> return@call fixtures.error("errors/ai-unavailable.json")
            ChatScenario.DEGRADED -> return@call ApiResult.Success(fixtures.load("chat/turn.degraded.json"))
            else -> Unit
        }
        val lower = text.lowercase()
        if (lower.startsWith("help") || lower.startsWith("what can")) {
            return@call ApiResult.Success(fixtures.load("chat/turn.general.json"))
        }
        val turn: AssistantTurn = when (category) {
            Category.GROCERY -> {
                val needsClarify = cfg.chat == ChatScenario.CLARIFY_FIRST && clarificationAnswer == null && sessionId !in clarified
                if (needsClarify) {
                    clarified += sessionId
                    fixtures.load("chat/turn.grocery-clarification.json")
                } else {
                    fixtures.load("chat/turn.grocery-cart.json")
                }
            }
            Category.FOOD -> fixtures.load("chat/turn.food-cart.json")
            Category.TRAVEL -> fixtures.load("chat/turn.travel-ride.json")
            Category.SHOPPING -> fixtures.load<AssistantTurn>("chat/turn.general.json").copy(
                replyText = "Shopping on Amazon and Flipkart is coming soon. For now I can help with travel, food and groceries.",
            )
        }
        ApiResult.Success(turn.copy(messageId = "msg_" + UUID.randomUUID().toString().take(8)))
    }
}
