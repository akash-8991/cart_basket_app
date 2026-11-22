package com.smartcart.app.feature.task

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartcart.app.AppContainer
import com.smartcart.app.feature.auth.CheckRow
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.Format
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.data.AnalyticsEventName
import com.smartcart.data.ApiResult
import com.smartcart.designsystem.AppCard
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.DangerButton
import com.smartcart.designsystem.EmptyState
import com.smartcart.designsystem.KeyValueRow
import com.smartcart.designsystem.LoadingState
import com.smartcart.designsystem.Pill
import com.smartcart.designsystem.PrimaryButton
import com.smartcart.designsystem.ProviderMonogram
import com.smartcart.designsystem.SecondaryButton
import com.smartcart.designsystem.SectionHeader
import com.smartcart.designsystem.SmartCartTheme
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone
import com.smartcart.model.ActionRisk
import com.smartcart.model.ApiError
import com.smartcart.model.CartTask
import com.smartcart.model.Category
import com.smartcart.model.Handoff
import com.smartcart.model.HandoffStrategy
import com.smartcart.model.LineMatch
import com.smartcart.model.ProviderKey
import com.smartcart.model.StopReason
import com.smartcart.model.TaskEventType
import com.smartcart.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun riskTone(r: ActionRisk) = when (r) {
    ActionRisk.LOW -> Tone.NEUTRAL
    ActionRisk.MEDIUM -> Tone.INFO
    ActionRisk.HIGH -> Tone.WARNING
    ActionRisk.PROHIBITED -> Tone.DANGER
}

