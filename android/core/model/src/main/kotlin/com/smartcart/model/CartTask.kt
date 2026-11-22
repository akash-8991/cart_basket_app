package com.smartcart.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Canonical task states (D-002). */
@Serializable
enum class TaskStatus {
    DRAFT,
    AWAITING_APPROVAL,
    APPROVED,
    RUNNING,
    CHECKPOINT,
    NEEDS_USER_ACTION,
    CART_READY,
    HANDED_OFF,
    CANCELLED,
    EXPIRED,
    FAILED_SAFE,
    BLOCKED_POLICY;

    val isTerminal: Boolean get() = this in TERMINAL
    /** States during which the global Stop control must be visible. */
    val isActive: Boolean get() = this in ACTIVE

    companion object {
        val TERMINAL = setOf(CANCELLED, EXPIRED, FAILED_SAFE, BLOCKED_POLICY, HANDED_OFF)
        val ACTIVE = setOf(APPROVED, RUNNING, CHECKPOINT, NEEDS_USER_ACTION)
    }
}

@Serializable
enum class ActionRisk {
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
    @SerialName("prohibited") PROHIBITED,
}

@Serializable
enum class ActionType {
    @SerialName("open_app") OPEN_APP,
    @SerialName("search") SEARCH,
    @SerialName("select_item") SELECT_ITEM,
    @SerialName("set_quantity") SET_QUANTITY,
    @SerialName("choose_variant") CHOOSE_VARIANT,
    @SerialName("choose_slot") CHOOSE_SLOT,
    @SerialName("set_pickup") SET_PICKUP,
    @SerialName("set_drop") SET_DROP,
    @SerialName("choose_ride_class") CHOOSE_RIDE_CLASS,
    @SerialName("remove_item") REMOVE_ITEM,
    @SerialName("change_address") CHANGE_ADDRESS,
    @SerialName("verify_cart") VERIFY_CART,
}

@Serializable
data class PlannedAction(val actionId: String, val type: ActionType, val description: String, val risk: ActionRisk)

@Serializable
enum class TaskEventType {
    @SerialName("state_changed") STATE_CHANGED,
    @SerialName("action_started") ACTION_STARTED,
    @SerialName("action_succeeded") ACTION_SUCCEEDED,
    @SerialName("action_failed") ACTION_FAILED,
    @SerialName("checkpoint_raised") CHECKPOINT_RAISED,
    @SerialName("checkpoint_answered") CHECKPOINT_ANSWERED,
    @SerialName("halt") HALT,
    @SerialName("cart_verified") CART_VERIFIED,
    @SerialName("handoff_created") HANDOFF_CREATED,
}

@Serializable
data class TaskEvent(
    val seq: Int,
    val at: String,
    val type: TaskEventType,
    val description: String,
    val actionId: String? = null,
)

@Serializable
data class TaskLimits(val maxActions: Int, val maxRuntimeSeconds: Int, val inactivityTimeoutSeconds: Int)

@Serializable
data class Checkpoint(val checkpointId: String, val prompt: String, val risk: ActionRisk)

@Serializable
enum class UserActionReason {
    @SerialName("login_required") LOGIN_REQUIRED,
    @SerialName("otp_required") OTP_REQUIRED,
    @SerialName("captcha") CAPTCHA,
    @SerialName("biometric") BIOMETRIC,
    @SerialName("password_prompt") PASSWORD_PROMPT,
    @SerialName("unexpected_dialog") UNEXPECTED_DIALOG,
    @SerialName("location_permission") LOCATION_PERMISSION,
    @SerialName("app_update_required") APP_UPDATE_REQUIRED,
    @SerialName("item_unavailable") ITEM_UNAVAILABLE,
}

@Serializable
data class NeedsUserAction(val reason: UserActionReason, val message: String)

@Serializable
data class VerifiedCart(
    val verified: Boolean,
    val verifiedAt: String? = null,
    val lines: List<MatchedLine>,
    val totalEstimate: Money? = null,
    val differsFromQuote: Boolean = false,
)

@Serializable
enum class FailureCode {
    @SerialName("unknown_screen") UNKNOWN_SCREEN,
    @SerialName("package_mismatch") PACKAGE_MISMATCH,
    @SerialName("max_actions_reached") MAX_ACTIONS_REACHED,
    @SerialName("runtime_exceeded") RUNTIME_EXCEEDED,
    @SerialName("inactivity_timeout") INACTIVITY_TIMEOUT,
    @SerialName("cart_verification_failed") CART_VERIFICATION_FAILED,
    @SerialName("provider_kill_switch") PROVIDER_KILL_SWITCH,
    @SerialName("network_lost") NETWORK_LOST,
    @SerialName("app_not_installed") APP_NOT_INSTALLED,
    @SerialName("policy_blocked") POLICY_BLOCKED,
}

@Serializable
data class TaskFailure(val code: FailureCode, val message: String)

@Serializable
data class CartTask(
    val taskId: String,
    val provider: ProviderKey,
    val quoteId: String,
    val status: TaskStatus,
    val createdAt: String,
    val expiresAt: String,
    val limits: TaskLimits,
    val actionsUsed: Int = 0,
    val plannedActions: List<PlannedAction>,
    val events: List<TaskEvent>,
    val checkpoint: Checkpoint? = null,
    val needsUserAction: NeedsUserAction? = null,
    val cart: VerifiedCart? = null,
    val failure: TaskFailure? = null,
)

@Serializable
enum class StopReason {
    @SerialName("emergency_stop") EMERGENCY_STOP,
    @SerialName("user_cancel") USER_CANCEL,
    @SerialName("pause_all") PAUSE_ALL,
}

@Serializable
enum class HandoffStrategy {
    @SerialName("cart_deep_link") CART_DEEP_LINK,
    @SerialName("app_launch") APP_LAUNCH,
    @SerialName("manual") MANUAL,
}

@Serializable
data class Handoff(
    val handoffUrl: String,
    val strategy: HandoffStrategy,
    val targetPackage: String? = null,
    val expiresAt: String,
    val manualSteps: List<String>,
)
