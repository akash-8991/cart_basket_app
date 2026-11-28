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
import com.smartcart.app.R
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.label
import com.smartcart.app.ui.common.labelRes
import androidx.compose.ui.res.stringResource
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

    ScreenScaffold(stringResource(R.string.consent_title, provider.displayName), onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            item {
                Text(
                    stringResource(if (ride) R.string.consent_intro_ride else R.string.consent_intro_cart, provider.displayName),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                AppCard {
                    Text(stringResource(R.string.consent_will), style = MaterialTheme.typography.titleSmall)
                    listOf(
                        stringResource(R.string.consent_will_read, provider.displayName),
                        stringResource(if (ride) R.string.consent_will_ride else R.string.consent_will_cart),
                        stringResource(R.string.consent_will_show),
                        stringResource(R.string.consent_will_ask),
                    ).forEach { Text(stringResource(R.string.consent_will_item, it), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                AppCard {
                    Text(stringResource(R.string.consent_never), style = MaterialTheme.typography.titleSmall)
                    listOf(
                        stringResource(R.string.consent_never_pay),
                        stringResource(R.string.consent_never_secrets),
                        stringResource(if (ride) R.string.consent_never_book else R.string.consent_never_order),
                        stringResource(R.string.consent_never_read),
                    ).forEach { Text(stringResource(R.string.consent_never_item, it), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                Banner(stringResource(R.string.consent_stop_note), tone = Tone.INFO)
            }
            item {
                AppCard {
                    Text(stringResource(R.string.consent_android_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.consent_android_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }) { Text(stringResource(R.string.consent_open_settings)) }
                }
            }
            item { CheckRow(agreed, { agreed = it }, stringResource(R.string.consent_agree, provider.displayName)) }
            error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }
            item {
                PrimaryButton(stringResource(R.string.consent_allow), loading = busy, enabled = agreed, onClick = {
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
            item { SecondaryButton(stringResource(R.string.consent_decline), onManual) }
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
    /** CR-006: the link, once used; and whether it no longer worked. */
    private val _redemption = MutableStateFlow<com.smartcart.model.HandoffRedemption?>(null)
    val redemption: StateFlow<com.smartcart.model.HandoffRedemption?> = _redemption
    private val _linkExpired = MutableStateFlow(false)
    val linkExpired: StateFlow<Boolean> = _linkExpired
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

    /** Uses the cart's one-time link: the cart is handed off, and the app opens it (or shows the steps). */
    fun openCart() {
        val h = _handoff.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            when (val r = c.tasks.redeemHandoff(h.handoffUrl.substringAfterLast('/'))) {
                is ApiResult.Success -> {
                    _redemption.value = r.value
                    c.analytics.track(AnalyticsEventName.HANDOFF_OPENED, mapOf("handoff_strategy" to r.value.strategy))
                }
                is ApiResult.Failure -> if (r.error.code == com.smartcart.model.ErrorCode.NOT_FOUND) _linkExpired.value = true else _error.value = r.error
            }
            _busy.value = false
        }
    }

    fun dismissOpened() { _redemption.value = null }

    /** A link works once and for 10 minutes; a new one replaces it. */
    fun newLink() {
        _linkExpired.value = false
        loadHandoff()
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

    ScreenScaffold(stringResource(R.string.approve_title), onBack) { padding ->
        val t = task ?: run { LoadingState(); return@ScreenScaffold }
        if (t.status != TaskStatus.AWAITING_APPROVAL) {
            EmptyState(stringResource(R.string.approve_not_waiting), stringResource(R.string.approve_status, t.status.label()), Modifier.padding(padding), actionLabel = stringResource(R.string.approve_view_task), onAction = { onStarted(t.taskId) })
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
                        estimate?.let { Text(stringResource(R.string.approve_estimate, it.format(), t.provider.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.approve_steps)) }
            items(t.plannedActions, key = { it.actionId }) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Pill(stringResource(a.risk.labelRes()), tone = riskTone(a.risk))
                }
            }
            item {
                AppCard {
                    Text(stringResource(R.string.approve_limits), style = MaterialTheme.typography.titleSmall)
                    KeyValueRow(stringResource(R.string.approve_max_steps), t.limits.maxActions.toString())
                    KeyValueRow(stringResource(R.string.approve_max_time), stringResource(R.string.approve_minutes, t.limits.maxRuntimeSeconds / 60))
                    KeyValueRow(stringResource(R.string.approve_idle), stringResource(R.string.approve_seconds, t.limits.inactivityTimeoutSeconds))
                    KeyValueRow(stringResource(R.string.approve_expires), Format.time(t.expiresAt))
                }
            }
            item {
                Banner(
                    stringResource(R.string.approve_safety_note, t.provider.displayName),
                    tone = Tone.INFO,
                )
            }
            error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }
            item { PrimaryButton(stringResource(R.string.approve_start), onClick = { vm.approve { onStarted(t.taskId) } }, loading = busy) }
            item { SecondaryButton(stringResource(R.string.common_cancel), onClick = { vm.stop(StopReason.USER_CANCEL); onBack() }) }
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

    ScreenScaffold(stringResource(R.string.task_title), onBack) { padding ->
        val t = task ?: run {
            EmptyState(stringResource(R.string.task_not_found), stringResource(R.string.task_not_found_body), Modifier.padding(padding), actionLabel = stringResource(R.string.common_home), onAction = onHome)
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
                        Text(t.status.label(), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.task_meta, t.provider.displayName, t.taskId, t.actionsUsed, t.limits.maxActions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (t.status == TaskStatus.RUNNING || t.status == TaskStatus.APPROVED) CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            // Status-specific panels
            t.checkpoint?.takeIf { t.status == TaskStatus.CHECKPOINT }?.let { cp ->
                item {
                    AppCard {
                        Pill(stringResource(R.string.task_needs_decision, stringResource(cp.risk.labelRes())), tone = riskTone(cp.risk))
                        Spacer(Modifier.size(Spacing.sm))
                        Text(cp.prompt, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.task_decision_timeout, t.limits.inactivityTimeoutSeconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(Spacing.sm))
                        PrimaryButton(stringResource(R.string.task_yes_continue), onClick = { vm.answer(true) }, loading = busy)
                        Spacer(Modifier.size(Spacing.xs))
                        SecondaryButton(stringResource(R.string.task_no_stop), onClick = { vm.answer(false) })
                    }
                }
            }
            t.needsUserAction?.takeIf { t.status == TaskStatus.NEEDS_USER_ACTION }?.let { n ->
                item {
                    AppCard {
                        Pill(stringResource(n.reason.labelRes()), tone = Tone.WARNING)
                        Spacer(Modifier.size(Spacing.sm))
                        Text(n.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.size(Spacing.sm))
                        PrimaryButton(stringResource(R.string.task_resume), onClick = vm::resume, loading = busy)
                        Spacer(Modifier.size(Spacing.xs))
                        SecondaryButton(stringResource(R.string.task_manual_instead), onClick = { vm.stop(StopReason.USER_CANCEL); onManual(t.provider, t.quoteId, t.taskId) })
                    }
                }
            }
            t.failure?.let { f ->
                item {
                    Banner(
                        f.message,
                        tone = if (t.status == TaskStatus.BLOCKED_POLICY) Tone.DANGER else Tone.WARNING,
                        title = t.status.label(),
                        actionLabel = stringResource(R.string.common_continue_manually),
                        onAction = { onManual(t.provider, t.quoteId, t.taskId) },
                    )
                }
            }
            if (t.status == TaskStatus.CANCELLED) {
                item { Banner(stringResource(R.string.task_stopped_body, t.provider.displayName), tone = Tone.INFO, title = stringResource(R.string.task_stopped_title), actionLabel = stringResource(R.string.common_continue_manually), onAction = { onManual(t.provider, t.quoteId, t.taskId) }) }
            }
            if (t.status == TaskStatus.EXPIRED) {
                item { Banner(stringResource(R.string.task_expired_body), tone = Tone.INFO, title = stringResource(R.string.task_expired_title)) }
            }
            error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }

            item { SectionHeader(stringResource(R.string.task_timeline)) }
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
                    DangerButton(stringResource(R.string.task_emergency_stop), onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth())
                }
            } else if (t.status.isTerminal) {
                item { Spacer(Modifier.size(Spacing.md)); SecondaryButton(stringResource(R.string.common_back_home), onHome) }
            }
        }
    }

    if (confirmStop) {
        // Stop is immediate; the dialog only guards against accidental taps on the large button.
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.task_stop_confirm_title)) },
            text = { Text(stringResource(R.string.task_stop_confirm_body)) },
            confirmButton = { TextButton(onClick = { vm.stop(StopReason.EMERGENCY_STOP); confirmStop = false }) { Text(stringResource(R.string.task_stop_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.task_keep_going)) } },
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
    val opened by vm.redemption.collectAsStateWithLifecycle()
    val linkExpired by vm.linkExpired.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadHandoff() }
    // CR-006: a verified app is opened with an explicit package intent, never an implicit one.
    LaunchedEffect(opened) {
        val r = opened ?: return@LaunchedEffect
        val pkg = r.targetPackage?.takeIf { r.strategy != HandoffStrategy.MANUAL } ?: return@LaunchedEffect
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    ScreenScaffold(stringResource(R.string.ready_title), onBack) { padding ->
        val t = task ?: run { LoadingState(); return@ScreenScaffold }
        val cart = t.cart
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item {
                if (cart?.verified == true) Banner(stringResource(R.string.ready_verified_body, t.provider.displayName), tone = Tone.SUCCESS, title = stringResource(R.string.ready_verified_title, Format.time(cart.verifiedAt)))
                else Banner(stringResource(R.string.ready_unverified, t.provider.displayName), tone = Tone.WARNING)
            }
            if (cart?.differsFromQuote == true) item { Banner(stringResource(R.string.ready_differs, t.provider.displayName), tone = Tone.WARNING) }
            item {
                AppCard {
                    cart?.lines?.forEach { l ->
                        KeyValueRow(
                            stringResource(R.string.ready_line, listOfNotNull(l.matchedName ?: l.requestedName, l.matchedSize).joinToString(" "), l.quantity),
                            if (l.match == LineMatch.UNAVAILABLE) (l.note ?: stringResource(R.string.ready_skipped)) else (l.lineTotal?.format() ?: ""),
                        )
                    }
                    cart?.totalEstimate?.let {
                        HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
                        KeyValueRow(stringResource(R.string.ready_estimated_total), it.format(), emphasize = true)
                    }
                }
            }
            item { Text(stringResource(R.string.ready_pay_note, t.provider.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e), actionLabel = stringResource(R.string.common_try_again), onAction = vm::loadHandoff) } }
            if (linkExpired) item {
                Banner(stringResource(R.string.ready_link_expired_body), tone = Tone.WARNING, title = stringResource(R.string.ready_link_expired_title), actionLabel = stringResource(R.string.ready_link_new), onAction = vm::newLink)
            }
            item {
                PrimaryButton(
                    stringResource(R.string.ready_open_cart, t.provider.displayName),
                    loading = busy,
                    enabled = handoff != null && !linkExpired,
                    onClick = vm::openCart,
                )
            }
            item { SecondaryButton(stringResource(R.string.common_done), onHome) }
        }
    }

    opened?.let { h ->
        val t = task
        AlertDialog(
            onDismissRequest = vm::dismissOpened,
            title = { Text(stringResource(R.string.ready_opening, t?.provider?.displayName.orEmpty())) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        when (h.strategy) {
                            HandoffStrategy.CART_DEEP_LINK -> stringResource(R.string.ready_strategy_cart, t?.provider?.displayName.orEmpty())
                            HandoffStrategy.APP_LAUNCH -> stringResource(R.string.ready_strategy_app, t?.provider?.displayName.orEmpty())
                            HandoffStrategy.MANUAL -> stringResource(R.string.ready_strategy_manual, t?.provider?.displayName.orEmpty())
                        },
                    )
                    h.manualSteps.forEachIndexed { i, s -> Text(stringResource(R.string.ready_step, i + 1, s)) }
                    Text(stringResource(R.string.ready_link_note), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissOpened(); onHome() }) { Text(stringResource(R.string.common_ok)) } },
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

    ScreenScaffold(stringResource(R.string.manual_title, provider.displayName), onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            item { Text(stringResource(R.string.manual_intro), style = MaterialTheme.typography.bodyLarge) }
            item {
                AppCard {
                    listOf(
                        stringResource(R.string.manual_step_open, provider.displayName),
                        stringResource(if (provider.category == Category.TRAVEL) R.string.manual_step_ride else R.string.manual_step_cart),
                        stringResource(R.string.manual_step_pay, provider.displayName),
                    ).forEachIndexed { i, s -> Text(stringResource(R.string.ready_step, i + 1, s), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (done.isNotEmpty()) item { Banner(stringResource(R.string.manual_already_done, done.joinToString()), tone = Tone.INFO) }
            if (lines.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.manual_your_list)) }
                items(lines, key = { it.lineId }) { l -> KeyValueRow(l.requestedName, stringResource(R.string.manual_qty, l.quantity)) }
            }
            item { Text(stringResource(R.string.common_mock_no_partner_apps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { PrimaryButton(stringResource(R.string.common_done), onHome) }
        }
    }
}
