package com.smartcart.app.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The AccessibilityService that drives partner apps (Phase 4.1). Declared only in debug builds and limited
 * to SmartCart Test Store until the Play policy review (R-01) and provider approvals allow more. It does
 * nothing on its own: a [com.smartcart.automation.TaskRunner] acts through it for an approved task.
 */
class SmartCartAutomationService : AccessibilityService() {
    override fun onServiceConnected() {
        AutomationHost.connected.value = AccessibilityDriver(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit // the runner reads the screen when it needs to
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AutomationHost.connected.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AutomationHost.connected.value = null
        super.onDestroy()
    }
}

/** The connected service's driver, or null while the user hasn't turned the service on. */
object AutomationHost {
    internal val connected = MutableStateFlow<AccessibilityDriver?>(null)
    val driver: StateFlow<AccessibilityDriver?> = connected
}
