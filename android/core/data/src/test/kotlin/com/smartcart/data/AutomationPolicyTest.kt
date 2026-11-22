package com.smartcart.data

import com.smartcart.data.automation.AutomationPolicy
import com.smartcart.data.automation.PolicyDecision
import com.smartcart.data.automation.ScreenObservation
import com.smartcart.data.automation.TaskStateMachine
import com.smartcart.model.ActionRisk
import com.smartcart.model.FailureCode
import com.smartcart.model.TaskStatus
import com.smartcart.model.UserActionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPolicyTest {

    @Test
    fun paymentScreenIsAlwaysBlocked() {
        assertEquals(PolicyDecision.Block(FailureCode.POLICY_BLOCKED), AutomationPolicy.evaluate(ScreenObservation.PaymentScreen))
    }

    @Test
    fun credentialsAndChallengesAreHandedToTheUserNeverEntered() {
        val cases = mapOf(
            ScreenObservation.OtpPrompt to UserActionReason.OTP_REQUIRED,
            ScreenObservation.PasswordPrompt to UserActionReason.PASSWORD_PROMPT,
            ScreenObservation.Captcha to UserActionReason.CAPTCHA,
            ScreenObservation.BiometricPrompt to UserActionReason.BIOMETRIC,
            ScreenObservation.LoginRequired to UserActionReason.LOGIN_REQUIRED,
            ScreenObservation.UnexpectedDialog to UserActionReason.UNEXPECTED_DIALOG,
        )
        cases.forEach { (obs, reason) -> assertEquals(obs.toString(), PolicyDecision.HandToUser(reason), AutomationPolicy.evaluate(obs)) }
    }

    @Test
    fun unknownScreenAndWrongPackageHaltSafely() {
        assertEquals(PolicyDecision.Halt(FailureCode.UNKNOWN_SCREEN), AutomationPolicy.evaluate(ScreenObservation.UnknownScreen))
        assertEquals(PolicyDecision.Halt(FailureCode.PACKAGE_MISMATCH), AutomationPolicy.evaluate(ScreenObservation.PackageMismatch))
    }

    @Test
    fun stateDriftAndUnavailableItemsRequireHighRiskConfirmation() {
        val d1 = AutomationPolicy.evaluate(ScreenObservation.StateDrift("Address changed."))
        val d2 = AutomationPolicy.evaluate(ScreenObservation.ItemUnavailable("Eggs"))
        assertTrue(d1 is PolicyDecision.AskUser && d1.risk == ActionRisk.HIGH)
        assertTrue(d2 is PolicyDecision.AskUser && d2.risk == ActionRisk.HIGH)
    }

    @Test
    fun budgetsAndKillSwitchOverrideEverything() {
        val over = AutomationPolicy.Budget(actionsUsed = 25, maxActions = 25, runtimeSeconds = 1, maxRuntimeSeconds = 180)
        assertEquals(PolicyDecision.Halt(FailureCode.MAX_ACTIONS_REACHED), AutomationPolicy.evaluate(ScreenObservation.Expected, over))
        val slow = AutomationPolicy.Budget(actionsUsed = 1, maxActions = 25, runtimeSeconds = 180, maxRuntimeSeconds = 180)
        assertEquals(PolicyDecision.Halt(FailureCode.RUNTIME_EXCEEDED), AutomationPolicy.evaluate(ScreenObservation.Expected, slow))
        assertEquals(PolicyDecision.Halt(FailureCode.PROVIDER_KILL_SWITCH), AutomationPolicy.evaluate(ScreenObservation.PaymentScreen, providerKillSwitch = true))
    }

    @Test
    fun stateMachineAllowsOnlyCanonicalTransitions() {
        assertTrue(TaskStateMachine.canTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.APPROVED))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.RUNNING, TaskStatus.CART_READY))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.CART_READY, TaskStatus.HANDED_OFF))
        // No automation without approval.
        assertFalse(TaskStateMachine.canTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.RUNNING))
        assertFalse(TaskStateMachine.canTransition(TaskStatus.DRAFT, TaskStatus.RUNNING))
        // Terminal states never restart.
        TaskStatus.TERMINAL.forEach { t -> TaskStatus.entries.forEach { assertFalse("$t -> $it", TaskStateMachine.canTransition(t, it)) } }
        // A cart is never "ready" without running.
        assertFalse(TaskStateMachine.canTransition(TaskStatus.APPROVED, TaskStatus.CART_READY))
    }
}
