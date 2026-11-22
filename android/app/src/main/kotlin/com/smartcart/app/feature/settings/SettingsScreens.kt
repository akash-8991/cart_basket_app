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
import com.smartcart.app.R
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.capabilitySummary
import com.smartcart.app.ui.common.label
import com.smartcart.app.ui.common.labelRes
import com.smartcart.app.ui.common.title
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    ScreenScaffold(stringResource(R.string.set_title), onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = Spacing.xxl)) {
            item { NavRow(Icons.Outlined.Apps, stringResource(R.string.set_providers), stringResource(R.string.set_providers_sub)) { onNavigate(Routes.SET_PROVIDERS) } }
            item { NavRow(Icons.Outlined.PrivacyTip, stringResource(R.string.set_privacy), stringResource(R.string.set_privacy_sub)) { onNavigate(Routes.SET_PRIVACY) } }
            item { NavRow(Icons.Outlined.Devices, stringResource(R.string.set_sessions), stringResource(R.string.set_sessions_sub)) { onNavigate(Routes.SET_SESSIONS) } }
            item { NavRow(Icons.Outlined.Notifications, stringResource(R.string.set_notifications)) { onNavigate(Routes.SET_NOTIFICATIONS) } }
            item { NavRow(Icons.AutoMirrored.Outlined.ListAlt, stringResource(R.string.set_activity), stringResource(R.string.set_activity_sub)) { onNavigate(Routes.SET_ACTIVITY) } }
            item { NavRow(Icons.Outlined.HelpOutline, stringResource(R.string.set_help), stringResource(R.string.set_help_sub)) { onNavigate(Routes.HELP) } }
            if (BuildConfig.MOCK_SCENARIOS) item { NavRow(Icons.Outlined.Build, stringResource(R.string.set_developer), stringResource(R.string.set_developer_sub)) { onNavigate(Routes.DEVELOPER) } }
            item { HorizontalDivider(Modifier.padding(vertical = Spacing.sm)) }
            item {
                NavRow(Icons.Outlined.Logout, stringResource(R.string.set_sign_out)) {
                    scope.launch {
                        c.tasks.stopAll(StopReason.USER_CANCEL)
                        c.analytics.track(AnalyticsEventName.AUTH_LOGOUT)
                        c.auth.logout()
                    }
                }
            }
            item { Text(stringResource(R.string.set_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.lg)) }
        }
    }
}

