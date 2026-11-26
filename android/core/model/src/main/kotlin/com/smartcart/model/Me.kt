package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PrivacyMode {
    @SerialName("device_only") DEVICE_ONLY,
    @SerialName("normalized_upload") NORMALIZED_UPLOAD,
}

@Serializable
data class Profile(
    val userId: String,
    val emailMasked: String,
    val emailVerified: Boolean,
    val privacyMode: PrivacyMode,
    val createdAt: String,
)

@Serializable
data class Consent(val key: String, val granted: Boolean, val version: String, val updatedAt: String)

@Serializable
data class ConsentList(val consents: List<Consent>)

@Serializable
enum class ActivityType {
    @SerialName("automation_approved") AUTOMATION_APPROVED,
    @SerialName("automation_stopped") AUTOMATION_STOPPED,
    @SerialName("automation_completed") AUTOMATION_COMPLETED,
    @SerialName("automation_failed") AUTOMATION_FAILED,
    @SerialName("consent_granted") CONSENT_GRANTED,
    @SerialName("consent_revoked") CONSENT_REVOKED,
    @SerialName("history_imported") HISTORY_IMPORTED,
    @SerialName("data_exported") DATA_EXPORTED,
    @SerialName("data_deleted") DATA_DELETED,
    @SerialName("session_revoked") SESSION_REVOKED,
}

@Serializable
data class ActivityEvent(
    val eventId: String,
    val at: String,
    val type: ActivityType,
    val summary: String,
    val provider: ProviderKey? = null,
    val taskId: String? = null,
    /** CR-002 E: stable template key and values for app-side copy; [summary] is the English fallback. */
    val summaryKey: String? = null,
    val summaryArgs: Map<String, String>? = null,
)

@Serializable
data class ActivityPage(val events: List<ActivityEvent>, val nextCursor: String? = null)

@Serializable
enum class DataRequestStatus {
    @SerialName("queued") QUEUED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
}

@Serializable
data class DataRequestAccepted(
    val requestId: String,
    val status: DataRequestStatus,
    val message: String,
    val slaDays: Int? = null,
)

enum class DeleteScope(val key: String) { HISTORY("history"), CHAT("chat"), ACCOUNT("account") }