// ---------------------------------------------------------------- S-TASK-01 Automation consent
@Composable
fun AutomationConsentScreen(provider: ProviderKey, quoteId: String, onBack: () -> Unit, onApprove: (String) -> Unit, onManual: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var agreed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ApiError?>(null) }
    val ride = provider.category == Category.TRAVEL

    ScreenScaffold("Allow ${provider.displayName} automation", onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            item {
                Text(
                    "SmartCart can open ${provider.displayName} and ${if (ride) "fill in your pickup, drop and ride type" else "search for and add the items you approve"}. It uses Android's Accessibility service only while a task you approved is running.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                AppCard {
                    Text("It will", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        "Read ${provider.displayName} screens only during a task you approved",
                        if (ride) "Set pickup, drop and ride type" else "Search, select items and set quantities",
                        "Show every step and stop if anything looks unexpected",
                        "Ask you before any high-risk change (like a different address or item)",
                    ).forEach { Text("✓  $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                AppCard {
                    Text("It will never", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        "Pay, or enter card, UPI or bank details",
                        "Enter OTPs, passwords, CAPTCHAs or use your fingerprint/face",
                        if (ride) "Confirm or book the ride" else "Place the order",
                        "Read other apps, messages or notifications",
                    ).forEach { Text("✕  $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                Banner("You can stop any task instantly with the Stop button at the top of the screen, or turn this off at any time in Settings → Connected apps.", tone = Tone.INFO)
            }
            item {
                AppCard {
                    Text("Android setting", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "After you allow this, Android asks you to turn on SmartCart under Settings → Accessibility. In this mock build no accessibility service is installed, so this step is simulated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }) { Text("Open Accessibility settings") }
                }
            }
            item { CheckRow(agreed, { agreed = it }, "I allow SmartCart to automate ${provider.displayName} as described") }
            error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }
            item {
                PrimaryButton("Allow and continue", loading = busy, enabled = agreed, onClick = {
                    busy = true
                    scope.launch {
                        c.providers.setAutomationConsent(provider, true)
                        c.account.setConsent("automation.${provider.key}", true)
                        c.analytics.track(AnalyticsEventName.AUTOMATION_CONSENT_CHANGED, mapOf("provider" to provider.key, "granted" to true, "consent_scope" to "automation"))
                        when (val t = c.tasks.create(quoteId, provider)) {
                            is ApiResult.Success -> onApprove(t.value.taskId)
                            is ApiResult.Failure -> { error = t.error; busy = false }
                        }
                    }
                })
            }
            item { SecondaryButton("No thanks, I'll do it myself", onManual) }
        }
    }
}

// ---------------------------------------------------------------- S-TASK-02 Action preview & approval
class TaskViewModel(private val c: AppContainer, val taskId: String) : ViewModel() {
    val task: StateFlow<CartTask?> = MutableStateFlow<CartTask?>(null).also { flow ->
        viewModelScope.launch { c.tasks.observe(taskId).collect { flow.value = it } }
    }
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = _error
    private val _handoff = MutableStateFlow<Handoff?>(null)
    val handoff: StateFlow<Handoff?> = _handoff
    private var terminalLogged = false

    private fun act(block: suspend () -> ApiResult<*>, after: () -> Unit = {}) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            val r = block()
            _busy.value = false
            if (r is ApiResult.Failure) _error.value = r.error else after()
        }
    }

    fun approve(onStarted: () -> Unit) {
        val t = task.value ?: return
        act({ c.tasks.approve(taskId, t.plannedActions.map { it.actionId }) }) {
            c.analytics.track(AnalyticsEventName.TASK_APPROVED, mapOf("provider" to t.provider.key, "line_count" to t.plannedActions.size))
            onStarted()
        }
    }

    fun answer(approve: Boolean) {
        val cp = task.value?.checkpoint ?: return
        act({ c.tasks.answerCheckpoint(taskId, cp.checkpointId, approve) }) {
            c.analytics.track(AnalyticsEventName.TASK_CHECKPOINT_ANSWERED, mapOf("decision" to if (approve) "approve" else "reject"))
        }
    }

    fun resume() = act({ c.tasks.resume(taskId) })

    fun stop(reason: StopReason) = viewModelScope.launch {
        c.tasks.stop(taskId, reason)
        c.analytics.track(AnalyticsEventName.TASK_STOPPED, mapOf("stop_reason" to reason))
    }

    fun logTerminal(t: CartTask) {
        if (terminalLogged) return
        terminalLogged = true
        c.analytics.track(AnalyticsEventName.TASK_TERMINAL, mapOf("provider" to t.provider.key, "task_status" to t.status.name))
    }

    fun loadHandoff() = act({ c.tasks.createHandoff(taskId).also { if (it is ApiResult.Success) _handoff.value = it.value } })

    fun markHandedOff() = viewModelScope.launch {
        c.tasks.markHandedOff(taskId)
        c.analytics.track(AnalyticsEventName.HANDOFF_OPENED, mapOf("handoff_strategy" to _handoff.value?.strategy))
    }
}

@Composable
private fun taskVm(taskId: String): TaskViewModel {
    val c = LocalAppContainer.current
    return viewModel(key = "task-$taskId") { TaskViewModel(c, taskId) }
}

@Composable
fun ApproveScreen(taskId: String, onBack: () -> Unit, onStarted: (String) -> Unit) {
    val vm = taskVm(taskId)
    val task by vm.task.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val c = LocalAppContainer.current
    val quote = task?.let { c.comparisons.cached(it.quoteId) }
    val estimate = quote?.providerResults?.firstOrNull { it.provider == task?.provider }?.totalEstimate

    ScreenScaffold("Review and approve", onBack) { padding ->
        val t = task ?: run { LoadingState(); return@ScreenScaffold }
        if (t.status != TaskStatus.AWAITING_APPROVAL) {
            EmptyState("This request is no longer waiting", "Status: ${t.status.label}", Modifier.padding(padding), actionLabel = "View task", onAction = { onStarted(t.taskId) })
            return@ScreenScaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderMonogram(t.provider.displayName)
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(t.provider.displayName, style = MaterialTheme.typography.titleLarge)
                        estimate?.let { Text("Estimated total ${it.format()} · final price shown in ${t.provider.displayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item { SectionHeader("SmartCart will do these steps") }
            items(t.plannedActions, key = { it.actionId }) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Pill(a.risk.label, tone = riskTone(a.risk))
                }
            }
            item {
                AppCard {
                    Text("Safety limits for this task", style = MaterialTheme.typography.titleSmall)
                    KeyValueRow("Maximum steps", t.limits.maxActions.toString())
                    KeyValueRow("Maximum time", "${t.limits.maxRuntimeSeconds / 60} min")
                    KeyValueRow("Stops if idle for", "${t.limits.inactivityTimeoutSeconds} s")
                    KeyValueRow("Approval expires", Format.time(t.expiresAt))
                }
            }
            item {
                Banner(
                    "Stops automatically on any unfamiliar screen, sign-in, OTP, CAPTCHA or payment page. Payment always stays with you in ${t.provider.displayName}.",
                    tone = Tone.INFO,
                )
            }
            error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }
            item { PrimaryButton("Approve and start", onClick = { vm.approve { onStarted(t.taskId) } }, loading = busy) }
            item { SecondaryButton("Cancel", onClick = { vm.stop(StopReason.USER_CANCEL); onBack() }) }
        }
    }
}

