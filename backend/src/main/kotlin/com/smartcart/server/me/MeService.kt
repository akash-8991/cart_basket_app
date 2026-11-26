package com.smartcart.server.me

import com.smartcart.server.audit.Audit
import com.smartcart.server.audit.RequestContext
import com.smartcart.server.auth.AuthService
import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.mail.MailTemplate
import com.smartcart.server.mail.Mailer
import com.smartcart.server.security.RateLimiter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Consent keys the server accepts (app: settings, task consent, history import; fixtures: globals). */
object ConsentKeys {
    const val AI_PROCESSING = "ai_processing"
    const val NOTIFICATIONS = "notifications"
    val GLOBAL = listOf(AI_PROCESSING, NOTIFICATIONS)
    /** Acceptance of the terms and privacy notice, recorded like consents so re-acceptance has a home (CR-003 §3). */
    const val TERMS = "terms"
    const val PRIVACY = "privacy"
    val POLICIES = listOf(TERMS, PRIVACY)
    val PROVIDERS = setOf("uber", "ola", "rapido", "zomato", "swiggy", "blinkit", "bigbasket", "swiggy_instamart", "amazon", "flipkart", "zepto")
    /** Version of the consent texts shipped with the frozen frontend. */
    const val CURRENT_VERSION = "2026-11"

    fun isValid(key: String): Boolean =
        key in GLOBAL || key in POLICIES || key.substringAfter('.', "") in PROVIDERS && key.substringBefore('.') in setOf("automation", "history")

    fun provider(key: String): String? = key.substringAfter('.', "").takeIf { it in PROVIDERS }
}

data class Page<T>(val items: List<T>, val nextCursor: String?)

object Limits3 {
    val EXPORTS_PER_USER = 3 to Duration.ofDays(1)
    const val EXPORT_SLA_DAYS = 7
    const val DELETE_SLA_DAYS = 30
}

