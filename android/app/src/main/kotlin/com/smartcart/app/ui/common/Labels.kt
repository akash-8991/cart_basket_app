package com.smartcart.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.smartcart.app.R
import com.smartcart.model.ActionRisk
import com.smartcart.model.Availability
import com.smartcart.model.CartAutomationCapability
import com.smartcart.model.Category
import com.smartcart.model.Confidence
import com.smartcart.model.ComparisonStatus
import com.smartcart.model.DeepLinkCapability
import com.smartcart.model.FieldProblem
import com.smartcart.model.HistoryCapability
import com.smartcart.model.ImportField
import com.smartcart.model.ImportStatus
import com.smartcart.model.LineMatch
import com.smartcart.model.Objective
import com.smartcart.model.OrderStatus
import com.smartcart.model.PrivacyMode
import com.smartcart.model.ProviderCapabilities
import com.smartcart.model.ProviderImportStatus
import com.smartcart.model.ProviderStatus
import com.smartcart.model.RecommendationLabel
import com.smartcart.model.RideClass
import com.smartcart.model.TaskStatus
import com.smartcart.model.UserActionReason
import com.smartcart.model.AuthRules

/**
 * Maps contract enums to string resources. The core model carries no user-facing copy, so every
 * label is translatable and can change under post-freeze copy rules without touching logic.
 */
@StringRes fun Category.titleRes() = when (this) {
    Category.TRAVEL -> R.string.category_travel
    Category.FOOD -> R.string.category_food
    Category.GROCERY -> R.string.category_grocery
    Category.SHOPPING -> R.string.category_shopping
}

@StringRes fun TaskStatus.labelRes() = when (this) {
    TaskStatus.DRAFT -> R.string.task_status_draft
    TaskStatus.AWAITING_APPROVAL -> R.string.task_status_awaiting_approval
    TaskStatus.APPROVED -> R.string.task_status_approved
    TaskStatus.RUNNING -> R.string.task_status_running
    TaskStatus.CHECKPOINT -> R.string.task_status_checkpoint
    TaskStatus.NEEDS_USER_ACTION -> R.string.task_status_needs_user_action
    TaskStatus.CART_READY -> R.string.task_status_cart_ready
    TaskStatus.HANDED_OFF -> R.string.task_status_handed_off
    TaskStatus.CANCELLED -> R.string.task_status_cancelled
    TaskStatus.EXPIRED -> R.string.task_status_expired
    TaskStatus.FAILED_SAFE -> R.string.task_status_failed_safe
    TaskStatus.BLOCKED_POLICY -> R.string.task_status_blocked_policy
}

@StringRes fun ActionRisk.labelRes() = when (this) {
    ActionRisk.LOW -> R.string.risk_low
    ActionRisk.MEDIUM -> R.string.risk_medium
    ActionRisk.HIGH -> R.string.risk_high
    ActionRisk.PROHIBITED -> R.string.risk_prohibited
}

@StringRes fun UserActionReason.labelRes() = when (this) {
    UserActionReason.LOGIN_REQUIRED -> R.string.reason_login_required
    UserActionReason.OTP_REQUIRED -> R.string.reason_otp_required
    UserActionReason.CAPTCHA -> R.string.reason_captcha
    UserActionReason.BIOMETRIC -> R.string.reason_biometric
    UserActionReason.PASSWORD_PROMPT -> R.string.reason_password_prompt
    UserActionReason.UNEXPECTED_DIALOG -> R.string.reason_unexpected_dialog
    UserActionReason.LOCATION_PERMISSION -> R.string.reason_location_permission
    UserActionReason.APP_UPDATE_REQUIRED -> R.string.reason_app_update_required
    UserActionReason.ITEM_UNAVAILABLE -> R.string.reason_item_unavailable
}

@StringRes fun Objective.labelRes() = when (this) {
    Objective.LOWEST_TOTAL -> R.string.objective_lowest_total
    Objective.FASTEST -> R.string.objective_fastest
    Objective.BEST_VALUE -> R.string.objective_best_value
    Objective.FEWEST_PROVIDERS -> R.string.objective_fewest_providers
    Objective.PREFERRED_PROVIDER -> R.string.objective_preferred_provider
}

@StringRes fun LineMatch.labelRes() = when (this) {
    LineMatch.EXACT -> R.string.match_exact
    LineMatch.EQUIVALENT -> R.string.match_equivalent
    LineMatch.DIFFERENT_PACK -> R.string.match_different_pack
    LineMatch.DIFFERENT_CLASS -> R.string.match_different_class
    LineMatch.UNAVAILABLE -> R.string.match_unavailable
    LineMatch.UNCERTAIN -> R.string.match_uncertain
}

