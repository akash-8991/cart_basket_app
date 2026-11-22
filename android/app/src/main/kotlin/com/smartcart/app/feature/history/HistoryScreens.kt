package com.smartcart.app.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartcart.app.AppContainer
import com.smartcart.app.feature.auth.CheckRow
import com.smartcart.app.feature.home.confidenceTone
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.Format
import com.smartcart.app.ui.common.Load
import com.smartcart.app.ui.common.LoadContent
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.app.ui.common.toLoad
import com.smartcart.data.AnalyticsEventName
import com.smartcart.data.ApiResult
import com.smartcart.designsystem.AppCard
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.EmptyState
import com.smartcart.designsystem.LoadingState
import com.smartcart.designsystem.Pill
import com.smartcart.designsystem.PrimaryButton
import com.smartcart.designsystem.ProgressRow
import com.smartcart.designsystem.ProviderMonogram
import com.smartcart.designsystem.SecondaryButton
import com.smartcart.designsystem.SectionHeader
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone
import com.smartcart.model.ApiError
import com.smartcart.model.ImportField
import com.smartcart.model.ImportStatus
import com.smartcart.model.Order
import com.smartcart.model.OrderStatus
import com.smartcart.model.PrivacyMode
import com.smartcart.model.ProviderImportStatus
import com.smartcart.model.ProviderKey
import com.smartcart.model.ProviderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- S-HIST-01
@Composable
fun HistoryConnectScreen(onBack: () -> Unit, onStarted: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val providers by c.providers.providers.collectAsStateWithLifecycle()
    val eligible = providers.filter { !it.hidden && it.status == ProviderStatus.ENABLED }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var fields by rememberSaveable { mutableStateOf(ImportField.entries.filter { it != ImportField.LOCATION_LABEL }.map { it.key }.toSet()) }
    var mode by rememberSaveable { mutableStateOf(PrivacyMode.DEVICE_ONLY) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ApiError?>(null) }

    ScreenScaffold("Connect order history", onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.navigationBarsPadding()) {
            item {
                Text(
                    "SmartCart opens each app you choose, goes to its order history and reads only the fields below. Reading happens on this phone; raw screens are never saved or uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { SectionHeader("Apps") }
            items(eligible, key = { it.key.key }) { p ->
                CheckRow(p.key.key in selected, { on -> selected = if (on) selected + p.key.key else selected - p.key.key }, "${p.displayName} · ${p.category.title}")
            }
            item { SectionHeader("What's collected") }
            items(ImportField.entries, key = { it.key }) { f ->
                CheckRow(f.key in fields, { on -> fields = if (on) fields + f.key else fields - f.key }, f.label)
            }
            item { Text("Never collected: payment methods, card or UPI details, passwords, OTPs, chats with sellers or drivers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { SectionHeader("Where your history is kept") }
            items(PrivacyMode.entries, key = { it.name }) { m ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).selectable(mode == m, role = Role.RadioButton, onClick = { mode = m }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == m, onClick = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
                        Text(m.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (m == PrivacyMode.DEVICE_ONLY) "Monthly totals are calculated on this phone only." else "Only normalized order summaries are uploaded after you review them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            error?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e)) } }
            item {
                Spacer(Modifier.size(Spacing.md))
                PrimaryButton("Allow and import", enabled = selected.isNotEmpty() && fields.isNotEmpty(), loading = busy, onClick = {
                    busy = true
                    scope.launch {
                        val keys = selected.mapNotNull { k -> ProviderKey.entries.firstOrNull { it.key == k } }
                        keys.forEach { c.providers.setHistoryConsent(it, true); c.account.setConsent("history.${it.key}", true) }
                        val f = ImportField.entries.filter { it.key in fields }.toSet()
                        when (val r = c.history.start(keys, f, mode)) {
                            is ApiResult.Success -> {
                                c.analytics.track(AnalyticsEventName.HISTORY_IMPORT_STARTED, mapOf("providers_checked" to keys.size))
                                onStarted(r.value.sessionId)
                            }
                            is ApiResult.Failure -> { error = r.error; busy = false }
                        }
                    }
                })
            }
        }
    }
}

// ---------------------------------------------------------------- S-HIST-02
@Composable
fun ImportProgressScreen(sessionId: String, onBack: () -> Unit, onReview: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val session by remember(sessionId) { c.history.observe(sessionId) }.collectAsStateWithLifecycle(null)
    LaunchedEffect(session?.status) {
        if (session?.status == ImportStatus.AWAITING_REVIEW) {
            c.notifier?.importComplete(sessionId)
            onReview(sessionId)
        }
    }
    ScreenScaffold("Importing", onBack) { padding ->
        val s = session ?: run { LoadingState(); return@ScreenScaffold }
        LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item {
                val done = s.providers.count { it.status in setOf(ProviderImportStatus.COMPLETED, ProviderImportStatus.FAILED, ProviderImportStatus.SKIPPED) }
                ProgressRow("${s.status.label} · $done of ${s.providers.size} apps", done.toFloat() / s.providers.size.coerceAtLeast(1))
            }
            items(s.providers, key = { it.provider.key }) { p ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderMonogram(p.provider.displayName, size = 32)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(p.provider.displayName, style = MaterialTheme.typography.titleSmall)
                            Text("${p.status.label} · ${p.ordersFound} orders", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.status == ProviderImportStatus.FAILED) Pill("Skipped", tone = Tone.WARNING)
                    }
                    p.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (s.status == ImportStatus.CANCELLED) item { Banner("Import cancelled. Nothing was saved.", tone = Tone.INFO) }
            else if (s.status == ImportStatus.RUNNING) item { SecondaryButton("Cancel import", onClick = { scope.launch { c.history.cancel(sessionId) } }) }
        }
    }
}

// ---------------------------------------------------------------- S-HIST-03
@Composable
fun ImportReviewScreen(sessionId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val session by remember(sessionId) { c.history.observe(sessionId) }.collectAsStateWithLifecycle(null)
    var excluded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    ScreenScaffold("Review before saving", onBack) { padding ->
        val s = session ?: run { LoadingState(); return@ScreenScaffold }
        LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.navigationBarsPadding()) {
            item {
                Text(
                    if (s.uploadMode == PrivacyMode.DEVICE_ONLY) "Here's a sample of what was read. It stays on this phone." else "Here's a sample of what was read. Only these normalized fields will be synced to your account.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            s.providers.filter { it.status == ProviderImportStatus.FAILED }.forEach { f ->
                item { Banner(f.message ?: "${f.provider.displayName} was skipped.", tone = Tone.WARNING) }
            }
            if (s.reviewSample.isEmpty()) item { EmptyState("Nothing to review", "No orders were found.") }
            items(s.reviewSample, key = { it.orderId }) { o ->
                OrderRow(o, excluded = o.orderId in excluded, onToggle = { excluded = if (o.orderId in excluded) excluded - o.orderId else excluded + o.orderId })
            }
            item {
                val count = s.providers.sumOf { it.ordersFound }
                PrimaryButton(if (s.uploadMode == PrivacyMode.DEVICE_ONLY) "Save $count orders on this phone" else "Save and sync $count orders", loading = busy, onClick = {
                    busy = true
                    scope.launch {
                        c.history.confirmReview(sessionId, excluded)
                        c.analytics.track(AnalyticsEventName.HISTORY_IMPORT_FINISHED, mapOf("result" to "success"))
                        onDone()
                    }
                })
            }
            item { SecondaryButton("Discard import", onClick = { scope.launch { c.history.cancel(sessionId); onDone() } }) }
        }
    }
}

@Composable
private fun OrderRow(o: Order, excluded: Boolean = false, onToggle: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderMonogram(o.provider.displayName, size = 32)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text("${o.provider.displayName} · ${o.total.format()}", style = MaterialTheme.typography.titleSmall)
                Text("${Format.date(o.orderedAt)} · ${o.status.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(o.items.joinToString { "${it.name} × ${it.quantity}" }, style = MaterialTheme.typography.bodySmall)
            }
            if (o.extractionConfidence != com.smartcart.model.Confidence.HIGH) Pill("Check", tone = confidenceTone(o.extractionConfidence))
            onDelete?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Delete, contentDescription = "Delete order from ${o.provider.displayName} on ${Format.date(o.orderedAt)}") } }
        }
        onToggle?.let { CheckRow(!excluded, { _ -> it() }, "Include this order") }
        if (o.status == OrderStatus.REFUNDED || o.status == OrderStatus.CANCELLED) {
            Text("Not counted in monthly totals", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- S-HIST-04
class OrdersViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow<Load<List<Order>>>(Load.Loading)
    init { load() }
    fun load() { state.value = Load.Loading; viewModelScope.launch { state.value = c.history.orders().toLoad() } }
    fun delete(id: String) = viewModelScope.launch { c.history.deleteOrder(id); load() }
}

@Composable
fun OrdersScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel { OrdersViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold("Imported orders", onBack) { padding ->
        LoadContent(state, onRetry = vm::load) {
            val orders = (state as Load.Ready).value
            if (orders.isEmpty()) {
                EmptyState("No imported orders", "Connect your apps to see your order history here.", Modifier.padding(padding), actionLabel = "Connect order history", onAction = onConnect)
                return@LoadContent
            }
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(orders, key = { it.orderId }) { o -> OrderRow(o, onDelete = { vm.delete(o.orderId) }) }
            }
        }
    }
}