class MeService(
    private val repo: MeRepository,
    private val auth: AuthService,
    private val activity: ActivityLog,
    private val limiter: RateLimiter,
    private val audit: Audit,
    private val clock: Clock,
) {
    fun profile(userId: String): ProfileRow = repo.profile(userId) ?: throw ApiException(ErrorCode.UNAUTHENTICATED)

    /** Explicit decisions, plus the two global consents (not granted until the user says yes). */
    fun consents(userId: String): List<ConsentRow> {
        val set = repo.consents(userId).associateBy { it.key }
        val p = profile(userId)
        val globals = ConsentKeys.GLOBAL.map { set[it] ?: ConsentRow(it, false, ConsentKeys.CURRENT_VERSION, p.createdAt) }
        // Terms and privacy: the latest acceptance, or the versions accepted at registration.
        val policies = listOf(
            set[ConsentKeys.TERMS] ?: ConsentRow(ConsentKeys.TERMS, true, p.termsVersion, p.createdAt),
            set[ConsentKeys.PRIVACY] ?: ConsentRow(ConsentKeys.PRIVACY, true, p.privacyVersion, p.createdAt),
        )
        return globals + policies + set.values.filter { it.key !in ConsentKeys.GLOBAL && it.key !in ConsentKeys.POLICIES }.sortedBy { it.key }
    }

    /**
     * Consents that count right now: granted against the current consent texts. A grant given for older texts
     * stops counting when they change, so the app's just-in-time screens ask again (CR-003 §3).
     */
    fun grantedKeys(userId: String): Set<String> =
        repo.consents(userId).filter { it.granted && it.version == ConsentKeys.CURRENT_VERSION }.map { it.key }.toSet()

    fun setConsent(userId: String, key: String, granted: Boolean, version: String, ctx: RequestContext): ConsentRow {
        if (!ConsentKeys.isValid(key)) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "consent_key", "unknown_consent")
        if (version.isBlank() || version.length > 32) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "version", "invalid_format")
        if (key in ConsentKeys.POLICIES) {
            // Terms and privacy can only be accepted, and only in their current version.
            val current = if (key == ConsentKeys.TERMS) com.smartcart.server.config.PolicyVersions.TERMS else com.smartcart.server.config.PolicyVersions.PRIVACY
            if (!granted) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "granted", "cannot_withdraw_policy")
            if (version != current) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "version", "not_current")
        }
        val now = clock.instant()
        val previous = repo.setConsent(userId, key, granted, version, now, ctx.correlationId)
        audit.record("consent.set", if (granted) "granted" else "revoked", ctx, actor = userId, target = key, detail = mapOf("version" to version))
        if (previous != granted) {
            activity.record(
                userId,
                if (granted) ActivityType.CONSENT_GRANTED else ActivityType.CONSENT_REVOKED,
                consentSummary(key, granted),
                now,
                provider = ConsentKeys.provider(key),
                key = (if (granted) "consent_granted." else "consent_revoked.") + key.substringBefore('.'),
                args = ConsentKeys.provider(key)?.let { mapOf("provider_name" to providerName(it)) },
            )
        }
        return ConsentRow(key, granted, version, now)
    }

    /** CR-002 D. Changing it is audited; the export already includes it. */
    fun setPrivacyMode(userId: String, mode: String, ctx: RequestContext): ProfileRow {
        if (mode !in setOf("device_only", "normalized_upload")) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "privacy_mode", "invalid_value")
        repo.setPrivacyMode(userId, mode)
        audit.record("privacy.mode_set", mode, ctx, actor = userId)
        return profile(userId)
    }

    fun activity(userId: String, limit: Int, cursor: String?): Page<ActivityRow> {
        val after = cursor?.let { decodeCursor(it) ?: throw ApiException.field(ErrorCode.VALIDATION_FAILED, "cursor", "invalid_cursor") }
        val rows = repo.activity(userId, limit + 1, after)
        val page = rows.take(limit)
        return Page(page, if (rows.size > limit) encodeCursor(page.last()) else null)
    }

    fun requestExport(userId: String, password: String, ctx: RequestContext): DataRequestRow {
        auth.stepUp(userId, password, ctx)
        val d = limiter.hit("export:$userId", Limits3.EXPORTS_PER_USER.first, Limits3.EXPORTS_PER_USER.second)
        if (!d.allowed) throw ApiException.limited(ErrorCode.RATE_LIMITED, d)
        return repo.createDataRequest(userId, "export", "all", clock.instant()).also {
            audit.record("data.export_requested", "queued", ctx, actor = userId, target = it.requestId)
        }
    }

    fun requestDeletion(userId: String, scope: String, password: String, ctx: RequestContext): DataRequestRow {
        if (scope !in setOf("history", "chat", "account")) throw ApiException.field(ErrorCode.VALIDATION_FAILED, "scope", "invalid_value")
        auth.stepUp(userId, password, ctx)
        val now = clock.instant()
        val req = repo.createDataRequest(userId, "delete", scope, now)
        if (scope == "account") {
            // Effective at once: the account can't be used again while erasure is pending.
            repo.markDeletionRequested(userId, now)
            auth.revokeAllForDeletion(userId, ctx)
        }
        audit.record("data.delete_requested", "queued", ctx, actor = userId, target = req.requestId, detail = mapOf("scope" to scope))
        return req
    }

    private fun consentSummary(key: String, granted: Boolean): String {
        val verb = if (granted) "allowed" else "turned off"
        val provider = ConsentKeys.provider(key)?.let(::providerName)
        return when {
            key == ConsentKeys.AI_PROCESSING -> "You $verb AI help with your requests."
            key == ConsentKeys.NOTIFICATIONS -> "You $verb notifications."
            key == ConsentKeys.TERMS -> "You accepted the updated terms."
            key == ConsentKeys.PRIVACY -> "You accepted the updated privacy notice."
            key.startsWith("automation.") -> "You $verb SmartCart to prepare carts in $provider."
            else -> "You $verb importing order history from $provider."
        }
    }

    private fun encodeCursor(r: ActivityRow): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${r.at.toEpochMilli()}|${r.at.nano}|${r.eventId}".toByteArray())

    private fun decodeCursor(c: String): Pair<Instant, String>? = runCatching {
        val (ms, nanos, id) = String(Base64.getUrlDecoder().decode(c)).split('|', limit = 3)
        Instant.ofEpochSecond(Math.floorDiv(ms.toLong(), 1000L), nanos.toLong()) to id.also { require(it.startsWith("evt_")) }
    }.getOrNull()
}