@StringRes fun Availability.labelRes() = when (this) {
    Availability.AVAILABLE -> R.string.availability_available
    Availability.PARTIAL -> R.string.availability_partial
    Availability.UNAVAILABLE -> R.string.availability_unavailable
    Availability.NOT_CHECKED -> R.string.availability_not_checked
    Availability.FAILED -> R.string.availability_failed
}

@StringRes fun ComparisonStatus.labelRes() = when (this) {
    ComparisonStatus.IN_PROGRESS -> R.string.cmp_status_in_progress
    ComparisonStatus.COMPLETE -> R.string.cmp_status_complete
    ComparisonStatus.PARTIAL_COVERAGE -> R.string.cmp_status_partial
    ComparisonStatus.STALE -> R.string.cmp_status_stale
    ComparisonStatus.INCOMPARABLE -> R.string.cmp_status_incomparable
    ComparisonStatus.UNAVAILABLE -> R.string.cmp_status_unavailable
    ComparisonStatus.PRICE_CHANGED -> R.string.cmp_status_price_changed
    ComparisonStatus.MANUAL_ONLY -> R.string.cmp_status_manual_only
}

/** Null for [RecommendationLabel.NONE]: no badge is shown. */
@StringRes fun RecommendationLabel.labelRes(): Int? = when (this) {
    RecommendationLabel.CHEAPEST -> R.string.rec_label_cheapest
    RecommendationLabel.LOWEST_OBSERVED_ESTIMATE -> R.string.rec_label_lowest_observed_estimate
    RecommendationLabel.FASTEST -> R.string.rec_label_fastest
    RecommendationLabel.BEST_VALUE -> R.string.rec_label_best_value
    RecommendationLabel.PREFERRED -> R.string.rec_label_preferred
    RecommendationLabel.NONE -> null
}

@StringRes fun Confidence.labelRes() = when (this) {
    Confidence.HIGH -> R.string.confidence_high
    Confidence.MEDIUM -> R.string.confidence_medium
    Confidence.LOW -> R.string.confidence_low
}

@StringRes fun RideClass.labelRes() = when (this) {
    RideClass.BIKE -> R.string.ride_bike
    RideClass.AUTO -> R.string.ride_auto
    RideClass.MINI -> R.string.ride_mini
    RideClass.SEDAN -> R.string.ride_sedan
    RideClass.SUV -> R.string.ride_suv
    RideClass.ANY -> R.string.ride_any
}

@StringRes fun PrivacyMode.labelRes() = when (this) {
    PrivacyMode.DEVICE_ONLY -> R.string.privacy_device_only
    PrivacyMode.NORMALIZED_UPLOAD -> R.string.privacy_normalized_upload
}

@StringRes fun PrivacyMode.descriptionRes() = when (this) {
    PrivacyMode.DEVICE_ONLY -> R.string.privacy_device_only_desc
    PrivacyMode.NORMALIZED_UPLOAD -> R.string.privacy_normalized_upload_desc
}

@StringRes fun ProviderStatus.labelRes() = when (this) {
    ProviderStatus.ENABLED -> R.string.provider_status_enabled
    ProviderStatus.DISABLED -> R.string.provider_status_disabled
    ProviderStatus.KILL_SWITCHED -> R.string.provider_status_kill_switched
    ProviderStatus.PENDING_APPROVAL -> R.string.provider_status_pending_approval
}

@StringRes fun ImportStatus.labelRes() = when (this) {
    ImportStatus.AWAITING_PERMISSION -> R.string.import_awaiting_permission
    ImportStatus.RUNNING -> R.string.import_running
    ImportStatus.PAUSED -> R.string.import_paused
    ImportStatus.AWAITING_REVIEW -> R.string.import_awaiting_review
    ImportStatus.COMPLETED -> R.string.import_completed
    ImportStatus.CANCELLED -> R.string.import_cancelled
    ImportStatus.FAILED -> R.string.import_failed
}

@StringRes fun ProviderImportStatus.labelRes() = when (this) {
    ProviderImportStatus.PENDING -> R.string.pimport_pending
    ProviderImportStatus.RUNNING -> R.string.pimport_running
    ProviderImportStatus.COMPLETED -> R.string.pimport_completed
    ProviderImportStatus.FAILED -> R.string.pimport_failed
    ProviderImportStatus.SKIPPED -> R.string.pimport_skipped
}

