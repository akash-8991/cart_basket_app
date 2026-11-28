package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// CR-005: what the phone reports to the server. The server treats both as untrusted readings: it works out
// every figure from a PriceObservation and checks every TaskProgressReport against the task state machine.

/** Which adapter (and version) read the app. */
@Serializable
data class AdapterRef(val key: String, val version: String)

/** What the app's search results said about one quote line. */
@Serializable
data class ObservedOffer(
    val lineId: String,
    val found: Boolean,
    val name: String? = null,
    val unitPrice: Money? = null,
    val inStock: Boolean = false,
)

/** Fees the app states. Null means not stated; the server never assumes zero. */
@Serializable
data class ObservedFees(
    val deliveryFee: Money? = null,
    val packagingFee: Money? = null,
    val platformFee: Money? = null,
    val smallCartFee: Money? = null,
    val surgeOrBookingFee: Money? = null,
    val taxes: Money? = null,
    val discounts: Money? = null,
)

@Serializable
enum class ObservationOutcome {
    @SerialName("read") READ,
    @SerialName("not_read") NOT_READ,
}

@Serializable
enum class NotReadReason {
    @SerialName("service_off") SERVICE_OFF,
    @SerialName("consent_missing") CONSENT_MISSING,
    @SerialName("app_not_installed") APP_NOT_INSTALLED,
    @SerialName("package_mismatch") PACKAGE_MISMATCH,
    @SerialName("login_required") LOGIN_REQUIRED,
    @SerialName("otp_required") OTP_REQUIRED,
    @SerialName("captcha") CAPTCHA,
    @SerialName("biometric") BIOMETRIC,
    @SerialName("password_prompt") PASSWORD_PROMPT,
    @SerialName("payment_screen") PAYMENT_SCREEN,
    @SerialName("unknown_screen") UNKNOWN_SCREEN,
    @SerialName("unexpected_dialog") UNEXPECTED_DIALOG,
    @SerialName("timeout") TIMEOUT,
    @SerialName("kill_switch") KILL_SWITCH,
    @SerialName("stopped") STOPPED,
}

/** Body of `POST /v1/comparison/quotes/{quoteId}/observations`. */
@Serializable
data class PriceObservation(
    val provider: ProviderKey,
    val capturedAt: String,
    val outcome: ObservationOutcome,
    val notReadReason: NotReadReason? = null,
    val adapter: AdapterRef,
    val offers: List<ObservedOffer> = emptyList(),
    val fees: ObservedFees? = null,
    val etaMinutes: Int? = null,
)

/** Body of `POST /v1/cart-tasks/{taskId}/progress`: the device's state and the events since the last report. */
@Serializable
data class TaskProgressReport(
    val reportSeq: Int,
    val status: TaskStatus,
    val actionsUsed: Int,
    val events: List<TaskEvent>,
    val checkpoint: Checkpoint? = null,
    val needsUserAction: NeedsUserAction? = null,
    val failure: TaskFailure? = null,
    val cart: VerifiedCart? = null,
    val adapter: AdapterRef,
) {
    companion object {
        /** The states a device may report (CR-005); the rest belong to approve, handoff and the server clock. */
        val DEVICE_STATUSES = setOf(
            TaskStatus.RUNNING, TaskStatus.CHECKPOINT, TaskStatus.NEEDS_USER_ACTION, TaskStatus.CART_READY,
            TaskStatus.FAILED_SAFE, TaskStatus.BLOCKED_POLICY, TaskStatus.CANCELLED,
        )
    }
}
