package com.smartcart.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartcart.app.AppContainer
import com.smartcart.app.BuildConfig
import com.smartcart.app.feature.auth.PasswordInput
import com.smartcart.app.ui.Routes
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.Format
import com.smartcart.app.ui.common.Load
import com.smartcart.app.ui.common.LoadContent
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.app.ui.common.toLoad
import com.smartcart.data.AnalyticsEventName
import com.smartcart.data.ApiResult
import com.smartcart.data.FlagKeys
import com.smartcart.designsystem.AppCard
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.DangerButton
import com.smartcart.designsystem.EmptyState
import com.smartcart.designsystem.KeyValueRow
import com.smartcart.designsystem.Pill
import com.smartcart.designsystem.PrimaryButton
import com.smartcart.designsystem.ProviderMonogram
import com.smartcart.designsystem.SecondaryButton
import com.smartcart.designsystem.SectionHeader
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone
import com.smartcart.model.ActivityPage
import com.smartcart.model.ApiError
import com.smartcart.model.DeleteScope
import com.smartcart.model.DeviceSession
import com.smartcart.model.PrivacyMode
import com.smartcart.model.Profile
import com.smartcart.model.ProviderStatus
import com.smartcart.model.StopReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

// ---------------------------------------------------------------- S-SET-01
@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    ScreenScaffold("Settings", onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = Spacing.xxl)) {
            item { NavRow(Icons.Outlined.Apps, "Connected apps", "Permissions and automation per app") { onNavigate(Routes.SET_PROVIDERS) } }
            item { NavRow(Icons.Outlined.PrivacyTip, "Privacy and data", "Export, delete, where data is kept") { onNavigate(Routes.SET_PRIVACY) } }
            item { NavRow(Icons.Outlined.Devices, "Devices and sessions", "Signed-in devices") { onNavigate(Routes.SET_SESSIONS) } }
            item { NavRow(Icons.Outlined.Notifications, "Notifications") { onNavigate(Routes.SET_NOTIFICATIONS) } }
            item { NavRow(Icons.AutoMirrored.Outlined.ListAlt, "Activity", "Recent automation and consent events") { onNavigate(Routes.SET_ACTIVITY) } }
            item { NavRow(Icons.Outlined.HelpOutline, "Help and emergency stop", "Pause all automation, troubleshooting") { onNavigate(Routes.HELP) } }
            if (BuildConfig.MOCK_SCENARIOS) item { NavRow(Icons.Outlined.Build, "Developer: mock scenarios", "Debug builds only") { onNavigate(Routes.DEVELOPER) } }
            item { HorizontalDivider(Modifier.padding(vertical = Spacing.sm)) }
            item {
                NavRow(Icons.Outlined.Logout, "Sign out") {
                    scope.launch {
                        c.tasks.stopAll(StopReason.USER_CANCEL)
                        c.analytics.track(AnalyticsEventName.AUTH_LOGOUT)
                        c.auth.logout()
                    }
                }
            }
            item { Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.lg)) }
        }
    }
}