@StringRes fun OrderStatus.labelRes() = when (this) {
    OrderStatus.PLACED -> R.string.order_placed
    OrderStatus.CONFIRMED -> R.string.order_confirmed
    OrderStatus.PREPARING -> R.string.order_preparing
    OrderStatus.OUT_FOR_DELIVERY -> R.string.order_out_for_delivery
    OrderStatus.DELIVERED -> R.string.order_delivered
    OrderStatus.CANCELLED -> R.string.order_cancelled
    OrderStatus.REFUNDED -> R.string.order_refunded
    OrderStatus.PARTIALLY_REFUNDED -> R.string.order_partially_refunded
    OrderStatus.UNKNOWN -> R.string.order_unknown
}

@StringRes fun ImportField.labelRes() = when (this) {
    ImportField.ORDER_DATE -> R.string.field_order_date
    ImportField.ITEMS -> R.string.field_items
    ImportField.QUANTITY -> R.string.field_quantity
    ImportField.AMOUNTS -> R.string.field_amounts
    ImportField.STATUS -> R.string.field_status
    ImportField.LOCATION_LABEL -> R.string.field_location_label
}

/** Cost-row keys match the contract field names used in `unavailable_fields`. */
@StringRes fun costRowRes(key: String) = when (key) {
    "item_subtotal" -> R.string.cost_item_subtotal
    "delivery_fee" -> R.string.cost_delivery_fee
    "packaging_fee" -> R.string.cost_packaging_fee
    "platform_fee" -> R.string.cost_platform_fee
    "small_cart_fee" -> R.string.cost_small_cart_fee
    "surge_or_booking_fee" -> R.string.cost_surge_or_booking_fee
    "taxes" -> R.string.cost_taxes
    "discounts" -> R.string.cost_discounts
    else -> null
}

/** A contract field name as a readable label, e.g. "platform_fee" → "Platform fee". */
@Composable
fun costFieldLabel(key: String): String = costRowRes(key)?.let { stringResource(it) } ?: key.replace('_', ' ')

@Composable
fun capabilitySummary(c: ProviderCapabilities): String {
    val cart = stringResource(
        when (c.cartAutomation) {
            CartAutomationCapability.SUPPORTED -> R.string.cap_supported
            CartAutomationCapability.RIDE_SETUP_ONLY -> R.string.cap_ride_setup_only
            CartAutomationCapability.MANUAL_ONLY -> R.string.cap_manual_only
            CartAutomationCapability.UNSUPPORTED -> R.string.cap_unsupported
        },
    )
    val history = stringResource(
        when (c.historyImport) {
            HistoryCapability.SUPPORTED -> R.string.cap_supported
            HistoryCapability.MANUAL_ONLY -> R.string.cap_manual_only
            HistoryCapability.UNSUPPORTED -> R.string.cap_unsupported
        },
    )
    val link = stringResource(
        when (c.deepLink) {
            DeepLinkCapability.CART -> R.string.cap_link_cart
            DeepLinkCapability.APP_LAUNCH -> R.string.cap_link_app
            DeepLinkCapability.NONE -> R.string.cap_link_none
        },
    )
    return stringResource(R.string.cap_summary, cart, history, link)
}

/** Validation copy for auth fields. */
@Composable
fun userIdProblem(p: FieldProblem?): String? = when (p) {
    null -> null
    FieldProblem.TOO_SHORT -> stringResource(R.string.problem_user_id_short, AuthRules.USER_ID_MIN)
    FieldProblem.TOO_LONG -> stringResource(R.string.problem_too_long, AuthRules.USER_ID_MAX)
    else -> stringResource(R.string.problem_user_id_chars)
}

@Composable
fun passwordProblem(p: FieldProblem?): String? = when (p) {
    null -> null
    FieldProblem.TOO_SHORT -> stringResource(R.string.problem_password_short, AuthRules.PASSWORD_MIN)
    FieldProblem.TOO_LONG -> stringResource(R.string.problem_too_long, AuthRules.PASSWORD_MAX)
    FieldProblem.CONTAINS_USER_ID -> stringResource(R.string.problem_contains_user_id)
    else -> stringResource(R.string.problem_repetitive)
}

@Composable
fun emailProblem(p: FieldProblem?): String? = p?.let { stringResource(R.string.problem_email) }

/** Shorthand used by screens. */
@Composable fun Category.title(): String = stringResource(titleRes())
@Composable fun TaskStatus.label(): String = stringResource(labelRes())
@Composable fun Confidence.label(): String = stringResource(labelRes())