fun providerName(key: String) = when (key) {
    "swiggy_instamart" -> "Swiggy Instamart"
    "bigbasket" -> "BigBasket"
    else -> key.replaceFirstChar { it.uppercase() }
}

/**
 * Carries out export and deletion requests outside the request path. Features that store user data
 * register an eraser for their scope (chat registers "chat" in slice 3.5; history arrives in Phase 4).
 */
class DataRequestProcessor(
    private val repo: MeRepository,
    private val mailer: Mailer,
    private val activity: ActivityLog,
    private val audit: Audit,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(DataRequestProcessor::class.java)
    private val erasers = mutableMapOf<String, MutableList<(String) -> Unit>>()

    fun onErase(scope: String, eraser: (userId: String) -> Unit) { erasers.getOrPut(scope) { mutableListOf() } += eraser }

    /** Processes queued requests; returns how many were handled. Safe to run from several instances. */
    fun processPending(max: Int = 20): Int {
        val batch = repo.claimPending(max)
        batch.forEach { r ->
            val ctx = RequestContext("job:${r.requestId}", null)
            runCatching {
                when {
                    r.kind == "export" -> export(r)
                    r.scope == "account" -> eraseAccount(r)
                    else -> {
                        erasers[r.scope].orEmpty().forEach { it(r.userId) }
                        repo.finishRequest(r.requestId, "completed", clock.instant())
                        activity.record(r.userId, ActivityType.DATA_DELETED, if (r.scope == "chat") "Your chat history was deleted." else "Your order history was deleted.", clock.instant(), key = "data_deleted.${r.scope}")
                    }
                }
                audit.record("data.${r.kind}_completed", "success", ctx, actor = r.userId, target = r.requestId, detail = mapOf("scope" to r.scope))
            }.onFailure {
                log.error("data request {} failed", r.requestId, it)
                repo.finishRequest(r.requestId, "failed", clock.instant())
                audit.record("data.${r.kind}_completed", "failed", ctx, actor = r.userId, target = r.requestId)
            }
        }
        return batch.size
    }

    private fun export(r: DataRequestRow) {
        val p = repo.profile(r.userId) ?: run { repo.finishRequest(r.requestId, "failed", clock.instant()); return }
        val doc = buildJsonObject {
            put("exported_at", clock.instant().toString())
            put("profile", buildJsonObject {
                put("user_id", p.userId); put("email", p.email); put("email_verified", p.emailVerified)
                put("privacy_mode", p.privacyMode); put("created_at", p.createdAt.toString())
            })
            put("consents", JsonArray(repo.consents(r.userId).map { JsonObject(mapOf("key" to JsonPrimitive(it.key), "granted" to JsonPrimitive(it.granted), "version" to JsonPrimitive(it.version), "updated_at" to JsonPrimitive(it.updatedAt.toString()))) }))
            put("consent_history", JsonArray(repo.consentHistory(r.userId).map { JsonObject(mapOf("key" to JsonPrimitive(it.key), "granted" to JsonPrimitive(it.granted), "version" to JsonPrimitive(it.version), "at" to JsonPrimitive(it.at.toString()))) }))
            put("activity", JsonArray(repo.activity(r.userId, 10_000, null).map { JsonObject(mapOf("at" to JsonPrimitive(it.at.toString()), "type" to JsonPrimitive(it.type), "summary" to JsonPrimitive(it.summary))) }))
        }
        mailer.send(p.email, MailTemplate.DATA_EXPORT, mapOf("request_id" to r.requestId, "export_json" to doc.toString()))
        repo.finishRequest(r.requestId, "completed", clock.instant())
        activity.record(r.userId, ActivityType.DATA_EXPORTED, "A copy of your data was emailed to you.", clock.instant())
    }

    private fun eraseAccount(r: DataRequestRow) {
        val email = repo.profile(r.userId)?.email
        erasers.values.flatten().forEach { it(r.userId) }
        repo.eraseAccount(r.userId) // the request row goes with the account
        email?.let { mailer.send(it, MailTemplate.ACCOUNT_DELETED, mapOf("user_id" to r.userId)) }
    }
}
