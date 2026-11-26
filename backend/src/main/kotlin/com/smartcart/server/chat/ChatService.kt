package com.smartcart.server.chat

import com.smartcart.server.audit.RequestContext
import com.smartcart.server.flags.Flags
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ApiJson
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.me.ConsentKeys
import com.smartcart.server.me.MeService
import com.smartcart.server.me.Page
import com.smartcart.server.security.RateLimiter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

object ChatLimits {
    val SESSIONS_PER_USER = 30 to Duration.ofHours(1)
    val MESSAGES_PER_USER = 30 to Duration.ofMinutes(1)
    /** data-classification.md: chat content is kept 30 days. */
    val RETENTION: Duration = Duration.ofDays(30)
    val CATEGORIES = setOf("travel", "food", "grocery", "shopping")
}

class ChatService(
    private val repo: ChatRepository,
    private val gateway: ClaudeGateway,
    private val me: MeService,
    private val flags: Flags,
    private val limiter: RateLimiter,
    private val clock: Clock,
) {
    private val orchestrator = Orchestrator(flags, repo::newId)

    fun createSession(userId: String, category: String): SessionRow {
        if (category !in ChatLimits.CATEGORIES) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "category", "invalid_value")
        // The category card is behind a server flag (e.g. Shopping, D-004): the server enforces it too.
        if (!flags.isOn("home.category.$category")) throw ApiException(ErrorCode.FORBIDDEN)
        limit("chat:sessions:$userId", ChatLimits.SESSIONS_PER_USER)
        return repo.createSession(userId, category, defaultTitle(category), clock.instant())
    }

    fun session(userId: String, sessionId: String): SessionRow = repo.session(userId, sessionId) ?: throw ApiException(ErrorCode.NOT_FOUND)

    fun detail(userId: String, sessionId: String): Pair<SessionRow, List<MessageRow>> {
        val s = session(userId, sessionId)
        return s to repo.messages(s.sessionId)
    }

    fun sessions(userId: String, limit: Int, cursor: String?): Page<SessionRow> {
        val after = cursor?.let { decodeCursor(it) ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "cursor", "invalid_cursor") }
        val rows = repo.sessions(userId, limit + 1, after)
        val page = rows.take(limit)
        return Page(page, if (rows.size > limit) encodeCursor(page.last()) else null)
    }

    fun delete(userId: String, sessionId: String) {
        if (!repo.deleteSession(userId, sessionId)) throw ApiException(ErrorCode.NOT_FOUND)
    }

    fun deleteAll(userId: String) = repo.deleteAll(userId)

    fun purgeExpired(): Int = repo.purgeInactiveSince(clock.instant().minus(ChatLimits.RETENTION))

    /** The stored turn, re-encoded canonically (Postgres jsonb reorders keys and adds spaces). */
    fun storedTurn(userId: String, messageId: String): String {
        val raw = repo.turn(userId, messageId) ?: throw ApiException(ErrorCode.CONFLICT)
        return ApiJson.encodeToString(AssistantTurn.serializer(), ApiJson.decodeFromString(AssistantTurn.serializer(), raw))
    }

    /** One user message in, one assistant turn out. Returns the assistant message ID. */
    fun send(userId: String, req: ChatMessageRequest, ctx: RequestContext): String {
        limit("chat:messages:$userId", ChatLimits.MESSAGES_PER_USER)
        val session = session(userId, req.sessionId)
        val now = clock.instant()
        val text = Redactor.redact(req.text.trim())
        val firstMessage = session.updatedAt == null

        val pending = runCatching { ApiJson.decodeFromString(State.serializer(), session.state).pending }.getOrNull()
        val decision = when {
            req.clarificationAnswer != null -> {
                if (pending == null || pending.clarificationId != req.clarificationAnswer.clarificationId) {
                    throw ApiException.field(ErrorCode.VALIDATION_FAILED, "clarification_answer", "stale_clarification")
                }
                orchestrator.answer(session.category, pending, req.clarificationAnswer.optionId)
                    ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "clarification_answer.option_id", "invalid_value")
            }
            else -> {
                val aiConsent = ConsentKeys.AI_PROCESSING in me.grantedKeys(userId)
                when (val r = gateway.extract(userId, aiConsent, IntentInput(text, session.category, req.context.installedProviders))) {
                    is GatewayResult.Ok -> orchestrator.fromModel(session.category, r.output)
                    is GatewayResult.Fallback -> orchestrator.fallback(session.category, text)
                }
            }
        }

        val messageId = repo.newId("msg")
        val turn = AssistantTurn(
            messageId = messageId,
            replyText = decision.reply,
            intent = decision.intent,
            nextStep = decision.nextStep,
            clarification = decision.clarification,
            cartDraft = decision.draft,
            degraded = decision.degraded,
        )
        val turnJson = ApiJson.encodeToString(AssistantTurn.serializer(), turn)
        // Stored only once the turn succeeded, so a rejected request leaves no orphan message.
        repo.addMessage(session.sessionId, "user", text, now)
        repo.addMessage(session.sessionId, "assistant", decision.reply, now, turnJson, messageId)
        repo.afterTurn(
            session.sessionId,
            ApiJson.encodeToString(State.serializer(), State(decision.pending)),
            if (firstMessage) text.lineSequence().first().take(40).ifBlank { null } else null,
            now,
        )
        return messageId
    }

    @kotlinx.serialization.Serializable
    data class State(val pending: PendingClarification? = null)

    private fun defaultTitle(category: String) = when (category) {
        "travel" -> "New ride"
        "food" -> "New food order"
        "grocery" -> "New grocery list"
        else -> "New shopping list"
    }

    private fun limit(key: String, rule: Pair<Int, Duration>) {
        val d = limiter.hit(key, rule.first, rule.second)
        if (!d.allowed) throw ApiException.limited(ErrorCode.RATE_LIMITED, d)
    }

    private fun encodeCursor(r: SessionRow): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${r.createdAt.epochSecond}|${r.createdAt.nano}|${r.sessionId}".toByteArray())

    private fun decodeCursor(c: String): Pair<Instant, String>? = runCatching {
        val (s, n, id) = String(Base64.getUrlDecoder().decode(c)).split('|', limit = 3)
        Instant.ofEpochSecond(s.toLong(), n.toLong()) to id.also { require(it.startsWith("chs_")) }
    }.getOrNull()
}
