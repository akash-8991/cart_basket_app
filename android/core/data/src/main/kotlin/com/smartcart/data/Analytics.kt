package com.smartcart.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.util.UUID

/** Frozen analytics taxonomy v1 (contracts/schemas/analytics-event.schema.json). */
enum class AnalyticsEventName(val wire: String) {
    SCREEN_VIEW("screen_view"),
    AUTH_REGISTER_RESULT("auth_register_result"),
    AUTH_LOGIN_RESULT("auth_login_result"),
    AUTH_RECOVERY_REQUESTED("auth_recovery_requested"),
    AUTH_LOGOUT("auth_logout"),
    HOME_MONTH_CHANGED("home_month_changed"),
    CATEGORY_SELECTED("category_selected"),
    CHAT_MESSAGE_SENT("chat_message_sent"),
    CHAT_CLARIFICATION_ANSWERED("chat_clarification_answered"),
    COMPARISON_REQUESTED("comparison_requested"),
    COMPARISON_VIEWED("comparison_viewed"),
    COMPARISON_PROVIDER_CHOSEN("comparison_provider_chosen"),
    COMPARISON_SWITCH_CONFIRMED("comparison_switch_confirmed"),
    AUTOMATION_CONSENT_CHANGED("automation_consent_changed"),
    TASK_APPROVED("task_approved"),
    TASK_CHECKPOINT_ANSWERED("task_checkpoint_answered"),
    TASK_STOPPED("task_stopped"),
    TASK_TERMINAL("task_terminal"),
    HANDOFF_OPENED("handoff_opened"),
    MANUAL_FALLBACK_SHOWN("manual_fallback_shown"),
    HISTORY_IMPORT_STARTED("history_import_started"),
    HISTORY_IMPORT_FINISHED("history_import_finished"),
    SUGGESTION_OPENED("suggestion_opened"),
    SUGGESTION_FEEDBACK("suggestion_feedback"),
    PRIVACY_ACTION("privacy_action"),
    SESSION_REVOKED("session_revoked"),
    NOTIFICATION_OPENED("notification_opened"),
}

/** Allowed property keys. Anything else is dropped so free text or PII cannot leak. */
private val ALLOWED_PROPS = setOf(
    "screen", "result", "error_code", "category", "provider", "recommended_provider", "comparison_status",
    "objective", "providers_checked", "task_status", "stop_reason", "decision", "granted", "consent_scope",
    "privacy_action", "useful", "line_count", "duration_ms", "handoff_strategy", "notification_type", "degraded",
)

@Serializable
data class AnalyticsEvent(
    val schemaVersion: Int = 1,
    val event: String,
    val at: String,
    val installId: String,
    val sessionId: String,
    val appVersion: String,
    val props: Map<String, JsonPrimitive>,
)

interface Analytics {
    fun track(event: AnalyticsEventName, props: Map<String, Any?> = emptyMap())
}

/** Phase 1 local event collector (PRD v1.3 Phase 1). Events are kept in memory and shown in Developer settings. */
class LocalAnalyticsCollector(
    private val appVersion: String,
    private val installId: String = UUID.randomUUID().toString(),
    private val clock: Clock = Clock.systemUTC(),
    private val capacity: Int = 200,
) : Analytics {
    private val sessionId = UUID.randomUUID().toString()
    private val _events = MutableStateFlow<List<AnalyticsEvent>>(emptyList())
    val events: StateFlow<List<AnalyticsEvent>> = _events

    override fun track(event: AnalyticsEventName, props: Map<String, Any?>) {
        val clean = props.filterKeys { it in ALLOWED_PROPS }.mapNotNull { (k, v) ->
            when (v) {
                null -> null
                is Boolean -> k to JsonPrimitive(v)
                is Number -> k to JsonPrimitive(v)
                is Enum<*> -> k to JsonPrimitive(v.name.lowercase())
                // Only short machine tokens are allowed as strings.
                is String -> if (Regex("^[A-Za-z0-9_-]{1,40}$").matches(v)) k to JsonPrimitive(v) else null
                else -> null
            }
        }.toMap()
        val e = AnalyticsEvent(event = event.wire, at = clock.instant().toString(), installId = installId, sessionId = sessionId, appVersion = appVersion, props = clean)
        _events.update { (listOf(e) + it).take(capacity) }
    }
}
