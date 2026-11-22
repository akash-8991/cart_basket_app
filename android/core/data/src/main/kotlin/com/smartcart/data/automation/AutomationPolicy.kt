package com.smartcart.data.automation

import com.smartcart.model.ActionRisk
import com.smartcart.model.FailureCode
import com.smartcart.model.TaskStatus
import com.smartcart.model.UserActionReason

/**
 * Deterministic automation safety policy (PRD v1.4 §4, audit §9). Shared by the Phase 1 simulator
 * and the Phase 4 on-device runtime. The model never decides any of this.
 */
object TaskStateMachine {
    private val allowed: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.DRAFT to setOf(TaskStatus.AWAITING_APPROVAL, TaskStatus.CANCELLED),
        TaskStatus.AWAITING_APPROVAL to setOf(TaskStatus.APPROVED, TaskStatus.CANCELLED, TaskStatus.EXPIRED),
        TaskStatus.APPROVED to setOf(TaskStatus.RUNNING, TaskStatus.CANCELLED, TaskStatus.EXPIRED, TaskStatus.BLOCKED_POLICY),
        TaskStatus.RUNNING to setOf(
            TaskStatus.CHECKPOINT, TaskStatus.NEEDS_USER_ACTION, TaskStatus.CART_READY,
            TaskStatus.CANCELLED, TaskStatus.FAILED_SAFE, TaskStatus.BLOCKED_POLICY,
        ),
        TaskStatus.CHECKPOINT to setOf(TaskStatus.RUNNING, TaskStatus.CANCELLED, TaskStatus.FAILED_SAFE),
        TaskStatus.NEEDS_USER_ACTION to setOf(TaskStatus.RUNNING, TaskStatus.CANCELLED, TaskStatus.FAILED_SAFE, TaskStatus.BLOCKED_POLICY),
        TaskStatus.CART_READY to setOf(TaskStatus.HANDED_OFF, TaskStatus.EXPIRED),
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean = allowed[from]?.contains(to) == true

    fun require(from: TaskStatus, to: TaskStatus) {
        check(canTransition(from, to)) { "Illegal task transition $from -> $to" }
    }
}

/** What the automation runtime observes on screen before each action. */
sealed interface ScreenObservation {
    data object Expected : ScreenObservation
    data object PackageMismatch : ScreenObservation
    data object UnknownScreen : ScreenObservation
    data object PaymentScreen : ScreenObservation
    data object OtpPrompt : ScreenObservation
    data object PasswordPrompt : ScreenObservation
    data object Captcha : ScreenObservation
    data object BiometricPrompt : ScreenObservation
    data object LoginRequired : ScreenObservation
    data object UnexpectedDialog : ScreenObservation
    data class ItemUnavailable(val itemLabel: String) : ScreenObservation
    /** Address, quantity, price or task context differs from what the user approved. */
    data class StateDrift(val description: String) : ScreenObservation
}

sealed interface PolicyDecision {
    data object Continue : PolicyDecision
    data class AskUser(val prompt: String, val risk: ActionRisk) : PolicyDecision
    data class HandToUser(val reason: UserActionReason) : PolicyDecision
    data class Halt(val code: FailureCode) : PolicyDecision
    data class Block(val code: FailureCode) : PolicyDecision
}

object AutomationPolicy {
    data class Budget(val actionsUsed: Int, val maxActions: Int, val runtimeSeconds: Long, val maxRuntimeSeconds: Int)

    fun evaluate(observation: ScreenObservation, budget: Budget? = null, providerKillSwitch: Boolean = false): PolicyDecision {
        if (providerKillSwitch) return PolicyDecision.Halt(FailureCode.PROVIDER_KILL_SWITCH)
        if (budget != null) {
            if (budget.actionsUsed >= budget.maxActions) return PolicyDecision.Halt(FailureCode.MAX_ACTIONS_REACHED)
            if (budget.runtimeSeconds >= budget.maxRuntimeSeconds) return PolicyDecision.Halt(FailureCode.RUNTIME_EXCEEDED)
        }
        return when (observation) {
            ScreenObservation.Expected -> PolicyDecision.Continue
            ScreenObservation.PackageMismatch -> PolicyDecision.Halt(FailureCode.PACKAGE_MISMATCH)
            ScreenObservation.UnknownScreen -> PolicyDecision.Halt(FailureCode.UNKNOWN_SCREEN)
            // Payment is prohibited outright; the task cannot continue automatically.
            ScreenObservation.PaymentScreen -> PolicyDecision.Block(FailureCode.POLICY_BLOCKED)
            // Credentials and challenges are handed to the user, never entered.
            ScreenObservation.OtpPrompt -> PolicyDecision.HandToUser(UserActionReason.OTP_REQUIRED)
            ScreenObservation.PasswordPrompt -> PolicyDecision.HandToUser(UserActionReason.PASSWORD_PROMPT)
            ScreenObservation.Captcha -> PolicyDecision.HandToUser(UserActionReason.CAPTCHA)
            ScreenObservation.BiometricPrompt -> PolicyDecision.HandToUser(UserActionReason.BIOMETRIC)
            ScreenObservation.LoginRequired -> PolicyDecision.HandToUser(UserActionReason.LOGIN_REQUIRED)
            ScreenObservation.UnexpectedDialog -> PolicyDecision.HandToUser(UserActionReason.UNEXPECTED_DIALOG)
            is ScreenObservation.ItemUnavailable -> PolicyDecision.AskUser(
                "${observation.itemLabel} is out of stock. Skip it and continue with the other items?",
                ActionRisk.HIGH,
            )
            is ScreenObservation.StateDrift -> PolicyDecision.AskUser(
                "${observation.description} Continue anyway?",
                ActionRisk.HIGH,
            )
        }
    }
}