// ---------------------------------------------------------------- S-SET-02
@Composable
fun ProvidersScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val providers by c.providers.providers.collectAsStateWithLifecycle()
    ScreenScaffold(stringResource(R.string.set_providers), onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item { Text(stringResource(R.string.providers_intro), style = MaterialTheme.typography.bodyMedium) }
            items(providers.filterNot { it.hidden }, key = { it.key.key }) { p ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderMonogram(p.displayName, size = 36)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(p.category.title(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(stringResource(p.status.labelRes()), tone = if (p.status == ProviderStatus.ENABLED) Tone.SUCCESS else Tone.WARNING)
                    }
                    Text(
                        capabilitySummary(p.capabilities),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val enabled = p.status == ProviderStatus.ENABLED
                    SwitchRow(stringResource(R.string.providers_allow_cart), p.consent.automation, enabled = enabled, onChange = { on ->
                        scope.launch {
                            c.providers.setAutomationConsent(p.key, on)
                            c.account.setConsent("automation.${p.key.key}", on)
                            c.analytics.track(AnalyticsEventName.AUTOMATION_CONSENT_CHANGED, mapOf("provider" to p.key.key, "granted" to on, "consent_scope" to "automation"))
                        }
                    })
                    SwitchRow(stringResource(R.string.providers_allow_history), p.consent.history, enabled = enabled, onChange = { on ->
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
    /** Successful request: whether it was an export, plus the server SLA in days. */
    val result = MutableStateFlow<Pair<Boolean, Int?>?>(null)
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
                    result.value = (action == "export") to r.value.slaDays
                    c.analytics.track(AnalyticsEventName.PRIVACY_ACTION, mapOf("privacy_action" to action))
                    if (action == "delete_account") { c.tasks.stopAll(StopReason.USER_CANCEL); c.auth.logout() }
                }
                is ApiResult.Failure -> error.value = r.error
            }
            busy.value = false
        }
    }
}

private enum class StepUpAction(@StringRes val title: Int, @StringRes val body: Int, @StringRes val confirm: Int) {
    EXPORT(R.string.stepup_export_title, R.string.stepup_export_body, R.string.stepup_export_confirm),
    DELETE_HISTORY(R.string.stepup_history_title, R.string.stepup_history_body, R.string.stepup_history_confirm),
    DELETE_CHAT(R.string.stepup_chat_title, R.string.stepup_chat_body, R.string.stepup_chat_confirm),
    DELETE_ACCOUNT(R.string.stepup_account_title, R.string.stepup_account_body, R.string.stepup_account_confirm),
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

    ScreenScaffold(stringResource(R.string.set_privacy), onBack) { padding ->
        LoadContent(profile, onRetry = vm::load) {
            val p = (profile as Load.Ready).value
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                result?.let { (isExport, sla) ->
                    item {
                        val msg = stringResource(if (isExport) R.string.privacy_export_queued else R.string.privacy_delete_queued)
                        Banner(sla?.let { pluralStringResource(R.plurals.privacy_sla, it, msg, it) } ?: msg, tone = Tone.SUCCESS, title = stringResource(R.string.privacy_request_received))
                    }
                }
                error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }
                item {
                    AppCard {
                        KeyValueRow(stringResource(R.string.privacy_user_id), p.userId)
                        KeyValueRow(stringResource(R.string.privacy_email), stringResource(if (p.emailVerified) R.string.privacy_email_verified else R.string.privacy_email_unverified, p.emailMasked))
                        KeyValueRow(stringResource(R.string.privacy_member_since), Format.date(p.createdAt))
                    }
                }
                item { SectionHeader(stringResource(R.string.privacy_where_kept)) }
                item {
                    AppCard {
                        PrivacyMode.entries.forEach { m ->
                            SwitchRow(stringResource(m.labelRes()), p.privacyMode == m, onChange = { if (it) vm.setMode(m) })
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.privacy_your_data)) }
                item { SecondaryButton(stringResource(R.string.privacy_view_orders), onOrders) }
                item { SecondaryButton(stringResource(R.string.privacy_export), { stepUp = StepUpAction.EXPORT }) }
                item { SecondaryButton(stringResource(if (cacheCleared) R.string.privacy_cache_cleared else R.string.privacy_clear_cache), { cacheCleared = true; c.analytics.track(AnalyticsEventName.PRIVACY_ACTION, mapOf("privacy_action" to "clear_cache")) }, enabled = !cacheCleared) }
                item { SecondaryButton(stringResource(R.string.privacy_delete_history), { stepUp = StepUpAction.DELETE_HISTORY }) }
                item { SecondaryButton(stringResource(R.string.privacy_delete_chat), { stepUp = StepUpAction.DELETE_CHAT }) }
                item { Spacer(Modifier.size(Spacing.md)); DangerButton(stringResource(R.string.privacy_delete_account), { stepUp = StepUpAction.DELETE_ACCOUNT }, Modifier.fillMaxWidth()) }
                item {
                    Text(
                        stringResource(R.string.privacy_retention),
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
            title = { Text(stringResource(a.title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(a.body))
                    Text(stringResource(R.string.privacy_confirm_identity), style = MaterialTheme.typography.bodySmall)
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
                }) { Text(stringResource(a.confirm)) }
            },
            dismissButton = { TextButton(onClick = { stepUp = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

// ---------------------------------------------------------------- S-SET-04
class SessionsViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow<Load<List<DeviceSession>>>(Load.Loading)
    /** Number of devices just signed out (shown as a confirmation), or null. */
    val revoked = MutableStateFlow<Int?>(null)
    init { load() }
    fun load() { state.value = Load.Loading; viewModelScope.launch { state.value = c.auth.sessions().toLoad() } }
    fun revoke(id: String) = viewModelScope.launch {
        c.auth.revokeSession(id); c.analytics.track(AnalyticsEventName.SESSION_REVOKED); revoked.value = -1; load()
    }
    fun revokeAll() = viewModelScope.launch {
        val r = c.auth.revokeAllOthers()
        c.analytics.track(AnalyticsEventName.SESSION_REVOKED)
        revoked.value = (r as? ApiResult.Success)?.value
        load()
    }
}

@Composable
fun SessionsScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel { SessionsViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val revoked by vm.revoked.collectAsStateWithLifecycle()
    ScreenScaffold(stringResource(R.string.set_sessions), onBack) { padding ->
        LoadContent(state, onRetry = vm::load) {
            val sessions = (state as Load.Ready).value
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                revoked?.let { n -> item { Banner(if (n < 0) stringResource(R.string.sessions_one_revoked) else pluralStringResource(R.plurals.sessions_all_revoked, n, n), tone = Tone.SUCCESS) } }
                items(sessions, key = { it.sessionId }) { s ->
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (s.current) stringResource(R.string.sessions_this_device, s.deviceLabel) else s.deviceLabel, style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.sessions_meta, s.appVersion, s.approximateLocation ?: stringResource(R.string.sessions_unknown_location), Format.dateTime(s.lastSeenAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!s.current) TextButton(onClick = { vm.revoke(s.sessionId) }) { Text(stringResource(R.string.sessions_sign_out)) }
                        }
                    }
                }
                if (sessions.count { !it.current } > 0) item { SecondaryButton(stringResource(R.string.sessions_sign_out_all), vm::revokeAll) }
                item { Text(stringResource(R.string.sessions_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---------------------------------------------------------------- S-SET-05
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val v by c.notificationPrefs.values.collectAsStateWithLifecycle()
    ScreenScaffold(stringResource(R.string.set_notifications), onBack) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = Spacing.lg)) {
            SwitchRow(stringResource(R.string.notif_pref_cart_ready), v.cartReady, { on -> c.notificationPrefs.update { it.copy(cartReady = on) } })
            SwitchRow(stringResource(R.string.notif_pref_needs_you), v.needsUserAction, { on -> c.notificationPrefs.update { it.copy(needsUserAction = on) } }, subtitle = stringResource(R.string.notif_pref_needs_you_sub))
            SwitchRow(stringResource(R.string.notif_pref_import), v.importComplete, { on -> c.notificationPrefs.update { it.copy(importComplete = on) } })
            SwitchRow(stringResource(R.string.notif_pref_monthly), v.monthlySummary, { on -> c.notificationPrefs.update { it.copy(monthlySummary = on) } })
            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            SwitchRow(stringResource(R.string.notif_pref_lock_screen), v.lockScreenPrivate, { on -> c.notificationPrefs.update { it.copy(lockScreenPrivate = on) } }, subtitle = stringResource(R.string.notif_pref_lock_screen_sub))
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
    ScreenScaffold(stringResource(R.string.set_activity), onBack) { padding ->
        LoadContent(state, onRetry = vm::load) {
            val events = (state as Load.Ready).value.events
            if (events.isEmpty()) {
                EmptyState(stringResource(R.string.activity_empty_title), stringResource(R.string.activity_empty_body), Modifier.padding(padding))
                return@LoadContent
            }
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl)) {
                items(events, key = { it.eventId }) { e ->
                    Column(Modifier.padding(vertical = Spacing.sm)) {
                        Text(e.summary, style = MaterialTheme.typography.bodyMedium)
                        Text(e.taskId?.let { stringResource(R.string.activity_meta_task, Format.dateTime(e.at), it) } ?: Format.dateTime(e.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    ScreenScaffold(stringResource(R.string.set_help), onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item {
                AppCard {
                    Text(stringResource(R.string.help_stop_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (paused) R.string.help_paused_body else R.string.help_active_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(Spacing.sm))
                    if (paused) {
                        PrimaryButton(stringResource(R.string.help_resume), onClick = { c.account.setAutomationPaused(false) })
                    } else {
                        DangerButton(stringResource(R.string.help_pause_all), onClick = {
                            scope.launch {
                                c.account.setAutomationPaused(true)
                                c.tasks.stopAll(StopReason.PAUSE_ALL)
                                c.analytics.track(AnalyticsEventName.TASK_STOPPED, mapOf("stop_reason" to "pause_all"))
                            }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    active?.let { Text(stringResource(R.string.help_running_now, it.provider.displayName, it.status.label()), style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { SectionHeader(stringResource(R.string.help_troubleshooting)) }
            listOf(
                R.string.help_q_unknown_screen to R.string.help_a_unknown_screen,
                R.string.help_q_price to R.string.help_a_price,
                R.string.help_q_otp to R.string.help_a_otp,
                R.string.help_q_cart to R.string.help_a_cart,
            ).forEach { (q, a) ->
                item {
                    AppCard {
                        Text(stringResource(q), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(a), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