// ---------------------------------------------------------------- S-SET-02
@Composable
fun ProvidersScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val providers by c.providers.providers.collectAsStateWithLifecycle()
    ScreenScaffold("Connected apps", onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item { Text("SmartCart only works with an app after you allow it. Turning a switch off takes effect immediately.", style = MaterialTheme.typography.bodyMedium) }
            items(providers.filterNot { it.hidden }, key = { it.key.key }) { p ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderMonogram(p.displayName, size = 36)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(p.category.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(p.status.label, tone = if (p.status == ProviderStatus.ENABLED) Tone.SUCCESS else Tone.WARNING)
                    }
                    Text(
                        "Cart: ${p.capabilities.cartAutomation.name.lowercase().replace('_', ' ')} · History: ${p.capabilities.historyImport.name.lowercase().replace('_', ' ')} · Open cart link: ${p.capabilities.deepLink.name.lowercase().replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val enabled = p.status == ProviderStatus.ENABLED
                    SwitchRow("Allow cart automation", p.consent.automation, enabled = enabled, onChange = { on ->
                        scope.launch {
                            c.providers.setAutomationConsent(p.key, on)
                            c.account.setConsent("automation.${p.key.key}", on)
                            c.analytics.track(AnalyticsEventName.AUTOMATION_CONSENT_CHANGED, mapOf("provider" to p.key.key, "granted" to on, "consent_scope" to "automation"))
                        }
                    })
                    SwitchRow("Allow order-history import", p.consent.history, enabled = enabled, onChange = { on ->
                        scope.launch {
                            c.providers.setHistoryConsent(p.key, on)
                            c.account.setConsent("history.${p.key.key}", on)
                            c.analytics.track(AnalyticsEventName.AUTOMATION_CONSENT_CHANGED, mapOf("provider" to p.key.key, "granted" to on, "consent_scope" to "history"))
                        }
                    })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- S-SET-03
class PrivacyViewModel(private val c: AppContainer) : ViewModel() {
    val profile = MutableStateFlow<Load<Profile>>(Load.Loading)
    val result = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<ApiError?>(null)
    val busy = MutableStateFlow(false)
    init { load() }
    fun load() { viewModelScope.launch { profile.value = c.account.profile().toLoad() } }

    fun setMode(m: PrivacyMode) = viewModelScope.launch {
        c.account.setPrivacyMode(m).let { if (it is ApiResult.Success) profile.value = Load.Ready(it.value) }
        c.analytics.track(AnalyticsEventName.PRIVACY_ACTION, mapOf("privacy_action" to "privacy_mode_changed"))
    }

    fun export(password: String) = request("export") { c.account.export(password) }
    fun delete(scope: DeleteScope, password: String) = request("delete_${scope.key}") { c.account.delete(scope, password) }

    private fun request(action: String, block: suspend () -> ApiResult<com.smartcart.model.DataRequestAccepted>) {
        busy.value = true
        error.value = null
        viewModelScope.launch {
            when (val r = block()) {
                is ApiResult.Success -> {
                    result.value = r.value.message + (r.value.slaDays?.let { " Completes within $it days." } ?: "")
                    c.analytics.track(AnalyticsEventName.PRIVACY_ACTION, mapOf("privacy_action" to action))
                    if (action == "delete_account") { c.tasks.stopAll(StopReason.USER_CANCEL); c.auth.logout() }
                }
                is ApiResult.Failure -> error.value = r.error
            }
            busy.value = false
        }
    }
}

private enum class StepUpAction(val title: String, val body: String, val confirm: String) {
    EXPORT("Export your data", "We'll email a copy of your data to your registered address.", "Request export"),
    DELETE_HISTORY("Delete order history", "Removes all imported orders and monthly totals. This can't be undone.", "Delete history"),
    DELETE_CHAT("Delete conversations", "Removes all chat history. This can't be undone.", "Delete conversations"),
    DELETE_ACCOUNT("Delete account", "Deletes your account and all data, and signs you out everywhere. This can't be undone.", "Delete account"),
}

@Composable
fun PrivacyScreen(onBack: () -> Unit, onOrders: () -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel { PrivacyViewModel(c) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var stepUp by remember { mutableStateOf<StepUpAction?>(null) }
    var cacheCleared by remember { mutableStateOf(false) }

    ScreenScaffold("Privacy and data", onBack) { padding ->
        LoadContent(profile, onRetry = vm::load) {
            val p = (profile as Load.Ready).value
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                result?.let { item { Banner(it, tone = Tone.SUCCESS, title = "Request received") } }
                error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }
                item {
                    AppCard {
                        KeyValueRow("User ID", p.userId)
                        KeyValueRow("Email", p.emailMasked + if (p.emailVerified) " · verified" else " · not verified")
                        KeyValueRow("Member since", Format.date(p.createdAt))
                    }
                }
                item { SectionHeader("Where your order history is kept") }
                item {
                    AppCard {
                        PrivacyMode.entries.forEach { m ->
                            SwitchRow(m.label, p.privacyMode == m, onChange = { if (it) vm.setMode(m) })
                        }
                    }
                }
                item { SectionHeader("Your data") }
                item { SecondaryButton("View imported orders", onOrders) }
                item { SecondaryButton("Export my data", { stepUp = StepUpAction.EXPORT }) }
                item { SecondaryButton(if (cacheCleared) "Cache cleared" else "Clear cache on this device", { cacheCleared = true; c.analytics.track(AnalyticsEventName.PRIVACY_ACTION, mapOf("privacy_action" to "clear_cache")) }, enabled = !cacheCleared) }
                item { SecondaryButton("Delete order history", { stepUp = StepUpAction.DELETE_HISTORY }) }
                item { SecondaryButton("Delete conversations", { stepUp = StepUpAction.DELETE_CHAT }) }
                item { Spacer(Modifier.size(Spacing.md)); DangerButton("Delete account", { stepUp = StepUpAction.DELETE_ACCOUNT }, Modifier.fillMaxWidth()) }
                item {
                    Text(
                        "Retention: conversations 30 days unless saved; automation logs 90 days (redacted); price comparisons 90 days; order history until you delete it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.md),
                    )
                }
            }
        }
    }

    stepUp?.let { a ->
        var password by remember(a) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { stepUp = null },
            title = { Text(a.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(a.body)
                    Text("Enter your password to confirm it's you.", style = MaterialTheme.typography.bodySmall)
                    PasswordInput(password, { password = it })
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && !busy, onClick = {
                    when (a) {
                        StepUpAction.EXPORT -> vm.export(password)
                        StepUpAction.DELETE_HISTORY -> vm.delete(DeleteScope.HISTORY, password)
                        StepUpAction.DELETE_CHAT -> vm.delete(DeleteScope.CHAT, password)
                        StepUpAction.DELETE_ACCOUNT -> vm.delete(DeleteScope.ACCOUNT, password)
                    }
                    stepUp = null
                }) { Text(a.confirm) }
            },
            dismissButton = { TextButton(onClick = { stepUp = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- S-SET-04
class SessionsViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow<Load<List<DeviceSession>>>(Load.Loading)
    val message = MutableStateFlow<String?>(null)
    init { load() }
    fun load() { state.value = Load.Loading; viewModelScope.launch { state.value = c.auth.sessions().toLoad() } }
    fun revoke(id: String) = viewModelScope.launch {
        c.auth.revokeSession(id); c.analytics.track(AnalyticsEventName.SESSION_REVOKED); message.value = "Device signed out."; load()
    }
    fun revokeAll() = viewModelScope.launch {
        val r = c.auth.revokeAllOthers()
        c.analytics.track(AnalyticsEventName.SESSION_REVOKED)
        message.value = (r as? ApiResult.Success)?.let { "Signed out ${it.value} other device(s)." }
        load()
    }
}

@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel { SessionsViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    ScreenScaffold("Devices and sessions", onBack) { padding ->
        LoadContent(state, onRetry = vm::load) {
            val sessions = (state as Load.Ready).value
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                message?.let { item { Banner(it, tone = Tone.SUCCESS) } }
                items(sessions, key = { it.sessionId }) { s ->
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.deviceLabel + if (s.current) " (this device)" else "", style = MaterialTheme.typography.titleSmall)
                                Text("App ${s.appVersion} · ${s.approximateLocation ?: "Unknown location"} · last seen ${Format.dateTime(s.lastSeenAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!s.current) TextButton(onClick = { vm.revoke(s.sessionId) }) { Text("Sign out") }
                        }
                    }
                }
                if (sessions.count { !it.current } > 0) item { SecondaryButton("Sign out all other devices", vm::revokeAll) }
                item { Text("Don't recognise a device? Sign it out and change your password.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---------------------------------------------------------------- S-SET-05
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val v by c.notificationPrefs.values.collectAsStateWithLifecycle()
    ScreenScaffold("Notifications", onBack) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = Spacing.lg)) {
            SwitchRow("Cart ready", v.cartReady, { on -> c.notificationPrefs.update { it.copy(cartReady = on) } })
            SwitchRow("Task needs you", v.needsUserAction, { on -> c.notificationPrefs.update { it.copy(needsUserAction = on) } }, subtitle = "Sign-in, OTP or a decision is needed")
            SwitchRow("Order history imported", v.importComplete, { on -> c.notificationPrefs.update { it.copy(importComplete = on) } })
            SwitchRow("Monthly summary", v.monthlySummary, { on -> c.notificationPrefs.update { it.copy(monthlySummary = on) } })
            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            SwitchRow("Hide details on lock screen", v.lockScreenPrivate, { on -> c.notificationPrefs.update { it.copy(lockScreenPrivate = on) } }, subtitle = "Shows \"Your cart is ready\" without the app name")
        }
    }
}

// ---------------------------------------------------------------- S-SET-06
class ActivityViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow<Load<ActivityPage>>(Load.Loading)
    init { load() }
    fun load() { state.value = Load.Loading; viewModelScope.launch { state.value = c.account.activity().toLoad() } }
}

@Composable
fun ActivityScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel { ActivityViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold("Activity", onBack) { padding ->
        LoadContent(state, onRetry = vm::load) {
            val events = (state as Load.Ready).value.events
            if (events.isEmpty()) {
                EmptyState("No activity yet", "Automation, consent and data events will appear here.", Modifier.padding(padding))
                return@LoadContent
            }
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl)) {
                items(events, key = { it.eventId }) { e ->
                    Column(Modifier.padding(vertical = Spacing.sm)) {
                        Text(e.summary, style = MaterialTheme.typography.bodyMedium)
                        Text(Format.dateTime(e.at) + (e.taskId?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- S-SET-07
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val paused by c.account.automationPaused.collectAsStateWithLifecycle()
    val active by c.tasks.activeTask.collectAsStateWithLifecycle()
    ScreenScaffold("Help and emergency stop", onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item {
                AppCard {
                    Text("Emergency stop", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (paused) "All automation is paused. No app will be opened or changed until you resume."
                        else "Stops any running task immediately and prevents new ones until you resume.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(Spacing.sm))
                    if (paused) {
                        PrimaryButton("Resume automation", onClick = { c.account.setAutomationPaused(false) })
                    } else {
                        DangerButton("Stop and pause all automation", onClick = {
                            scope.launch {
                                c.account.setAutomationPaused(true)
                                c.tasks.stopAll(StopReason.PAUSE_ALL)
                                c.analytics.track(AnalyticsEventName.TASK_STOPPED, mapOf("stop_reason" to "pause_all"))
                            }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    active?.let { Text("Running now: ${it.provider.displayName} (${it.status.label})", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { SectionHeader("Troubleshooting") }
            listOf(
                "A task stopped on an unfamiliar screen" to "Apps change their screens. SmartCart stops rather than guess. Finish in the app yourself; your list is shown on the next screen.",
                "The price in the app is different" to "Comparisons are estimates checked at a point in time. The app always shows the final price before you pay.",
                "The app asked me to sign in or enter an OTP" to "SmartCart never enters passwords or OTPs. Do it yourself, then tap Resume.",
                "I can't find my cart" to "Open the app and tap its cart icon. Some apps don't support opening the cart directly.",
            ).forEach { (q, a) ->
                item {
                    AppCard {
                        Text(q, style = MaterialTheme.typography.titleSmall)
                        Text(a, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