// ---------------------------------------------------------------- S-TASK-03 Progress timeline
@Composable
fun TaskScreen(taskId: String, onBack: () -> Unit, onReady: (String) -> Unit, onManual: (ProviderKey, String?, String?) -> Unit, onHome: () -> Unit) {
    val vm = taskVm(taskId)
    val task by vm.task.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }

    // Ask for notification permission just in time, so "cart ready" can reach the user in the partner app.
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(task?.status) {
        val t = task ?: return@LaunchedEffect
        if (t.status.isTerminal) vm.logTerminal(t)
        if (t.status == TaskStatus.CART_READY) onReady(t.taskId)
    }

    ScreenScaffold("Cart task", onBack) { padding ->
        val t = task ?: run {
            EmptyState("Task not found", "It may have expired.", Modifier.padding(padding), actionLabel = "Home", onAction = onHome)
            return@ScreenScaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    ProviderMonogram(t.provider.displayName)
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(t.status.label, style = MaterialTheme.typography.titleLarge)
                        Text("${t.provider.displayName} · task ${t.taskId} · ${t.actionsUsed}/${t.limits.maxActions} steps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (t.status == TaskStatus.RUNNING || t.status == TaskStatus.APPROVED) CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            // Status-specific panels
            t.checkpoint?.takeIf { t.status == TaskStatus.CHECKPOINT }?.let { cp ->
                item {
                    AppCard {
                        Pill("Needs your decision · ${cp.risk.label}", tone = riskTone(cp.risk))
                        Spacer(Modifier.size(Spacing.sm))
                        Text(cp.prompt, style = MaterialTheme.typography.bodyLarge)
                        Text("If you don't answer within ${t.limits.inactivityTimeoutSeconds} seconds, the task stops safely.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(Spacing.sm))
                        PrimaryButton("Yes, continue", onClick = { vm.answer(true) }, loading = busy)
                        Spacer(Modifier.size(Spacing.xs))
                        SecondaryButton("No, stop the task", onClick = { vm.answer(false) })
                    }
                }
            }
            t.needsUserAction?.takeIf { t.status == TaskStatus.NEEDS_USER_ACTION }?.let { n ->
                item {
                    AppCard {
                        Pill(n.reason.title, tone = Tone.WARNING)
                        Spacer(Modifier.size(Spacing.sm))
                        Text(n.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.size(Spacing.sm))
                        PrimaryButton("I've done it — resume", onClick = vm::resume, loading = busy)
                        Spacer(Modifier.size(Spacing.xs))
                        SecondaryButton("Continue manually instead", onClick = { vm.stop(StopReason.USER_CANCEL); onManual(t.provider, t.quoteId, t.taskId) })
                    }
                }
            }
            t.failure?.let { f ->
                item {
                    Banner(
                        f.message,
                        tone = if (t.status == TaskStatus.BLOCKED_POLICY) Tone.DANGER else Tone.WARNING,
                        title = t.status.label,
                        actionLabel = "Continue manually",
                        onAction = { onManual(t.provider, t.quoteId, t.taskId) },
                    )
                }
            }
            if (t.status == TaskStatus.CANCELLED) {
                item { Banner("The task was stopped. Anything already added stays in your ${t.provider.displayName} cart; nothing else will change.", tone = Tone.INFO, title = "Stopped", actionLabel = "Continue manually", onAction = { onManual(t.provider, t.quoteId, t.taskId) }) }
            }
            if (t.status == TaskStatus.EXPIRED) {
                item { Banner("The approval window passed before the task started. Start again from the comparison.", tone = Tone.INFO, title = "Expired") }
            }
            error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }

            item { SectionHeader("Timeline") }
            // A step shows once: its "started" line is replaced by its outcome.
            val finished = t.events.filter { it.type != TaskEventType.ACTION_STARTED && it.actionId != null }.mapNotNull { it.actionId }.toSet()
            val timeline = t.events.filterNot { it.type == TaskEventType.ACTION_STARTED && it.actionId in finished }
            items(timeline.reversed(), key = { it.seq }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.Top) {
                    val (icon, tint) = when (e.type) {
                        TaskEventType.ACTION_SUCCEEDED, TaskEventType.CART_VERIFIED, TaskEventType.HANDOFF_CREATED -> Icons.Outlined.CheckCircle to SmartCartTheme.semantic.success
                        TaskEventType.HALT -> Icons.Outlined.StopCircle to SmartCartTheme.semantic.danger
                        TaskEventType.CHECKPOINT_RAISED -> Icons.Outlined.WarningAmber to SmartCartTheme.semantic.warning
                        TaskEventType.ACTION_FAILED -> Icons.Outlined.Block to SmartCartTheme.semantic.danger
                        TaskEventType.ACTION_STARTED -> Icons.Outlined.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> Icons.Outlined.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(e.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(Format.time(e.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (t.status.isActive) {
                item {
                    Spacer(Modifier.size(Spacing.md))
                    DangerButton("Emergency stop", onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth())
                }
            } else if (t.status.isTerminal) {
                item { Spacer(Modifier.size(Spacing.md)); SecondaryButton("Back to home", onHome) }
            }
        }
    }

    if (confirmStop) {
        // Stop is immediate; the dialog only guards against accidental taps on the large button.
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop now?") },
            text = { Text("SmartCart will stop immediately. Items already added stay in the cart.") },
            confirmButton = { TextButton(onClick = { vm.stop(StopReason.EMERGENCY_STOP); confirmStop = false }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep going") } },
        )
    }
}

// ---------------------------------------------------------------- S-TASK-04 Cart ready / handoff
@Composable
fun CartReadyScreen(taskId: String, onBack: () -> Unit, onManual: (ProviderKey, String) -> Unit, onHome: () -> Unit) {
    val vm = taskVm(taskId)
    val task by vm.task.collectAsStateWithLifecycle()
    val handoff by vm.handoff.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<Handoff?>(null) }
    LaunchedEffect(Unit) { vm.loadHandoff() }

    ScreenScaffold("Cart ready", onBack) { padding ->
        val t = task ?: run { LoadingState(); return@ScreenScaffold }
        val cart = t.cart
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item {
                if (cart?.verified == true) Banner("I checked the ${t.provider.displayName} cart and it matches what you approved.", tone = Tone.SUCCESS, title = "Cart verified at ${Format.time(cart.verifiedAt)}")
                else Banner("The cart could not be verified. Please check it carefully in ${t.provider.displayName}.", tone = Tone.WARNING)
            }
            if (cart?.differsFromQuote == true) item { Banner("Some items were skipped with your OK, so the earlier estimate no longer applies. Check the final price in ${t.provider.displayName}.", tone = Tone.WARNING) }
            item {
                AppCard {
                    cart?.lines?.forEach { l ->
                        KeyValueRow(
                            "${l.matchedName ?: l.requestedName}${l.matchedSize?.let { " $it" } ?: ""} × ${l.quantity}",
                            if (l.match == LineMatch.UNAVAILABLE) (l.note ?: "Skipped") else (l.lineTotal?.format() ?: ""),
                        )
                    }
                    cart?.totalEstimate?.let {
                        HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                        KeyValueRow("Estimated total", it.format(), emphasize = true)
                    }
                }
            }
            item { Text("The final price is shown in ${t.provider.displayName}. You review and pay there — SmartCart never pays for you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e), actionLabel = "Try again", onAction = vm::loadHandoff) } }
            item {
                PrimaryButton(
                    "Open cart in ${t.provider.displayName}",
                    loading = busy,
                    enabled = handoff != null,
                    onClick = { vm.markHandedOff(); opened = handoff },
                )
            }
            item { SecondaryButton("Done", onHome) }
        }
    }

    opened?.let { h ->
        val t = task
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text("Opening ${t?.provider?.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        when (h.strategy) {
                            HandoffStrategy.CART_DEEP_LINK -> "In the live app this opens your ${t?.provider?.displayName} cart directly."
                            HandoffStrategy.APP_LAUNCH -> "${t?.provider?.displayName} doesn't support opening the cart directly, so the app opens on its home screen."
                            HandoffStrategy.MANUAL -> "Open ${t?.provider?.displayName} yourself and follow these steps."
                        },
                    )
                    h.manualSteps.forEachIndexed { i, s -> Text("${i + 1}. $s") }
                    Text("This mock build does not open partner apps. Link expires ${Format.time(h.expiresAt)}.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { opened = null; onHome() }) { Text("OK") } },
        )
    }
}

// ---------------------------------------------------------------- S-TASK-05 Manual fallback
@Composable
fun ManualFallbackScreen(provider: ProviderKey, quoteId: String?, taskId: String?, onBack: () -> Unit, onHome: () -> Unit) {
    val c = LocalAppContainer.current
    LaunchedEffect(Unit) { c.analytics.track(AnalyticsEventName.MANUAL_FALLBACK_SHOWN, mapOf("provider" to provider.key)) }
    val quote = quoteId?.let { c.comparisons.cached(it) }
    val lines = quote?.providerResults?.firstOrNull { it.provider == provider }?.lines.orEmpty().ifEmpty { quote?.providerResults?.firstOrNull()?.lines.orEmpty() }
    val task by remember(taskId) { taskId?.let { c.tasks.observe(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsStateWithLifecycle(null)
    val done = task?.events?.filter { it.type == TaskEventType.ACTION_SUCCEEDED }?.map { it.description }.orEmpty()

    ScreenScaffold("Continue in ${provider.displayName}", onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            item { Text("You can finish this yourself. Here's what to do:", style = MaterialTheme.typography.bodyLarge) }
            item {
                AppCard {
                    listOf(
                        "Open ${provider.displayName}",
                        if (provider.category == Category.TRAVEL) "Enter your pickup and drop, and choose the ride type" else "Search for and add the items below",
                        "Check the total, then pay in ${provider.displayName}",
                    ).forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (done.isNotEmpty()) item { Banner("Already done: ${done.joinToString()}", tone = Tone.INFO) }
            if (lines.isNotEmpty()) {
                item { SectionHeader("Your list") }
                items(lines, key = { it.lineId }) { l -> KeyValueRow(l.requestedName, "× ${l.quantity}") }
            }
            item { Text("In this mock build partner apps are not opened.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { PrimaryButton("Done", onHome) }
        }
    }
}
